package com.pixelcontrols.adblock;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DNS answer cache for weak networks. Pure Java (desktop-testable).
 *
 * - Fresh hits are answered on the phone, with no round trip over the weak link.
 * - Serve-stale (RFC 8767): when the network can't answer in time, the last known good answer
 *   (up to a day old) is returned with a short TTL, so apps that were working keep connecting.
 * - Answers keep their real TTLs, counted down; negative answers are cached briefly; SERVFAIL
 *   and truncated answers are never cached.
 */
public final class DnsCache {
    static final int MAX_ENTRIES = 4000;
    static final int MIN_TTL = 10, MAX_TTL = 3600, NEG_TTL = 60;
    static final long STALE_MAX_MS = 24L * 3600 * 1000;
    static final int STALE_SERVE_TTL = 30;
    private static final int TYPE_OPT = 41, TYPE_SOA = 6;

    private static final class CacheEntry {
        final byte[] resp;
        final int[] ttlOffsets;
        final long storedAt, expiresAt;
        CacheEntry(byte[] resp, int[] ttlOffsets, long storedAt, long expiresAt) {
            this.resp = resp; this.ttlOffsets = ttlOffsets; this.storedAt = storedAt; this.expiresAt = expiresAt;
        }
    }

    private final LinkedHashMap<String, CacheEntry> map = new LinkedHashMap<String, CacheEntry>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> e) { return size() > MAX_ENTRIES; }
    };

    static String key(DnsPacket.Query q) {
        if (q.name == null || q.questionEnd < 4) return null;
        int qclass = DnsPacket.u16(q.dns, q.questionEnd - 2);
        // DO bit / EDNS differences are ignored: Android's resolver asks the same way every time.
        return q.name + '/' + q.qtype + '/' + qclass;
    }

    /** Stores an upstream answer if it's cacheable. */
    public void put(DnsPacket.Query q, byte[] resp, long nowMs) {
        String k = key(q);
        if (k == null || resp == null || resp.length < 12) return;
        if ((resp[2] & 0x02) != 0) return;                        // truncated
        int rcode = resp[3] & 0x0F;
        if (rcode != 0 && rcode != 3) return;                      // only NOERROR and NXDOMAIN
        Parsed p = parse(resp, q);
        if (p == null) return;
        int ttl;
        if (rcode == 0 && p.answers > 0) ttl = clamp(p.minAnswerTtl, MIN_TTL, MAX_TTL);
        else ttl = Math.min(NEG_TTL, p.soaTtl > 0 ? p.soaTtl : NEG_TTL);   // NODATA / NXDOMAIN
        if (ttl <= 0) return;
        CacheEntry e = new CacheEntry(resp.clone(), p.ttlOffsets, nowMs, nowMs + ttl * 1000L);
        synchronized (map) { map.put(k, e); }
    }

    /**
     * A ready-to-send answer for q (its ID and question copied in, TTLs counted down), or null.
     * With allowStale, an expired answer up to a day old is returned with a 30 s TTL.
     */
    public byte[] get(DnsPacket.Query q, long nowMs, boolean allowStale) {
        String k = key(q);
        if (k == null) return null;
        CacheEntry e;
        synchronized (map) { e = map.get(k); }
        if (e == null) return null;
        int ttl;
        if (nowMs < e.expiresAt) ttl = (int) Math.max(1, (e.expiresAt - nowMs) / 1000);
        else if (allowStale && nowMs - e.storedAt < STALE_MAX_MS) ttl = STALE_SERVE_TTL;
        else return null;
        byte[] r = e.resp.clone();
        // The cached question has the same length (same name, maybe different letter case):
        // copy the asker's exact ID and question so their resolver accepts it.
        if (q.questionEnd > r.length) return null;
        System.arraycopy(q.dns, 0, r, 0, 2);
        System.arraycopy(q.dns, 12, r, 12, q.questionEnd - 12);
        for (int off : e.ttlOffsets) {
            r[off] = (byte) (ttl >>> 24); r[off + 1] = (byte) (ttl >>> 16);
            r[off + 2] = (byte) (ttl >>> 8); r[off + 3] = (byte) ttl;
        }
        return r;
    }

    public int size() { synchronized (map) { return map.size(); } }

    public void clear() { synchronized (map) { map.clear(); } }

    // ------------------------------------------------------------------ parsing
    static final class Parsed {
        int answers, minAnswerTtl = Integer.MAX_VALUE, soaTtl;
        int[] ttlOffsets;
    }

    /** Walks every record; null if malformed or if the question doesn't match q. */
    static Parsed parse(byte[] r, DnsPacket.Query q) {
        try {
            int qd = DnsPacket.u16(r, 4), an = DnsPacket.u16(r, 6), ns = DnsPacket.u16(r, 8), ar = DnsPacket.u16(r, 10);
            if (qd != 1) return null;
            int pos = 12;
            int qEnd = skipName(r, pos) + 4;
            // Same question length and type as asked (names compared case-insensitively).
            if (qEnd != q.questionEnd || qEnd > r.length) return null;
            for (int i = 12; i < qEnd; i++) {
                int a = r[i] & 0xFF, b = q.dns[i] & 0xFF;
                if (a != b && Character.toLowerCase((char) a) != Character.toLowerCase((char) b)) return null;
            }
            pos = qEnd;
            Parsed p = new Parsed();
            int[] offs = new int[an + ns + ar];
            int n = 0;
            for (int i = 0; i < an + ns + ar; i++) {
                pos = skipName(r, pos);
                if (pos + 10 > r.length) return null;
                int type = DnsPacket.u16(r, pos);
                long ttl = ((long) (r[pos + 4] & 0xFF) << 24) | ((r[pos + 5] & 0xFF) << 16) | ((r[pos + 6] & 0xFF) << 8) | (r[pos + 7] & 0xFF);
                int rdlen = DnsPacket.u16(r, pos + 8);
                if (type != TYPE_OPT) {
                    offs[n++] = pos + 4;
                    int t = (int) Math.min(ttl, Integer.MAX_VALUE);
                    if (i < an) { p.answers++; p.minAnswerTtl = Math.min(p.minAnswerTtl, t); }
                    else if (i < an + ns && type == TYPE_SOA) p.soaTtl = t;
                }
                pos += 10 + rdlen;
                if (pos > r.length) return null;
            }
            p.ttlOffsets = Arrays.copyOf(offs, n);
            return p;
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static int skipName(byte[] r, int pos) {
        for (int guard = 0; guard < 128; guard++) {
            int l = r[pos] & 0xFF;
            if (l == 0) return pos + 1;
            if ((l & 0xC0) == 0xC0) return pos + 2;               // compression pointer ends the name
            if ((l & 0xC0) != 0) throw new ArrayIndexOutOfBoundsException();
            pos += 1 + l;
        }
        throw new ArrayIndexOutOfBoundsException();
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
