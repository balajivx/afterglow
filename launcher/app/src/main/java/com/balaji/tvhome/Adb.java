package com.balaji.tvhome;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * A small client for this TV's own debugging service (adb over the network, 127.0.0.1:5555), so the
 * Control Center can do what Android allows no app to: close another app at once (am force-stop) and read
 * how much memory each holds (dumpsys meminfo). Same commands the Mac runs over adb.
 *
 * The protocol: 24-byte little-endian headers (command, two args, length, checksum, magic); CNXN to
 * connect, AUTH with an RSA key, OPEN "shell:..." and read WRTE until CLSE. The key (2048-bit, made once,
 * kept in this app's private files) is new to the TV at first, so the TV asks "Allow USB debugging?"
 * once; with "Always allow" ticked it never asks again.
 *
 * Blocking: call off the main thread.
 */
final class Adb {
    private Adb() {
    }

    /** The TV hasn't accepted the key yet: its "Allow USB debugging?" prompt is showing (or was refused). */
    static final class NotAllowed extends IOException {
        NotAllowed() {
            super("Allow it on the TV's prompt");
        }
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3;
    /** How long the TV's prompt may wait for someone to press Allow. */
    private static final int PROMPT_MS = 60_000;

    /** Runs [command] in the TV's shell; its output. */
    static synchronized String shell(Context c, String command) throws IOException {
        KeyPair key = key(c);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, offered = false;
            Message m;
            while (true) {
                m = read(in);
                if (m.command == CNXN) break;
                if (m.command != AUTH || m.arg0 != TOKEN) throw new IOException("Debugging service said " + Integer.toHexString(m.command));
                if (!signed) { // first: prove we hold a key the TV already trusts
                    send(out, AUTH, SIGNATURE, 0, sign(key.getPrivate(), m.data));
                    signed = true;
                } else if (!offered) { // not trusted yet: offer it, and the TV asks the viewer
                    send(out, AUTH, PUBLIC_KEY, 0, publicKey((RSAPublicKey) key.getPublic()));
                    offered = true;
                    s.setSoTimeout(PROMPT_MS);
                } else {
                    throw new NotAllowed();
                }
            }
            s.setSoTimeout(15000);
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                try {
                    m = read(in);
                } catch (java.net.SocketTimeoutException e) {
                    if (offered) throw new NotAllowed();
                    throw e;
                }
                if (m.command == WRTE) {
                    text.write(m.data);
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.command == CLSE) {
                    send(out, CLSE, 1, m.arg0, new byte[0]);
                    break;
                }
            }
            return text.toString("UTF-8");
        } catch (java.net.SocketTimeoutException e) {
            throw new NotAllowed();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // ---- Messages

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Message read(InputStream in) throws IOException {
        ByteBuffer h = ByteBuffer.wrap(readFully(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("Odd message from the debugging service");
        m.data = readFully(in, length);
        return m;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int got = 0; got < n; ) {
            int k = in.read(b, got, n - got);
            if (k < 0) throw new IOException("The debugging service hung up");
            got += k;
        }
        return b;
    }

    // ---- The key: RSA 2048, kept in private files; signed tokens; Android's public-key format

    private static KeyPair key(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory f = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(java.nio.file.Files.readAllBytes(pub.toPath()))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(java.nio.file.Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair k = g.generateKeyPair();
            java.nio.file.Files.write(pub.toPath(), k.getPublic().getEncoded());
            java.nio.file.Files.write(priv.toPath(), k.getPrivate().getEncoded());
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The token signed as adbd checks it: PKCS#1 v1.5 over the token taken as a SHA-1 digest. */
    private static byte[] sign(PrivateKey k, byte[] token) throws GeneralSecurityException {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] digestInfo = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, digestInfo, 0, prefix.length);
        System.arraycopy(token, 0, digestInfo, prefix.length, token.length);
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(k);
        s.update(digestInfo);
        return s.sign();
    }

    /**
     * The public key in Android's own format (64 words: length, -1/n mod 2^32, n, R^2 mod n with
     * R = 2^2048, exponent; little-endian), base64, then the name the TV's prompt shows.
     */
    private static byte[] publicKey(RSAPublicKey k) {
        int words = 64;
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(k.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Afterglow@Hall-TV\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }
}
