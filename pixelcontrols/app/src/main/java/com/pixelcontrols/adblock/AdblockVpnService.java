package com.pixelcontrols.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import com.pixelcontrols.MainActivity;
import com.pixelcontrols.R;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DNS-only local VPN. Only our fake DNS server address is routed into the tunnel, so apps'
 * actual traffic never passes through it (no speed or battery cost beyond DNS lookups).
 * Queries for ad/tracker domains are answered with 0.0.0.0; everything else is forwarded to
 * the real network's DNS servers.
 */
public class AdblockVpnService extends VpnService {
    public static final String ACTION_START = "com.pixelcontrols.adblock.START";
    private static final String TAG = "PCAdblock";
    static final String FAKE_DNS = "10.111.222.1", TUN_ADDR = "10.111.222.2";
    private static final String CHANNEL = "adblock";
    private static final int NOTIF_ID = 1;
    static final long PAUSE_MS = 10 * 60 * 1000L;

    static volatile AdblockVpnService instance;

    private final Object writeLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private HedgedResolver resolver;
    private volatile ParcelFileDescriptor tun;
    private volatile FileDescriptor tunFd;
    private volatile boolean running;
    private volatile DnsFilterEngine engine;
    private volatile Blocklist blocklist;
    private volatile long pausedUntil;
    private volatile boolean changedPrivateDns;

    private volatile List<InetAddress> upstream;
    private volatile Network upstreamNet;
    private volatile long upstreamAt;
    private final Map<Integer, String> appNames = new ConcurrentHashMap<>();

    public static AdblockVpnService get() { return instance; }

    public static boolean isRunning() {
        AdblockVpnService s = instance;
        return s != null && s.running;
    }

    public static DnsFilterEngine currentEngine() {
        AdblockVpnService s = instance;
        return s == null ? null : s.engine;
    }

    public static int listSize() {
        AdblockVpnService s = instance;
        Blocklist b = s == null ? null : s.blocklist;
        return b == null ? 0 : b.size();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Lookups can wait several seconds on a weak link: enough threads that one slow lookup
        // never queues behind another.
        pool = Executors.newFixedThreadPool(16);
        resolver = new HedgedResolver(new HedgedResolver.SocketFactory() {
            @Override public DatagramSocket create() throws IOException {
                DatagramSocket s = new DatagramSocket();
                protect(s);
                Network n = upstreamNet;
                if (n != null) {
                    try { n.bindSocket(s); } catch (IOException ignored) { }
                }
                return s;
            }
        });
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Adblock", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent or android.net.VpnService action means a system restart / Always-on VPN.
        Adblock.prefs(this).edit().putBoolean("enabled", true).apply();
        if (!running) startTunnelAsync();
        return START_STICKY;
    }

    private void startTunnelAsync() {
        running = true;
        new Thread(new Runnable() { @Override public void run() {
            try {
                if (blocklist == null) blocklist = Adblock.loadBlocklist(AdblockVpnService.this);
                fixPrivateDnsIfStrict();
                establish();
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                running = false;
                Adblock.prefs(AdblockVpnService.this).edit().putBoolean("enabled", false).apply();
                Adblock.toast(AdblockVpnService.this, "Adblock couldn't start: " + e.getMessage());
                stopSelf();
            }
            Adblock.refreshTile(AdblockVpnService.this);
        } }, "adblock-start").start();
    }

