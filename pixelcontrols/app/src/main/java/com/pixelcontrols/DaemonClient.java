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

    /** Sends a command and returns the reply line (e.g. "OK:ON"). Retries transient connect drops. */
    public static String send(String command) throws IOException {
        IOException lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
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
            } catch (IOException e) {
                lastErr = e;
                if (attempt < 2) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastErr != null ? lastErr : new IOException("daemon unreachable");
    }

    /** Like send() but returns null instead of throwing, and checks for an OK reply. */
    public static boolean sendOk(String command) {
        try {
            return send(command).startsWith("OK:");
        } catch (IOException e) {
            return false;
        }
    }

    /** "STATUS:saver=1:has_nr=0:low_signal=1:since=1700000000" as a line for the app. */
    public static String describeStatus(String s) {
        if (s == null || !s.startsWith("STATUS:")) return "Unexpected reply: " + s;
        StringBuilder b = new StringBuilder();
        try {
            long t = Long.parseLong(field(s, "since")) * 1000L;
            if (t > 0) b.append("Running since ").append(android.text.format.DateUtils.getRelativeTimeSpanString(t,
                    System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)).append(". ");
        } catch (RuntimeException ignored) {
        }
        b.append("Data Saver ").append("1".equals(field(s, "saver")) ? "on" : "off");
        b.append(" · 5G ").append("0".equals(field(s, "has_nr")) ? "off (4G lock)" : "allowed");
        return b.toString();
    }

    static String field(String s, String key) {
        for (String part : s.substring(s.indexOf(':') + 1).split(":")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) return part.substring(eq + 1);
        }
        return "";
    }

    public static boolean isRunning() {
        try {
            return send("STATUS").startsWith("STATUS:");
        } catch (IOException e) {
            return false;
        }
    }
}
