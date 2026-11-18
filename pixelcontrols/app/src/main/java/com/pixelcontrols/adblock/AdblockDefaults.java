package com.pixelcontrols.adblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in compatibility rules. Pure Java (shared with desktop tests). */
public final class AdblockDefaults {
    private AdblockDefaults() {}

    /**
     * Google's video-ad request endpoints. When these fail, many in-app and web video players
     * (built on Google IMA / DAI) stall, show an error, or display "ad blocker detected".
     * Allowing them lets video play; ad creatives and trackers stay blocked where the list
     * covers them.
     */
    public static final List<String> VIDEO_COMPAT = Collections.unmodifiableList(Arrays.asList(
            "pubads.g.doubleclick.net",
            "imasdk.googleapis.com",
            "dai.google.com"
    ));

    /**
     * Apps that bypass the filter entirely. Excluded apps also can't see the VPN, which is
     * what payment and banking apps check for. Only packages actually installed are applied.
     */
    public static final List<String> EXCLUDED_APPS = Collections.unmodifiableList(Arrays.asList(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM
            "com.sbi.lotusintouch",                   // YONO SBI
            "com.csam.icici.bank.imobile",            // ICICI iMobile
            "com.snapwork.hdfc",                      // HDFC Bank
            "com.axis.mobile"                         // Axis Mobile
    ));

    /**
     * Per-app ad server packs, active with "Maximum OTT ad blocking". Curated from domains that
     * HaGeZi Pro++/Ultimate, AdGuard DNS, StevenBlack and GoodbyeAds block for each app (Nov 2026),
     * keeping only hosts whose names clearly serve ads, ad tracking or analytics. Ambiguous hosts that
     * could be core app services (e.g. service.hotstar.com, vs.hotstar.com, hotstar.pubnub.net) are
     * deliberately left out. Entries also block their subdomains.
     */
    public static final Map<String, List<String>> OTT_PACKS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("Hotstar / JioHotstar", Arrays.asList(
                "ads.hotstar.com", "adsmanager.hotstar.com", "videoads.hotstar.com", "hesads.akamaized.net",
                "adtracker.jiocinema.com", "adtracker2.jiocinema.com-v1.edgesuite.net",
                "adtracker2.jiocinema.com-v2.edgesuite.net", "adtracker.jiocinemas.com.akadns.net",
                "jioads.akamaized.net", "jioadsweb.akamaized.net", "jioads.jio.com", "jioads.in",
                "events-analytics.hotstar.com", "fp-analytics.hotstar.com", "hb-analytics.hotstar.com",
                "settings-analytics.hotstar.com", "logs.hotstar.com", "segment.hotstar.com", "cws-hotstar.conviva.com"));
        m.put("Spotify", Arrays.asList(
                "ads-fa.spotify.com", "ads.spotify.com", "adeventtracker.spotify.com",
                "adeventtrackermonitoring.spotify.com", "spotify.deliveryengine.adswizz.com", "spotify.mopub.com",
                "analytics.spotify.com", "pixel.spotify.com", "pixels.spotify.com", "pixel-static.spotify.com",
                "log.spotify.com", "log2.spotify.com", "crashdump.spotify.com", "spotify.demdex.net"));
        m.put("Gaana", Arrays.asList("gaana.deliveryengine.adswizz.com", "logs.gaana.com"));
        m.put("JioSaavn", Arrays.asList(
                "ads.jiosaavn.com", "saavn.adswizz.com", "deliveryengine.saavn.adswizz.com",
                "saavn.deliveryengine.adswizz.com", "stats.jiosaavn.com", "stats.saavn.com"));
        m.put("Wynk Music", Arrays.asList(
                "ad.wynk.in", "ad-assets.wynk.in", "adconfig.wynk.in", "adtech.wynk.in", "imgads.wynk.in",
                "stats.wynk.in", "uclm-tracker.wynk.in"));
        m.put("MX Player", Arrays.asList("ads-server.mxplay.com", "mxaserver.mxplay.com", "evt.mxplay.com"));
        m.put("SonyLIV / ZEE5 / other Google-ad OTT apps", Arrays.asList(
                "pubads.g.doubleclick.net", "securepubads.g.doubleclick.net", "dai.google.com",
                "sonypicturesnetworksindia.data.insert.io", "sonypicturesnetworksindia.device.insert.io"));
        m.put("Amazon Prime Video / miniTV", Arrays.asList("amazon-adsystem.com", "videorolls.row.aiv-cdn.net"));
        m.put("Hungama", Arrays.asList("ping.hungama.com"));
        OTT_PACKS = Collections.unmodifiableMap(m);
    }

    /** Domains of the enabled packs. */
    public static List<String> packDomains(java.util.Set<String> disabledPacks) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : OTT_PACKS.entrySet())
            if (disabledPacks == null || !disabledPacks.contains(e.getKey())) out.addAll(e.getValue());
        return out;
    }

    public static final String BLOCKLIST_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt";

    /** Effective allowlist = built-ins (if enabled) + the user's own entries. */
    public static List<String> allowlist(boolean videoCompat, String... user) {
        List<String> out = new ArrayList<>();
        if (videoCompat) out.addAll(VIDEO_COMPAT);
        if (user != null) for (String u : user) if (u != null && !u.trim().isEmpty()) out.add(u.trim());
        return out;
    }
}