    /** Builds (or rebuilds) the tunnel. Rebuilding swaps interfaces without a DNS gap. */
    private synchronized void establish() throws IOException {
        Builder b = new Builder()
                .setSession(getString(R.string.vpn_session))
                .addAddress(TUN_ADDR, 32)
                .addRoute(FAKE_DNS, 32)
                .addDnsServer(FAKE_DNS)
                .setMtu(1500)
                .setBlocking(false)
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6);   // IPv6 traffic bypasses (DNS still comes to us)
        try {
            b.addDisallowedApplication(getPackageName());  // our own lookups/updates go direct
        } catch (PackageManager.NameNotFoundException ignored) { }
        for (String pkg : Adblock.excludedApps(this)) {
            try { b.addDisallowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
        }
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE));

        ParcelFileDescriptor pfd = b.establish();
        if (pfd == null) throw new IOException("VPN permission was revoked");

        DnsFilterEngine old = engine;
        final DnsFilterEngine e = new DnsFilterEngine(blocklist, new DnsFilterEngine.Upstream() {
            @Override public byte[] resolve(byte[] q) throws IOException { return resolveUpstream(q); }
        }, new DnsFilterEngine.Sink() {
            @Override public void write(byte[] p) throws IOException { writePacket(p); }
        }, pool);
        if (old != null) {
            e.totalQueries.set(old.totalQueries.get());
            e.blockedQueries.set(old.blockedQueries.get());
        }
        e.paused = isPaused();
        if (focusGate == null) focusGate = new FocusGate(this, new FocusGate.UidLookup() {
            @Override public int ownerUid(DnsPacket.Query q) { return queryUid(q); }
        });
        e.gate = focusGate;   // Low signal: the app on screen gets the weak link
        e.setListener(new DnsFilterEngine.BlockListener() {
            @Override public void onBlocked(DnsPacket.Query q, DnsFilterEngine.BlockedEntry entry) { AdblockVpnService.this.onBlocked(q, entry); }
        });

        ParcelFileDescriptor previous = tun;
        engine = e;
        tun = pfd;
        tunFd = pfd.getFileDescriptor();
        final FileDescriptor fd = tunFd;
        new Thread(new Runnable() { @Override public void run() { readLoop(fd, e); } }, "adblock-tun").start();
        closeQuietly(previous);

        main.removeCallbacks(tick);
        main.post(tick);
    }

    private void readLoop(FileDescriptor fd, DnsFilterEngine e) {
        byte[] buf = new byte[32767];
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = {p};
        while (running && tunFd == fd) {
            try {
                p.revents = 0;
                if (Os.poll(fds, 1000) <= 0) continue;          // timeout lets us notice shutdown
                if ((p.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) break;
                int len = Os.read(fd, buf, 0, buf.length);
                if (len > 0) e.handlePacket(buf, len);
            } catch (ErrnoException ex) {
                if (ex.errno == OsConstants.EAGAIN || ex.errno == OsConstants.EINTR) continue;
                break;
            } catch (InterruptedIOException ex) {
                break;
            } catch (RuntimeException ex) {
                Log.w(TAG, "bad packet", ex);   // never let one odd packet kill filtering
            }
        }
    }

    private void writePacket(byte[] p) throws IOException {
        FileDescriptor fd = tunFd;
        if (fd == null) throw new IOException("tunnel closed");
        synchronized (writeLock) {
            try {
                Os.write(fd, p, 0, p.length);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }
    }

    // ------------------------------------------------------------------ upstream DNS
    private byte[] resolveUpstream(byte[] query) throws IOException {
        List<InetSocketAddress> servers = new ArrayList<>();
        for (InetAddress a : upstreamServers()) servers.add(new InetSocketAddress(a, 53));
        try {
            return resolver.resolve(query, servers);
        } catch (IOException e) {
            upstreamAt = 0;   // network may have changed: re-read servers next time
            throw e;
        }
    }

    private List<InetAddress> upstreamServers() {
        long now = SystemClock.elapsedRealtime();
        List<InetAddress> cached = upstream;
        if (cached != null && now - upstreamAt < 15_000) return cached;
        List<InetAddress> out = new ArrayList<>();
        Network net = null;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            net = cm.getActiveNetwork();     // we're excluded from our own VPN: this is Wi-Fi/mobile
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (!a.isLoopbackAddress() && !FAKE_DNS.equals(a.getHostAddress()) && !out.contains(a)) out.add(a);
                }
            }
            InetAddress cf = InetAddress.getByAddress(new byte[]{1, 1, 1, 1});
            InetAddress gg = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
            if (!out.contains(cf)) out.add(cf);
            if (!out.contains(gg)) out.add(gg);
        } catch (Exception ignored) { }
        upstream = out;
        upstreamNet = net;
        upstreamAt = now;
        return out;
    }

    /** Strict Private DNS sends lookups over TLS straight to the provider, skipping our filter. */
    private void fixPrivateDnsIfStrict() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null && lp.getPrivateDnsServerName() != null) {
                if (Adblock.privateDnsAuto(this)) {
                    changedPrivateDns = true;
                } else {
                    Adblock.toast(this, "Private DNS is set to a specific provider, which bypasses the ad filter. "
                            + "Set Settings › Network › Private DNS to Automatic.");
                }
            }
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ attribution
    private FocusGate focusGate;   // created once the service has its context (see establish)

    /** The app (uid) that sent this DNS query, or -1 when Android can't tell. */
    private int queryUid(DnsPacket.Query q) {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            return cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                    new InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                    new InetSocketAddress(InetAddress.getByAddress(q.dstIp), 53));
        } catch (Exception e) {
            return -1;
        }
    }

    private void onBlocked(DnsPacket.Query q, DnsFilterEngine.BlockedEntry entry) {
        if (entry.app != null) return;
        final byte[] src = q.srcIp.clone(), dst = q.dstIp.clone();
        final int sport = q.srcPort;
        pool.execute(new Runnable() { @Override public void run() {
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                int uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                        new InetSocketAddress(InetAddress.getByAddress(src), sport),
                        new InetSocketAddress(InetAddress.getByAddress(dst), 53));
                if (uid >= 10000) entry.app = appName(uid);
            } catch (Exception ignored) { }
        } });
    }

    private String appName(int uid) {
        String cached = appNames.get(uid);
        if (cached != null) return cached;
        String name = "uid " + uid;
        PackageManager pm = getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs != null && pkgs.length > 0) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkgs[0], 0);
                name = pm.getApplicationLabel(ai).toString();
            } catch (PackageManager.NameNotFoundException e) {
                name = pkgs[0];
            }
        }
        appNames.put(uid, name);
        return name;
    }

    // ------------------------------------------------------------------ pause / reload / stop
    public boolean isPaused() { return SystemClock.elapsedRealtime() < pausedUntil; }

    public void pause() {
        pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS;
        DnsFilterEngine e = engine;
        if (e != null) e.paused = true;
        main.removeCallbacks(resume);
        main.postDelayed(resume, PAUSE_MS);
        main.post(tick);
    }

    public void resumeNow() {
        main.removeCallbacks(resume);
        resume.run();
    }

    private final Runnable resume = new Runnable() { @Override public void run() {
        pausedUntil = 0;
        DnsFilterEngine e = engine;
        if (e != null) e.paused = false;
        main.post(tick);
    } };

    public void reloadListsAsync() {
        new Thread(new Runnable() { @Override public void run() {
            try {
                Blocklist b = Adblock.loadBlocklist(AdblockVpnService.this);
                blocklist = b;
                DnsFilterEngine e = engine;
                if (e != null) e.setBlocklist(b);
            } catch (IOException e) {
                Log.e(TAG, "reload failed", e);
            }
        } }, "adblock-reload").start();
    }

    public void restartTunnel() {
        if (!running) return;
        new Thread(new Runnable() { @Override public void run() {
            try { establish(); } catch (IOException e) { Log.e(TAG, "restart failed", e); }
        } }, "adblock-restart").start();
    }

    /** Turns filtering off and tears everything down. */
    public void shutdown() {
        if (!running && tun == null) { stopSelf(); return; }
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(resume);
        pausedUntil = 0;
        ParcelFileDescriptor t = tun;
        tun = null;
        tunFd = null;
        closeQuietly(t);
        getSystemService(NotificationManager.class).cancel(NOTIF_ID);
        if (changedPrivateDns) {
            changedPrivateDns = false;
            new Thread(new Runnable() { @Override public void run() { Adblock.privateDnsRestore(AdblockVpnService.this); } }).start();
        }
        Adblock.refreshTile(this);
        stopSelf();
    }

    @Override
    public void onRevoke() {   // user switched VPN off in Settings or another VPN took over
        Adblock.prefs(this).edit().putBoolean("enabled", false).apply();
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        if (running || tun != null) shutdown();
        pool.shutdownNow();
        if (instance == this) instance = null;
        Adblock.refreshTile(this);
        super.onDestroy();
    }

    private static void closeQuietly(ParcelFileDescriptor p) {
        if (p == null) return;
        try { p.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ notification
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            postNotification();
            Adblock.refreshTile(AdblockVpnService.this);
            main.postDelayed(this, 15_000);
        }
    };

    private void postNotification() {
        DnsFilterEngine e = engine;
        long blocked = e == null ? 0 : e.blockedQueries.get();
        boolean paused = isPaused();
        String text = paused ? "Paused · resumes automatically in 10 min or less"
                : String.format(Locale.US, "%,d ads & trackers blocked", blocked);
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(paused ? "Adblock paused" : "Adblock on")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause 10 min",
                        AdblockActionReceiver.pending(this, paused ? AdblockActionReceiver.RESUME : AdblockActionReceiver.PAUSE)).build())
                .addAction(new Notification.Action.Builder(null, "Turn off",
                        AdblockActionReceiver.pending(this, AdblockActionReceiver.STOP)).build());
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, nb.build());
        } catch (SecurityException ignored) { }   // notifications not allowed: filtering still works
    }
}
