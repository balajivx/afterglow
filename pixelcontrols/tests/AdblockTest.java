import com.pixelcontrols.adblock.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class AdblockTest {
    static int fails = 0, passes = 0;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] a) throws Exception {
        String mode = a[0];
        if (mode.equals("blocklist")) blocklist(a[1]);
        else if (mode.equals("engine")) engine(a[1], a[2], a[3], Integer.parseInt(a[4]));
        System.out.println(mode + ": " + passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void blocklist(String path) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.gc(); long before = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        Blocklist b = Blocklist.load(new FileInputStream(path), AdblockDefaults.allowlist(true));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.gc(); long after = rt.totalMemory() - rt.freeMemory();
        System.out.println("loaded " + b.size() + " domains in " + ms + " ms, ~" + (after - before) / 1024 + " KB");
        check(b.size() > 200_000, "size");

        String[] mustBlock = {
            "googleads.g.doubleclick.net", "ad.doubleclick.net", "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com", "x.y.googlesyndication.com", "app-measurement.com",
            "GOOGLESYNDICATION.COM.", "adservice.google.com", "ads.applovin.com", "popads.net", "aax.amazon-adsystem.com",
        };
        for (String d : mustBlock) {
            boolean bl = b.isBlocked(d);
            System.out.println("  " + (bl ? "BLOCK" : "allow") + "  " + d);
        }
        check(b.isBlocked("pagead2.googlesyndication.com"), "pagead2");
        check(b.isBlocked("x.y.googlesyndication.com"), "subdomain match");
        check(b.isBlocked("GOOGLESYNDICATION.COM."), "case + trailing dot");
        check(b.isBlocked("app-measurement.com"), "firebase analytics");

        // Must never be blocked: core services, video CDNs, OTT, payments, banking.
        String[] mustAllow = {
            "google.com", "www.google.com", "youtube.com", "www.youtube.com", "i.ytimg.com",
            "rr3---sn-ci5gup-qxae.googlevideo.com", "youtubei.googleapis.com", "www.googleapis.com",
            "play.googleapis.com", "android.clients.google.com", "mtalk.google.com",
            "fcm.googleapis.com", "firebaseinstallations.googleapis.com",
            "netflix.com", "www.netflix.com", "api-global.netflix.com", "nflxvideo.net",
            "primevideo.com", "atv-ps.amazon.com", "hotstar.com", "www.hotstar.com", "api.hotstar.com",
            "jiocinema.com", "sonyliv.com", "zee5.com", "spotify.com", "api.spotify.com",
            "whatsapp.net", "g.whatsapp.net", "instagram.com", "i.instagram.com", "facebook.com",
            "phonepe.com", "paytm.com", "upi.npci.org.in", "pay.google.com", "razorpay.com",
            "api.razorpay.com", "onlinesbi.sbi", "hdfcbank.com", "icicibank.com",
            "amazon.in", "flipkart.com", "rukminim1.flixcart.com", "swiggy.com", "zomato.com",
            "imasdk.googleapis.com", "dai.google.com", "app.link", "github.com", "claude.ai",
            "one.one.one.one", "dns.google",
        };
        for (String d : mustAllow) check(!b.isBlocked(d), "should allow " + d);

        check(b.isBlocked("ads.applovin.com") && b.isBlocked("popads.net") && b.isBlocked("config.unityads.unity3d.com"), "in-app ad SDKs + popunder nets");
        check(!b.isBlocked("pubads.g.doubleclick.net"), "video compat allows IMA");
        check(b.withAllowlist(AdblockDefaults.allowlist(false)).isBlocked("pubads.g.doubleclick.net"), "strict blocks IMA");
        check(b.isBlocked("googleads.g.doubleclick.net"), "compat still blocks googleads");
        // Allowlist behaviour
        Blocklist strict = b.withAllowlist(Collections.emptyList());
        Blocklist custom = b.withAllowlist(Arrays.asList("googlesyndication.com"));
        check(custom.isBlocked("ad.doubleclick.net") == strict.isBlocked("ad.doubleclick.net"), "unrelated unaffected");
        check(!custom.isBlocked("pagead2.googlesyndication.com"), "allowlist parent wins over block");
        check(Blocklist.of(Arrays.asList("example.com"), Arrays.asList("cdn.example.com")).isBlocked("x.example.com"), "block subdomain");
        check(!Blocklist.of(Arrays.asList("example.com"), Arrays.asList("cdn.example.com")).isBlocked("a.cdn.example.com"), "allow deeper");
        check(!Blocklist.of(Arrays.asList("example.com"), null).isBlocked("notexample.com"), "no partial-label match");
        check(!Blocklist.of(Arrays.asList("example.com"), null).isBlocked("example.com.evil"), "suffix only");
        check(!b.isBlocked(""), "empty");
        check(!b.isBlocked("bad name!"), "garbage");

        // User's own blocks (e.g. an OTT ad server found via Recent lookups)
        Blocklist ub = b.withUserBlocks(Arrays.asList("ads.ottexample.in"));
        check(ub.isBlocked("ads.ottexample.in") && ub.isBlocked("cdn.ads.ottexample.in"), "user block + subdomains");
        check(!ub.isBlocked("ottexample.in") && !ub.isBlocked("video.ottexample.in"), "user block doesn't hit siblings/parent");
        check(!ub.withAllowlist(Arrays.asList("ads.ottexample.in")).isBlocked("ads.ottexample.in"), "allowlist beats user block");
        check(ub.withAllowlist(Collections.emptyList()).isBlocked("ads.ottexample.in"), "user blocks survive allowlist change");
        check(ub.isBlocked("pagead2.googlesyndication.com") && !ub.isBlocked("www.youtube.com"), "base list intact");
        // OTT packs: block the ad servers, never the app's core servers
        Blocklist ott = b.withAllowlist(AdblockDefaults.allowlist(false)).withUserBlocks(AdblockDefaults.packDomains(null));
        String[] packMustBlock = {"ads.hotstar.com", "videoads.hotstar.com", "adsmanager.hotstar.com", "hesads.akamaized.net",
            "adtracker.jiocinema.com", "jioads.in", "events-analytics.hotstar.com", "ads-fa.spotify.com",
            "spotify.deliveryengine.adswizz.com", "gaana.deliveryengine.adswizz.com", "ads.jiosaavn.com",
            "ad-assets.wynk.in", "ads-server.mxplay.com", "pubads.g.doubleclick.net", "x.videorolls.row.aiv-cdn.net",
            "aax.amazon-adsystem.com", "c.amazon-adsystem.com"};
        for (String d : packMustBlock) check(ott.isBlocked(d), "pack should block " + d);
        String[] core = {"hotstar.com", "www.hotstar.com", "api.hotstar.com", "service.hotstar.com", "vs.hotstar.com",
            "hotstar.pubnub.net", "bifrost-api.hotstar.com", "jiocinema.com", "www.jiocinema.com", "apis-jiocinema.voot.com",
            "spotify.com", "open.spotify.com", "api.spotify.com", "spclient.wg.spotify.com", "apresolve.spotify.com",
            "audio-ak-spotify-com.akamaized.net", "i.scdn.co", "accounts.spotify.com",
            "gaana.com", "api.gaana.com", "jiosaavn.com", "www.jiosaavn.com", "wynk.in", "www.wynk.in",
            "mxplay.com", "api.mxplay.com", "sonyliv.com", "apiv2.sonyliv.com", "zee5.com", "gwapi.zee5.com",
            "primevideo.com", "www.primevideo.com", "atv-ps.amazon.com", "aiv-cdn.net", "amazon.in", "www.amazon.in",
            "hungama.com", "www.youtube.com", "music.youtube.com", "youtubei.googleapis.com", "rr1---sn-ci5gup-qxae.googlevideo.com",
            "i.ytimg.com", "imasdk.googleapis.com", "www.googleapis.com", "akamaized.net", "edgesuite.net", "akadns.net"};
        for (String d : core) check(!ott.isBlocked(d), "pack must NOT block core " + d);
        for (String d : AdblockDefaults.packDomains(null)) check(d.split("\\.").length >= 3 || d.equals("jioads.in") || d.equals("amazon-adsystem.com"),
            "pack entry too broad: " + d);
        Blocklist ottOff = b.withAllowlist(AdblockDefaults.allowlist(true));
        check(!ottOff.isBlocked("ads.hotstar.com") && !ottOff.isBlocked("pubads.g.doubleclick.net"), "packs inactive when Maximum OTT is off");
        System.out.println("OTT packs: " + AdblockDefaults.OTT_PACKS.size() + " apps, " + AdblockDefaults.packDomains(null).size() + " servers");
        // Hosts + adblock syntax
        String hosts = "# c\n0.0.0.0 ads.one.test\n127.0.0.1\ttrack.two.test # x\n||pix.three.test^\n*.four.test\nlocalhost\n0.0.0.0 0.0.0.0\n";
        Blocklist h = Blocklist.load(new ByteArrayInputStream(hosts.getBytes()), null);
        check(h.size() == 4, "hosts parse size=" + h.size());
        check(h.isBlocked("ads.one.test") && h.isBlocked("a.track.two.test") && h.isBlocked("pix.three.test") && h.isBlocked("x.four.test"), "hosts formats");

        // Lookup speed
        t0 = System.nanoTime();
        int n = 0;
        for (int i = 0; i < 1_000_000; i++) if (b.isBlocked("cdn" + (i & 1023) + ".static.example-site.com")) n++;
        System.out.println("1M lookups (5-label names): " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    /** Reads length-prefixed IP packets, runs them through the engine, writes responses. */
    static void engine(String listPath, String inPath, String outPath, int upstreamPort) throws Exception {
        Blocklist b = Blocklist.load(new FileInputStream(listPath), AdblockDefaults.allowlist(true));
        final List<byte[]> out = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(4);
        DnsFilterEngine.Upstream up = q -> {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(2000);
                s.send(new DatagramPacket(q, q.length, InetAddress.getLoopbackAddress(), upstreamPort));
                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                s.receive(r);
                return Arrays.copyOf(buf, r.getLength());
            }
        };
        DnsFilterEngine e = new DnsFilterEngine(b, up, out::add, ex);
        DataInputStream in = new DataInputStream(new FileInputStream(inPath));
        int handled = 0, dropped = 0;
        byte[] buf = new byte[32767];
        while (in.available() > 0) {
            int len = in.readInt();
            in.readFully(buf, 0, len);
            Arrays.fill(buf, len, Math.min(buf.length, len + 64), (byte) 0x5A); // stale bytes after packet must be ignored
            if (e.handlePacket(buf, len)) handled++; else dropped++;
            Arrays.fill(buf, 0, len, (byte) 0);  // engine must have copied what it needs
        }
        ex.shutdown();
        ex.awaitTermination(10, TimeUnit.SECONDS);
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(outPath))) {
            for (byte[] p : out) { o.writeInt(p.length); o.write(p); }
        }
        System.out.println("handled=" + handled + " dropped=" + dropped + " responses=" + out.size()
                + " total=" + e.totalQueries + " blocked=" + e.blockedQueries + " failed=" + e.failedQueries);
        List<DnsFilterEngine.BlockedEntry> rec = e.recentBlocked();
        System.out.println("recent blocked (newest first): " + rec.stream().map(x -> x.domain).limit(5).toList());
        List<DnsFilterEngine.BlockedEntry> lk = e.recentLookups();
        System.out.println("lookup log: " + lk.size() + " entries, e.g. " + lk.stream().limit(4).map(x -> (x.blocked ? "X " : "ok ") + x.domain).toList());
        check(lk.stream().anyMatch(x -> !x.blocked && x.domain.equals("api.hotstar.com")), "allowed lookups are logged");
        check(lk.stream().anyMatch(x -> x.blocked && x.domain.equals("ads.applovin.com")), "blocked lookups are logged");
        check(lk.stream().noneMatch(x -> x.domain.equals("pubads.g.doubleclick.net") && x.blocked), "compat domain logged as allowed");
        check(handled > 0, "handled");
    }
}
