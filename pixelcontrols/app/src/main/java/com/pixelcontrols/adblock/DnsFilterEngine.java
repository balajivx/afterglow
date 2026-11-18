package com.pixelcontrols.adblock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core DNS filtering logic, independent of Android so it can be tested on a desktop JVM.
 * The VPN service feeds it raw packets from the tun interface and gives it a way to write
 * packets back and to reach a real DNS server.
 */
public final class DnsFilterEngine {

    public interface Upstream {
        /** Sends a DNS query to a real resolver and returns its response, or null on timeout. */
        byte[] resolve(byte[] dnsQuery) throws IOException;
    }

    public interface Sink {
        void write(byte[] packet) throws IOException;
    }

    /** Called for every lookup (blocked or not), e.g. to work out which app made it. */
    public interface BlockListener {
        void onBlocked(DnsPacket.Query q, BlockedEntry entry);
    }

    public static final class BlockedEntry {
        public final String domain;
        public final long time;
        public volatile String app;              // filled in by the service when it can tell
        public final boolean blocked;
        BlockedEntry(String domain, long time, boolean blocked) { this.domain = domain; this.time = time; this.blocked = blocked; }
    }

    private volatile Blocklist blocklist;
    private final Upstream upstream;
    private final Sink sink;
    private final Executor executor;
    private volatile BlockListener listener;
    /** While paused everything is forwarded unfiltered (used by "Pause 10 min"). */
    public volatile boolean paused;

    public final AtomicLong totalQueries = new AtomicLong();
    public final AtomicLong blockedQueries = new AtomicLong();
    public final AtomicLong failedQueries = new AtomicLong();

    private static final int RECENT_MAX = 100;
    private final BlockedEntry[] recent = new BlockedEntry[RECENT_MAX];
    private int recentHead = 0, recentCount = 0;
    private static final int LOOKUPS_MAX = 300;
    private final BlockedEntry[] lookups = new BlockedEntry[LOOKUPS_MAX];
    private int lookupsHead = 0, lookupsCount = 0;

    public DnsFilterEngine(Blocklist blocklist, Upstream upstream, Sink sink, Executor executor) {
        this.blocklist = blocklist;
        this.upstream = upstream;
        this.sink = sink;
        this.executor = executor;
    }

    public void setBlocklist(Blocklist b) { this.blocklist = b; }
    public Blocklist getBlocklist() { return blocklist; }
    public void setListener(BlockListener l) { this.listener = l; }

    /** Handles one packet read from the tun interface. Returns false if it was dropped. */
    public boolean handlePacket(byte[] packet, int len) {
        final DnsPacket.Query q = DnsPacket.parse(packet, len);
        if (q == null) return false;
        totalQueries.incrementAndGet();

        final boolean block = !paused && q.name != null && blocklist.isBlocked(q.name);
        BlockedEntry logged = q.name == null ? null : logLookup(q.name, block);
        BlockListener l = listener;
        if (l != null && logged != null && logged.app == null) {
            try { l.onBlocked(q, logged); } catch (RuntimeException ignored) { }
        }

        if (block) {
            blockedQueries.incrementAndGet();
            remember(logged);
            try {
                byte[] resp = DnsPacket.blockedResponse(q);
                sink.write(DnsPacket.wrapResponse(q, resp, resp.length));
            } catch (IOException ignored) {
                // tun closed while shutting down
            }
            return true;
        }

        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] resp = upstream.resolve(q.dns);
                    if (resp == null || resp.length < 12) { failedQueries.incrementAndGet(); return; }
                    sink.write(DnsPacket.wrapResponse(q, resp, resp.length));
                } catch (IOException e) {
                    failedQueries.incrementAndGet();  // app's resolver will retry
                }
            }
        });
        return true;
    }

    /** Adds to the all-lookups log; repeats of the newest entry are collapsed. */
    private synchronized BlockedEntry logLookup(String domain, boolean blocked) {
        if (lookupsCount > 0) {
            BlockedEntry last = lookups[(lookupsHead - 1 + LOOKUPS_MAX) % LOOKUPS_MAX];
            if (last.domain.equals(domain) && last.blocked == blocked) return last;
        }
        BlockedEntry e = new BlockedEntry(domain, System.currentTimeMillis(), blocked);
        lookups[lookupsHead] = e;
        lookupsHead = (lookupsHead + 1) % LOOKUPS_MAX;
        if (lookupsCount < LOOKUPS_MAX) lookupsCount++;
        return e;
    }

    private synchronized void remember(BlockedEntry e) {
        if (recentCount > 0 && recent[(recentHead - 1 + RECENT_MAX) % RECENT_MAX] == e) return;
        recent[recentHead] = e;
        recentHead = (recentHead + 1) % RECENT_MAX;
        if (recentCount < RECENT_MAX) recentCount++;
    }

    /** Every recent lookup, blocked or not, most recent first. */
    public synchronized List<BlockedEntry> recentLookups() {
        List<BlockedEntry> out = new ArrayList<>(lookupsCount);
        for (int i = 1; i <= lookupsCount; i++) out.add(lookups[(lookupsHead - i + LOOKUPS_MAX) % LOOKUPS_MAX]);
        return out;
    }

    /** Most recent first. */
    public synchronized List<BlockedEntry> recentBlocked() {
        List<BlockedEntry> out = new ArrayList<>(recentCount);
        for (int i = 1; i <= recentCount; i++) out.add(recent[(recentHead - i + RECENT_MAX) % RECENT_MAX]);
        return out;
    }
}
