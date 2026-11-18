package com.pixelcontrols.adblock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;

/**
 * Memory-compact domain blocklist.
 *
 * Each domain is stored as a 64-bit FNV-1a hash in a sorted long[] (about 2 MB for 250k
 * domains instead of ~30 MB as a HashSet&lt;String&gt;). A list entry "example.com" blocks
 * example.com and every subdomain. Allowlist entries work the same way and always win.
 *
 * Pure Java (no Android dependencies) so it can be unit tested on a desktop JVM.
 */
public final class Blocklist {

    private final long[] blocked;
    private final long[] allowed;
    private final long[] userBlocked;   // the user's own additions (e.g. an OTT app's ad server)

    private Blocklist(long[] blocked, long[] allowed) { this(blocked, allowed, new long[0]); }

    private Blocklist(long[] blocked, long[] allowed, long[] userBlocked) {
        this.blocked = blocked;
        this.allowed = allowed;
        this.userBlocked = userBlocked;
    }

    public int size() { return blocked.length; }

    /** Parses plain-domain, hosts ("0.0.0.0 domain") and adblock ("||domain^") lines. */
    public static Blocklist load(InputStream in, Collection<String> allowlist) throws IOException {
        long[] buf = new long[1 << 18];
        int n = 0;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            String d = parseLine(line);
            if (d == null) continue;
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = hash(d);
        }
        return new Blocklist(sortedUnique(buf, n), hashAll(allowlist));
    }

    public static Blocklist of(Collection<String> blockDomains, Collection<String> allowlist) {
        long[] h = hashAll(blockDomains);
        return new Blocklist(h, hashAll(allowlist));
    }

    /** Returns a copy of this list with a different allowlist (cheap: no re-parsing). */
    public Blocklist withAllowlist(Collection<String> allowlist) {
        return new Blocklist(blocked, hashAll(allowlist), userBlocked);
    }

    /** Copy with the user's own block entries. Allowlist still wins over these. */
    public Blocklist withUserBlocks(Collection<String> domains) {
        return new Blocklist(blocked, allowed, hashAll(domains));
    }

    public boolean isBlocked(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        String d = normalize(domain);
        if (d == null) return false;
        // Allowlist wins at any level: "allow cdn.example.com" beats "block example.com".
        if (matchesAnySuffix(d, allowed)) return false;
        return matchesAnySuffix(d, userBlocked) || matchesAnySuffix(d, blocked);
    }

    public boolean isAllowlisted(String domain) {
        String d = normalize(domain);
        return d != null && matchesAnySuffix(d, allowed);
    }

    private static boolean matchesAnySuffix(String d, long[] set) {
        if (set.length == 0) return false;
        int start = 0;
        while (true) {
            if (Arrays.binarySearch(set, hash(d, start)) >= 0) return true;
            int dot = d.indexOf('.', start);
            if (dot < 0 || dot == d.length() - 1) return false;
            start = dot + 1;
        }
    }

    static String parseLine(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.charAt(0) == '#' || s.charAt(0) == '!' || s.charAt(0) == '[') return null;
        int hashPos = s.indexOf('#');
        if (hashPos > 0) s = s.substring(0, hashPos).trim();
        if (s.startsWith("||")) {
            int end = s.indexOf('^');
            if (end < 0) return null;
            s = s.substring(2, end);
        } else {
            int sp = lastWhitespace(s);
            if (sp >= 0) s = s.substring(sp + 1); // hosts format: take the hostname
        }
        if (s.startsWith("*.")) s = s.substring(2);
        String d = normalize(s);
        if (d == null) return null;
        if (d.equals("localhost") || d.equals("localhost.localdomain") || d.equals("local")
                || d.equals("broadcasthost") || d.equals("0.0.0.0") || d.indexOf('.') < 0) return null;
        return d;
    }

    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** Lowercases, strips a trailing dot, rejects anything that isn't a plausible hostname. */
    static String normalize(String s) {
        int len = s.length();
        if (len > 0 && s.charAt(len - 1) == '.') len--;
        if (len == 0 || len > 253) return null;
        StringBuilder sb = null;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_';
            if (!ok) {
                if (c >= 'A' && c <= 'Z') {
                    if (sb == null) { sb = new StringBuilder(len); sb.append(s, 0, i); }
                    sb.append((char) (c + 32));
                    continue;
                }
                return null;
            }
            if (sb != null) sb.append(c);
        }
        if (sb != null) return sb.toString();
        return len == s.length() ? s : s.substring(0, len);
    }

    private static long[] hashAll(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) return new long[0];
        long[] h = new long[domains.size()];
        int n = 0;
        for (String raw : domains) {
            if (raw == null) continue;
            String d = parseLine(raw);
            if (d != null) h[n++] = hash(d);
        }
        return sortedUnique(h, n);
    }

    private static long[] sortedUnique(long[] a, int n) {
        Arrays.sort(a, 0, n);
        int w = 0;
        for (int i = 0; i < n; i++) if (w == 0 || a[i] != a[w - 1]) a[w++] = a[i];
        return Arrays.copyOf(a, w);
    }

    static long hash(String s) { return hash(s, 0); }

    /** 64-bit FNV-1a over s[start..]. */
    static long hash(String s, int start) {
        long h = 0xcbf29ce484222325L;
        for (int i = start, n = s.length(); i < n; i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
