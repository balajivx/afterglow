package com.pixelcontrols.adblock;

import java.util.Arrays;

/**
 * Minimal IPv4/UDP/DNS codec for the local DNS filter. Pure Java for desktop testing.
 *
 * The VPN routes only our fake DNS server address into the tun interface, so every
 * packet we see should be a UDP DNS query; anything else is dropped.
 */
public final class DnsPacket {

    public static final int TYPE_A = 1, TYPE_AAAA = 28;

    private DnsPacket() {}

    public static final class Query {
        public final byte[] srcIp = new byte[4], dstIp = new byte[4];
        public int srcPort, dstPort;
        /** Copy of the DNS payload (safe to keep after the tun read buffer is reused). */
        public byte[] dns;
        /** Lower-case query name without trailing dot, or null when it couldn't be parsed. */
        public String name;
        public int qtype;
        /** Offset in {@link #dns} just past the first question (QNAME+QTYPE+QCLASS). */
        public int questionEnd;
    }

    /** Returns null for anything that isn't an IPv4 UDP packet to port 53 with a DNS query. */
    public static Query parse(byte[] p, int len) {
        if (len < 20 + 8 + 12) return null;
        if ((p[0] >> 4 & 0xF) != 4) return null;
        int ihl = (p[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8 + 12) return null;
        int totalLen = u16(p, 2);
        if (totalLen < ihl + 8 || totalLen > len) return null;
        if ((p[9] & 0xFF) != 17) return null;               // UDP only
        if ((u16(p, 6) & 0x3FFF) != 0) return null;         // fragments: not worth handling
        int udp = ihl;
        int dstPort = u16(p, udp + 2);
        if (dstPort != 53) return null;
        int udpLen = u16(p, udp + 4);
        if (udpLen < 8 + 12 || udp + udpLen > totalLen) return null;

        Query q = new Query();
        System.arraycopy(p, 12, q.srcIp, 0, 4);
        System.arraycopy(p, 16, q.dstIp, 0, 4);
        q.srcPort = u16(p, udp);
        q.dstPort = dstPort;
        q.dns = Arrays.copyOfRange(p, udp + 8, udp + udpLen);

        byte[] d = q.dns;
        if ((d[2] & 0x80) != 0) return null;                 // QR=1: it's a response, not a query
        int qd = u16(d, 4);
        if (qd < 1) return q;                                // forward as-is, nothing to inspect
        int pos = 12;
        StringBuilder name = new StringBuilder(64);
        while (true) {
            if (pos >= d.length) return q;
            int l = d[pos] & 0xFF;
            if (l == 0) { pos++; break; }
            if ((l & 0xC0) != 0 || pos + 1 + l > d.length || name.length() + l > 253) return q; // malformed
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < l; i++) {
                char c = (char) (d[pos + 1 + i] & 0xFF);
                if (c >= 'A' && c <= 'Z') c += 32;
                name.append(c);
            }
            pos += 1 + l;
        }
        if (pos + 4 > d.length) return q;
        q.qtype = u16(d, pos);
        q.questionEnd = pos + 4;
        q.name = name.toString();
        return q;
    }

    /**
     * DNS answer for a blocked name: NOERROR with 0.0.0.0 (A) or :: (AAAA), and an empty
     * answer for other types. Null-routing instead of NXDOMAIN avoids aggressive retries
     * and is how AdGuard/NextDNS behave by default.
     */
    public static byte[] blockedResponse(Query q) {
        int qEnd = q.questionEnd;
        int rdLen = q.qtype == TYPE_A ? 4 : q.qtype == TYPE_AAAA ? 16 : 0;
        int answerLen = rdLen > 0 ? 12 + rdLen : 0;
        byte[] r = new byte[qEnd + answerLen];
        System.arraycopy(q.dns, 0, r, 0, qEnd);              // header + question
        r[2] = (byte) (0x80 | (q.dns[2] & 0x79));            // QR=1, keep opcode + RD, AA=0, TC=0
        r[3] = (byte) 0x80;                                  // RA=1, RCODE=0
        put16(r, 4, 1);                                      // QDCOUNT
        put16(r, 6, rdLen > 0 ? 1 : 0);                      // ANCOUNT
        put16(r, 8, 0);
        put16(r, 10, 0);
        if (rdLen > 0) {
            int a = qEnd;
            put16(r, a, 0xC00C);                             // name: pointer to question
            put16(r, a + 2, q.qtype);
            put16(r, a + 4, 1);                              // class IN
            put16(r, a + 6, 0);
            put16(r, a + 8, 300);                            // TTL 300 s
            put16(r, a + 10, rdLen);                         // rdata stays all zeros
        }
        return r;
    }

    /** Wraps a DNS payload into an IPv4/UDP packet travelling back to the querying app. */
    public static byte[] wrapResponse(Query q, byte[] dns, int dnsLen) {
        int udpLen = 8 + dnsLen;
        int total = 20 + udpLen;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        put16(p, 6, 0x4000);                                 // DF
        p[8] = 64;                                           // TTL
        p[9] = 17;
        System.arraycopy(q.dstIp, 0, p, 12, 4);              // from our fake DNS server...
        System.arraycopy(q.srcIp, 0, p, 16, 4);              // ...to the app
        put16(p, 10, checksum(p, 0, 20, 0));
        put16(p, 20, q.dstPort);
        put16(p, 22, q.srcPort);
        put16(p, 24, udpLen);
        System.arraycopy(dns, 0, p, 28, dnsLen);
        long pseudo = sum(p, 12, 8) + 17 + udpLen;
        int c = checksum(p, 20, udpLen, pseudo);
        put16(p, 26, c == 0 ? 0xFFFF : c);
        return p;
    }

    private static long sum(byte[] b, int off, int len) {
        long s = 0;
        int i = off, end = off + len;
        for (; i + 1 < end; i += 2) s += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        if (i < end) s += (b[i] & 0xFF) << 8;
        return s;
    }

    static int checksum(byte[] b, int off, int len, long initial) {
        long s = initial + sum(b, off, len);
        while ((s >> 16) != 0) s = (s & 0xFFFF) + (s >> 16);
        return (int) (~s & 0xFFFF);
    }

    static int u16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }
}
