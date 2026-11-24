package com.pixelcontrols.adblock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;

/**
 * Resolver tuned for weak, lossy links. Pure Java (desktop-testable).
 *
 * Asks the first server; if it hasn't answered after {@link #hedgeMs}, the same query also goes
 * to the next server (and so on). After {@link #retransmitMs} every server asked so far gets the
 * query again, covering packets lost on the way. The first valid answer wins. A SERVFAIL is kept
 * only as a last resort while other servers may still answer.
 */
public final class HedgedResolver {

    /** Creates the UDP socket (on Android: protected from the VPN and bound to the real network). */
    public interface SocketFactory {
        DatagramSocket create() throws IOException;
    }

    final SocketFactory factory;
    public int hedgeMs = 600, retransmitMs = 1500, deadlineMs = 4500;

    public HedgedResolver(SocketFactory factory) { this.factory = factory; }

    public byte[] resolve(byte[] query, List<InetSocketAddress> servers) throws IOException {
        if (servers == null || servers.isEmpty()) throw new IOException("no DNS servers");
        try (DatagramSocket s = factory.create()) {
            long start = System.currentTimeMillis(), deadline = start + deadlineMs;
            int asked = 0;
            send(s, query, servers.get(asked++));
            long nextHedge = start + hedgeMs, retransmitAt = start + retransmitMs;
            boolean retransmitted = false;
            byte[] servfail = null;
            byte[] buf = new byte[4096];
            while (true) {
                long now = System.currentTimeMillis();
                if (now >= deadline) {
                    if (servfail != null) return servfail;
                    throw new SocketTimeoutException("no DNS answer in " + deadlineMs + " ms");
                }
                if (asked < servers.size() && now >= nextHedge) {
                    send(s, query, servers.get(asked++));
                    nextHedge = now + hedgeMs;
                }
                if (!retransmitted && now >= retransmitAt) {
                    for (int i = 0; i < asked; i++) send(s, query, servers.get(i));
                    retransmitted = true;
                }
                long wake = deadline;
                if (asked < servers.size()) wake = Math.min(wake, nextHedge);
                if (!retransmitted) wake = Math.min(wake, retransmitAt);
                s.setSoTimeout((int) Math.max(1, wake - now));
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    int len = p.getLength();
                    if (len < 12 || buf[0] != query[0] || buf[1] != query[1] || (buf[2] & 0x80) == 0) continue;
                    if (!fromAsked(p, servers, asked)) continue;         // not from a server we asked
                    byte[] ans = Arrays.copyOf(buf, len);
                    if ((ans[3] & 0x0F) == 2) {                           // SERVFAIL: try the others first
                        servfail = ans;
                        if (asked < servers.size()) { send(s, query, servers.get(asked++)); nextHedge = System.currentTimeMillis() + hedgeMs; }
                        continue;
                    }
                    return ans;
                } catch (SocketTimeoutException ignored) {
                    // Loop back to evaluate deadline/hedge/retransmit based on current time
                }
            }
        }
    }

    private static boolean fromAsked(DatagramPacket p, List<InetSocketAddress> servers, int asked) {
        for (int i = 0; i < asked; i++) {
            InetSocketAddress a = servers.get(i);
            if (a.getAddress().equals(p.getAddress()) && a.getPort() == p.getPort()) return true;
        }
        return false;
    }

    private static void send(DatagramSocket s, byte[] q, InetSocketAddress to) {
        try {
            s.send(new DatagramPacket(q, q.length, to));
        } catch (IOException ignored) {
            // e.g. no route to this server right now; the others may still work
        }
    }
}
