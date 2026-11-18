package com.pixelcontrols;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Client for the shell helper started by start_daemon.sh (runs as the ADB shell user, so it
 * can change global settings an app can't). One command per connection, one reply line.
 * Never call on the main thread.
 */
public final class DaemonClient {
    private static final int PORT = 28888;

    private DaemonClient() {}

    /** Sends a command and returns the reply line (e.g. "OK:ON"). Throws if the daemon is down. */
    public static String send(String command) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) throw new IOException("daemon closed the connection");
            return line.trim();
        }
    }

    /** Like send() but returns null instead of throwing, and checks for an OK reply. */
    public static boolean sendOk(String command) {
        try {
            return send(command).startsWith("OK:");
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isRunning() {
        try {
            return send("STATUS").startsWith("STATUS:");
        } catch (IOException e) {
            return false;
        }
    }
}
