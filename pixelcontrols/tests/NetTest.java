import com.pixelcontrols.adblock.*;

import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** DNS accelerator tests: cache + serve-stale, and the racing resolver on simulated weak links. */
public class NetTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + what); } }

    // ------------------------------------------------------------------ DNS message builders
    static byte[] query(String name, int type, int id) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8); o.write(id); o.write(0x01); o.write(0x00);
        o.write(0); o.write(1); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0);
        for (String l : name.split("\\.")) { o.write(l.length()); o.writeBytes(l.getBytes()); }
        o.write(0); o.write(type >> 8); o.write(type); o.write(0); o.write(1);
        return o.toByteArray();
    }

    /** Response to q: rcode, A answers with given TTLs (name = pointer to question), optional SOA + OPT. */
    static byte[] response(byte[] q, int rcode, int[] ttls, int soaTtl, boolean opt) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(q[0]); o.write(q[1]); o.write(0x81); o.write(0x80 | rcode);
        o.write(0); o.write(1); o.write(0); o.write(ttls.length); o.write(0); o.write(soaTtl > 0 ? 1 : 0); o.write(0); o.write(opt ? 1 : 0);
        o.write(q, 12, q.length - 12);
        for (int i = 0; i < ttls.length; i++) {
            o.write(0xC0); o.write(0x0C); o.write(0); o.write(1); o.write(0); o.write(1);
            int t = ttls[i]; o.write(t >>> 24); o.write(t >>> 16); o.write(t >>> 8); o.write(t);
            o.write(0); o.write(4); o.write(93); o.write(184); o.write(216); o.write(34 + i);
        }
        if (soaTtl > 0) {
            o.write(0xC0); o.write(0x0C); o.write(0); o.write(6); o.write(0); o.write(1);
            o.write(soaTtl >>> 24); o.write(soaTtl >>> 16); o.write(soaTtl >>> 8); o.write(soaTtl);
            byte[] rd = new byte[22]; rd[0] = 0; rd[1] = 0;   // mname=root, rname=root, 5 x 32-bit
            o.write(0); o.write(rd.length); o.writeBytes(rd);
        }
        if (opt) { o.write(0); o.write(0); o.write(41); o.write(0x10); o.write(0); o.write(0); o.write(0x80); o.write(0); o.write(0); o.write(0); o.write(0); }
        return o.toByteArray();
    }

    /** Wraps a DNS query as the IPv4/UDP packet an app would send to the VPN's DNS address. */
    static byte[] ipPacket(byte[] dns, int srcPort) {
        byte[] p = new byte[28 + dns.length];
        p[0] = 0x45; p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length; p[8] = 64; p[9] = 17;
        p[12] = 10; p[13] = 111; p[14] = (byte) 222; p[15] = 2; p[16] = 10; p[17] = 111; p[18] = (byte) 222; p[19] = 1;
        p[20] = (byte) (srcPort >> 8); p[21] = (byte) srcPort; p[22] = 0; p[23] = 53;
        p[24] = (byte) ((8 + dns.length) >> 8); p[25] = (byte) (8 + dns.length);
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    static int ttlAt(byte[] r, int off) { return ((r[off] & 255) << 24) | ((r[off + 1] & 255) << 16) | ((r[off + 2] & 255) << 8) | (r[off + 3] & 255); }

    // ------------------------------------------------------------------ simulated DNS servers
    enum Mode { OK, DROP, SLOW, SERVFAIL, LOSE_FIRST, BOGUS_FIRST, LOSSY }

    static final class FakeServer implements Runnable {
        final DatagramSocket s; final Mode mode; final AtomicInteger received = new AtomicInteger();
        final Set<Integer> seenIds = ConcurrentHashMap.newKeySet();
        final Random rnd; final double loss;
        FakeServer(Mode m) throws Exception { this(m, 0, 1); }
        FakeServer(Mode m, double loss, long seed) throws Exception {
            s = new DatagramSocket(0, InetAddress.getLoopbackAddress()); mode = m; this.loss = loss; rnd = new Random(seed);
            Thread t = new Thread(this); t.setDaemon(true); t.start();
        }
        InetSocketAddress addr() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), s.getLocalPort()); }
        public void run() {
            byte[] buf = new byte[1500];
            while (true) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length); s.receive(p);
                    received.incrementAndGet();
                    byte[] q = Arrays.copyOf(buf, p.getLength());
                    int id = ((q[0] & 255) << 8) | (q[1] & 255);
                    if (mode == Mode.DROP) continue;
                    if (mode == Mode.LOSE_FIRST && seenIds.add(id)) continue;
                    if (mode == Mode.LOSSY && rnd.nextDouble() < loss) continue;          // request lost
                    if (mode == Mode.SLOW) Thread.sleep(2000);
                    byte[] r = response(q, mode == Mode.SERVFAIL ? 2 : 0, mode == Mode.SERVFAIL ? new int[0] : new int[]{300}, 0, false);
                    if (mode == Mode.BOGUS_FIRST) {
                        byte[] bad = r.clone(); bad[0] ^= 0x55;                                 // wrong ID
                        s.send(new DatagramPacket(bad, bad.length, p.getSocketAddress()));
                    }
                    if (mode == Mode.LOSSY && rnd.nextDouble() < loss) continue;          // answer lost
                    s.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                } catch (Exception e) { return; }
            }
        }
    }

    static HedgedResolver resolver() { return new HedgedResolver(new HedgedResolver.SocketFactory() { public DatagramSocket create() throws java.io.IOException { return new DatagramSocket(); } }); }

    /** The pre-2.5 behaviour: one query per server, waited on in turn (5 s, then 3.5 s each). */
    static byte[] oldResolve(byte[] q, List<InetSocketAddress> servers) throws Exception {
        int attempt = 0;
        for (InetSocketAddress a : servers) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(attempt++ == 0 ? 5000 : 3500);
                s.send(new DatagramPacket(q, q.length, a));
                byte[] b = new byte[4096]; DatagramPacket p = new DatagramPacket(b, b.length); s.receive(p);
                if (b[0] == q[0] && b[1] == q[1]) return Arrays.copyOf(b, p.getLength());
            } catch (SocketTimeoutException ignored) { }
        }
        throw new SocketTimeoutException();
    }

    public static void main(String[] a) throws Exception {
        // ================================================================ cache
        System.out.println("== DNS cache");
        DnsCache c = new DnsCache();
        byte[] q1 = query("www.youtube.com", 1, 0x1111);
        DnsPacket.Query pq1 = DnsPacket.parse(ipPacket(q1, 40001), 28 + q1.length);
        c.put(pq1, response(q1, 0, new int[]{300, 120}, 0, true), 0);
        check(c.size() == 1, "answer cached");
        byte[] q2 = query("WWW.YouTube.com", 1, 0x2222);           // later lookup, different ID + case
        DnsPacket.Query pq2 = DnsPacket.parse(ipPacket(q2, 40002), 28 + q2.length);
        byte[] hit = c.get(pq2, 20_000, false);
        check(hit != null, "fresh hit");
        check(hit[0] == 0x22 && hit[1] == 0x22, "asker's ID copied in");
        check(new String(hit, 13, 3).equals("WWW"), "asker's exact question copied in (0x20 case)");
        int off = q2.length;                                        // first answer's TTL at off+6
        check(ttlAt(hit, off + 6) == 100 && ttlAt(hit, off + 6 + 16) == 100, "TTLs counted down: 120 s TTL, 20 s later -> 100 (got " + ttlAt(hit, off + 6) + ")");
        int optTtlOff = off + 32 + 5;                               // OPT: name(1) type(2) class(2), then flags
        check(ttlAt(hit, optTtlOff) == 0x00800000, "EDNS OPT flags never rewritten as a TTL (got 0x" + Integer.toHexString(ttlAt(hit, optTtlOff)) + ")");
        check(c.get(pq2, 121_000, false) == null, "expired after shortest TTL");
        byte[] stale = c.get(pq2, 3_600_000, true);
        check(stale != null && ttlAt(stale, off + 6) == 30, "serve-stale after expiry, 30 s TTL");
        check(c.get(pq2, 25 * 3_600_000L, true) == null, "not served stale after a day");
        byte[] qa = query("www.youtube.com", 28, 0x3333);           // AAAA is a different entry
        check(c.get(DnsPacket.parse(ipPacket(qa, 40003), 28 + qa.length), 1000, false) == null, "type is part of the key");
        // negative + uncacheable answers
        byte[] qn = query("nope.example", 1, 7);
        DnsPacket.Query pqn = DnsPacket.parse(ipPacket(qn, 40004), 28 + qn.length);
        c.put(pqn, response(qn, 3, new int[0], 900, false), 0);
        check(c.get(pqn, 59_000, false) != null && c.get(pqn, 61_000, false) == null, "NXDOMAIN cached for at most 60 s");
        byte[] qs = query("sf.example", 1, 8);
        DnsPacket.Query pqs = DnsPacket.parse(ipPacket(qs, 40005), 28 + qs.length);
        c.put(pqs, response(qs, 2, new int[0], 0, false), 0);
        check(c.get(pqs, 1, true) == null, "SERVFAIL never cached");
        byte[] tr = response(qs, 0, new int[]{300}, 0, false); tr[2] |= 0x02;
        c.put(pqs, tr, 0);
        check(c.get(pqs, 1, true) == null, "truncated answers never cached");
        byte[] other = response(query("other.example", 1, 8), 0, new int[]{300}, 0, false);
        c.put(pqs, other, 0);
        check(c.get(pqs, 1, true) == null, "answer for a different question rejected");
        byte[] mal = Arrays.copyOf(response(qs, 0, new int[]{300}, 0, false), 40);
        c.put(pqs, mal, 0);
        check(c.get(pqs, 1, true) == null, "malformed answer rejected");
        check(c.get(pq1, 1, false) != null, "zero TTL floored to keep at least 10 s? (300 here)");
        byte[] q0 = query("zero.example", 1, 9); DnsPacket.Query pq0 = DnsPacket.parse(ipPacket(q0, 40006), 28 + q0.length);
        c.put(pq0, response(q0, 0, new int[]{0}, 0, false), 0);
        check(c.get(pq0, 9_000, false) != null && c.get(pq0, 11_000, false) == null, "TTL 0 floored to 10 s");
        DnsCache big = new DnsCache();
        for (int i = 0; i < 5000; i++) {
            byte[] qq = query("h" + i + ".example", 1, i);
            big.put(DnsPacket.parse(ipPacket(qq, 40000), 28 + qq.length), response(qq, 0, new int[]{300}, 0, false), 0);
        }
        check(big.size() == 4000, "bounded at 4000 entries (LRU)");

        // ================================================================ racing resolver
        System.out.println("== Racing resolver (simulated weak network)");
        FakeServer ok = new FakeServer(Mode.OK), drop = new FakeServer(Mode.DROP), slow = new FakeServer(Mode.SLOW),
                sf = new FakeServer(Mode.SERVFAIL), loseFirst = new FakeServer(Mode.LOSE_FIRST), bogus = new FakeServer(Mode.BOGUS_FIRST);
        HedgedResolver r = resolver();
        long t0 = System.currentTimeMillis();
        byte[] ans = r.resolve(query("a.example", 1, 101), Arrays.asList(drop.addr(), ok.addr()));
        long dt = System.currentTimeMillis() - t0;
        System.out.println("   carrier DNS silent, backup healthy: answered in " + dt + " ms");
        check(ans != null && dt >= 550 && dt < 1200, "silent carrier DNS: backup answers after ~0.6 s (" + dt + " ms)");
        t0 = System.currentTimeMillis();
        ans = r.resolve(query("b.example", 1, 102), Arrays.asList(slow.addr(), ok.addr()));
        dt = System.currentTimeMillis() - t0;
        System.out.println("   carrier DNS slow (2 s), backup healthy: answered in " + dt + " ms");
        check(dt < 1200, "slow carrier DNS doesn't stall the lookup (" + dt + " ms)");
        ans = r.resolve(query("c.example", 1, 103), Arrays.asList(sf.addr(), ok.addr()));
        check((ans[3] & 15) == 0, "SERVFAIL from one server: the good answer wins");
        ans = r.resolve(query("c2.example", 1, 104), Arrays.asList(sf.addr()));
        check((ans[3] & 15) == 2, "only SERVFAIL available: returned as last resort");
        t0 = System.currentTimeMillis();
        ans = r.resolve(query("d.example", 1, 105), Arrays.asList(loseFirst.addr()));
        dt = System.currentTimeMillis() - t0;
        System.out.println("   single server, first packet lost: answered in " + dt + " ms (resend)");
        check(ans != null && dt >= 1400 && dt < 2200, "lost packet recovered by the 1.5 s resend (" + dt + " ms)");
        ans = r.resolve(query("e.example", 1, 106), Arrays.asList(bogus.addr()));
        check(ans[0] == 0 && (ans[1] & 255) == 106, "forged answer with the wrong ID ignored");
        t0 = System.currentTimeMillis();
        boolean threw = false;
        try { r.resolve(query("f.example", 1, 107), Arrays.asList(drop.addr(), drop.addr())); } catch (SocketTimeoutException e) { threw = true; }
        dt = System.currentTimeMillis() - t0;
        check(threw && dt >= 4400 && dt < 5200, "everything silent: gives up at 4.5 s (" + dt + " ms), then serve-stale takes over");

        // ================================================================ head to head: 30% loss
        System.out.println("== Head to head on a 30%-loss link (each direction), 60 lookups");
        for (String setup : new String[]{"one lossy server", "lossy carrier DNS + lossy backup"}) {
            int okOld = 0, okNew = 0; long tOld = 0, tNew = 0;
            FakeServer l1 = new FakeServer(Mode.LOSSY, 0.30, 7), l2 = new FakeServer(Mode.LOSSY, 0.30, 11);
            List<InetSocketAddress> servers = setup.startsWith("one") ? Arrays.asList(l1.addr()) : Arrays.asList(l1.addr(), l2.addr());
            ExecutorService ex = Executors.newFixedThreadPool(20);
            List<Future<long[]>> fs = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                final int id = 2000 + i;
                fs.add(ex.submit(() -> {
                    long s0 = System.currentTimeMillis(); long o = 0, n = 0;
                    try { oldResolve(query("x" + id + ".example", 1, id), servers); o = 1; } catch (Exception e) { }
                    long s1 = System.currentTimeMillis();
                    try { resolver().resolve(query("y" + id + ".example", 1, id + 5000), servers); n = 1; } catch (Exception e) { }
                    long s2 = System.currentTimeMillis();
                    return new long[]{o, s1 - s0, n, s2 - s1};
                }));
            }
            for (Future<long[]> f : fs) { long[] v = f.get(); okOld += v[0]; tOld += v[1]; okNew += v[2]; tNew += v[3]; }
            ex.shutdown();
            System.out.printf("   %-34s old: %2d/60 answered, avg %4d ms | new: %2d/60 answered, avg %4d ms%n", setup, okOld, tOld / 60, okNew, tNew / 60);
            check(okNew > okOld, setup + ": more lookups succeed");
            check(tNew < tOld, setup + ": faster on average");
        }

        // ================================================================ engine: cache + stale end to end
        System.out.println("== Engine: repeat lookups and a network dropout");
        Blocklist bl = Blocklist.of(Arrays.asList("ads.example"), null);
        final List<byte[]> out = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final boolean[] networkDown = {false};
        DnsFilterEngine e = new DnsFilterEngine(bl, dq -> {
            upstreamCalls.incrementAndGet();
            if (networkDown[0]) throw new SocketTimeoutException("weak link");
            return response(dq, 0, new int[]{60}, 0, false);
        }, out::add, Runnable::run);
        for (int i = 0; i < 5; i++) { byte[] dq = query("api.hotstar.com", 1, 500 + i); e.handlePacket(ipPacket(dq, 41000 + i), 28 + dq.length); }
        check(upstreamCalls.get() == 1 && e.cacheHits.get() == 4 && out.size() == 5, "5 lookups, 1 network trip, 4 instant (" + upstreamCalls.get() + " trips)");
        networkDown[0] = true;
        e.testClockOffsetMs = 120_000;                             // answer expired, network now failing
        byte[] dq = query("api.hotstar.com", 1, 600);
        e.handlePacket(ipPacket(dq, 41100), 28 + dq.length);
        byte[] last = out.get(out.size() - 1);
        check(e.staleServed.get() == 1 && (last[28 + 3] & 15) == 0 && (last[28] & 255) == 0x02 && (last[29] & 255) == 0x58, "dropout: last good answer served (stale) with the asker's ID");
        byte[] dq2 = query("never-seen.example", 1, 601);
        int before = out.size();
        e.handlePacket(ipPacket(dq2, 41101), 28 + dq2.length);
        check(out.size() == before && e.failedQueries.get() == 1, "unknown name during dropout: no fake answer invented");
        byte[] ad = query("ads.example", 1, 602);
        e.handlePacket(ipPacket(ad, 41102), 28 + ad.length);
        check(e.blockedQueries.get() == 1, "blocking still applies with the cache on");

        System.out.println("net: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
