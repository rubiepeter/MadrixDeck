package au.com.peterrubie.madrixdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live Main Output preview from MADRIX over CITP/MSEX.
 * UDP 4809 (multicast 239.224.0.180 and legacy 224.0.0.180): MADRIX's PINF/PLoc announcement
 * (gives its TCP port) and the StFr stream frames. TCP: we ask for the video sources (GVSr)
 * and keep requesting the stream (RqSt) every two seconds.
 */
final class CitpClient {

    interface Listener {
        void onFrame(Bitmap frame);

        void onCitpStatus();
    }

    private static final int REQUEST_EVERY_MS = 2000;
    private static final int STREAM_TIMEOUT_S = 5;
    private static final int STREAM_FPS = 10;
    private static final int STREAM_SIZE = 160;

    private final ConnectivityManager cm;
    private final WifiManager wm;
    private final OscClient wifiSource;
    private final Listener listener;

    volatile boolean enabled = true;
    volatile String host = "";
    volatile int manualPort = 0;

    private volatile boolean running;
    private volatile int discoveredPort = -1;
    private volatile boolean connected;
    private volatile String serverName = "";
    private volatile int sourceId = -1;
    private volatile int msexMinor = 1;
    private volatile long lastFrameAt;
    private volatile int frameCount;
    private volatile String frameInfo = "";
    private volatile boolean warnedFragments;
    /** Shape of the chosen source from MADRIX's source list (0 = unknown). */
    private volatile int sourceWidth;
    private volatile int sourceHeight;
    private volatile Bitmap lastFrame;
    /** "240x71" -> {frames received, frames used, last brightness %}. */
    private final java.util.Map<String, int[]> sizeStats = new java.util.LinkedHashMap<>();

    private volatile MulticastSocket udpSocket;
    private volatile Socket tcpSocket;
    private WifiManager.MulticastLock lock;
    private Thread udpThread;
    private Thread tcpThread;

    private final ArrayDeque<String> log = new ArrayDeque<>();

    // Announcing ourselves (PINF/PLoc) every 2 s
    private long lastAnnounce;
    private boolean announceLogged;

    // Diagnostics: frame timing and press-to-picture-change
    private final ArrayDeque<Long> frameTimes = new ArrayDeque<>();
    private int[] lastSig;
    private double baselineDiff = -1;
    private volatile long pressAt;
    private volatile int[] pressSig;
    private volatile boolean pressPending;
    private volatile String lastPressResult = "";

    CitpClient(Context ctx, OscClient wifiSource, Listener listener) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.wifiSource = wifiSource;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        try {
            lock = wm.createMulticastLock("madrixdeck-citp");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Exception e) {
            log("Multicast lock failed: " + e.getMessage());
        }
        udpThread = new Thread(this::udpLoop, "citp-udp");
        udpThread.setDaemon(true);
        udpThread.start();
        tcpThread = new Thread(this::tcpLoop, "citp-tcp");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    synchronized void stop() {
        running = false;
        closeQuietly(udpSocket);
        closeQuietly(tcpSocket);
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        if (udpThread != null) {
            udpThread.interrupt();
        }
        if (tcpThread != null) {
            tcpThread.interrupt();
        }
    }

    /** True while frames are arriving (one within the last 3 seconds). */
    boolean live() {
        return enabled && System.currentTimeMillis() - lastFrameAt < 3000;
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "on" : "off");
        if (enabled) {
            sb.append(", TCP ").append(manualPort > 0 ? manualPort + " (set)" : (discoveredPort > 0 ? discoveredPort + " (found)" : "not found yet"));
            sb.append(connected ? ", connected" : ", not connected");
            if (!serverName.isEmpty()) {
                sb.append(" to ").append(serverName);
            }
            sb.append(", source ").append(sourceId >= 0 ? String.valueOf(sourceId) : "?");
            sb.append(", frames ").append(frameCount);
            if (!frameInfo.isEmpty()) {
                sb.append(" (").append(frameInfo).append(")");
            }
            if (sourceWidth > 0) {
                sb.append(", requesting ").append(sourceWidth).append("x").append(sourceHeight);
            }
            sb.append("\n   Timing: ").append(rateText());
            if (!lastPressResult.isEmpty()) {
                sb.append("\n   Last place press to picture change: ").append(lastPressResult);
            }
            synchronized (sizeStats) {
                for (java.util.Map.Entry<String, int[]> e : sizeStats.entrySet()) {
                    int[] v = e.getValue();
                    sb.append("\n   ").append(e.getKey()).append(": received ").append(v[0])
                            .append(", used ").append(v[1]).append(", brightness ").append(v[2]).append("%");
                }
            }
        }
        return sb.toString();
    }

    /** Most recent frame that was used for the preview, for the Connection info screen. */
    Bitmap lastFrame() {
        return lastFrame;
    }

    String logText() {
        synchronized (log) {
            return String.join("\n", log);
        }
    }

    private void log(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (log) {
            if (!log.isEmpty() && log.peekLast().endsWith(line)) {
                return; // don't repeat the same line
            }
            log.addLast(stamp + "  " + line);
            while (log.size() > 25) {
                log.removeFirst();
            }
        }
        listener.onCitpStatus();
    }

    // ---------- UDP: discovery + frames ----------

    private void udpLoop() {
        while (running) {
            Network net = wifiSource.network();
            if (!enabled || net == null) {
                sleep(1000);
                continue;
            }
            MulticastSocket ms = null;
            try {
                ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.bind(new InetSocketAddress(Citp.PORT));
                NetworkInterface ni = wifiInterface(net);
                for (String g : new String[]{Citp.GROUP, Citp.LEGACY_GROUP}) {
                    ms.joinGroup(new InetSocketAddress(InetAddress.getByName(g), Citp.PORT), ni);
                }
                ms.setSoTimeout(1000);
                udpSocket = ms;
                log("Listening on UDP " + Citp.PORT + (ni != null ? " (" + ni.getName() + ")" : ""));
                byte[] buf = new byte[65536];
                if (ni != null) {
                    ms.setNetworkInterface(ni);
                }
                while (running && enabled && net.equals(wifiSource.network())) {
                    announce(ms);
                    checkPressTimeout();
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        ms.receive(p);
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    }
                    handleUdp(p);
                }
            } catch (Exception e) {
                if (running) {
                    log("UDP: " + e.getMessage());
                    sleep(3000);
                }
            } finally {
                closeQuietly(ms);
                udpSocket = null;
            }
        }
    }

    private NetworkInterface wifiInterface(Network net) throws Exception {
        LinkProperties lp = cm.getLinkProperties(net);
        String name = lp != null ? lp.getInterfaceName() : null;
        NetworkInterface ni = name != null ? NetworkInterface.getByName(name) : null;
        return ni != null ? ni : NetworkInterface.getByName("wlan0");
    }

    private void handleUdp(DatagramPacket p) {
        Citp.Message m = Citp.parse(p.getData(), p.getOffset(), p.getLength());
        if (m == null) {
            return;
        }
        String from = p.getAddress() != null ? p.getAddress().getHostAddress() : "";
        boolean fromMadrix = from.equals(host);
        if ("PINF".equals(m.layer)) {
            int port = Citp.listeningPort(m);
            if (fromMadrix && port > 0 && port != discoveredPort) {
                discoveredPort = port;
                String[] t = "PLoc".equals(m.type) ? Citp.plocText(m) : new String[]{"", "", ""};
                log("MADRIX announced CITP on TCP " + port + (t[1].isEmpty() ? "" : " (" + t[1] + ")"));
            }
            return;
        }
        if ("MSEX".equals(m.layer) && "StFr".equals(m.type) && fromMadrix) {
            if (m.partCount > 1) {
                if (!warnedFragments) {
                    warnedFragments = true;
                    log("Frames arrive in " + m.partCount + " parts, not supported yet");
                }
                return;
            }
            Citp.Frame f = Citp.stfr(m);
            if (f == null || (sourceId >= 0 && f.source != sourceId)) {
                return;
            }
            String key = f.format + " " + f.width + "x" + f.height;
            int[] stat;
            synchronized (sizeStats) {
                stat = sizeStats.get(key);
                if (stat == null) {
                    stat = new int[]{0, 0, -1};
                    sizeStats.put(key, stat);
                    log("Frames arriving: " + key);
                }
                stat[0]++;
            }
            // Use only frames shaped like the source; MADRIX pads or stretches other requests.
            if (sourceWidth > 0 && sourceHeight > 0 && f.width > 0 && f.height > 0) {
                double want = (double) sourceWidth / sourceHeight;
                double got = (double) f.width / f.height;
                if (Math.abs(got - want) / want > 0.15) {
                    return;
                }
            }
            Bitmap bmp = decode(f);
            if (bmp == null) {
                return;
            }
            stat[1]++;
            int[] sig = signature(bmp);
            stat[2] = sigBrightness(sig);
            frameTiming(sig);
            lastFrame = bmp;
            frameCount++;
            if (lastFrameAt == 0 || frameCount == 1) {
                log("First frame: " + f.format + " " + f.width + "x" + f.height + ", MSEX 1." + f.msexMinor);
            }
            frameInfo = f.format + " " + f.width + "x" + f.height;
            lastFrameAt = System.currentTimeMillis();
            listener.onFrame(bmp);
        }
    }

    /** Brightness (0-255) of a 16 x 6 grid of pixels: enough to spot a change of look. */
    private static int[] signature(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] sig = new int[16 * 6];
        if (w <= 0 || h <= 0) {
            return sig;
        }
        int i = 0;
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 16; x++) {
                int c = b.getPixel(Math.min(w - 1, x * w / 16 + w / 32), Math.min(h - 1, y * h / 6 + h / 12));
                sig[i++] = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            }
        }
        return sig;
    }

    private static int sigBrightness(int[] sig) {
        long sum = 0;
        for (int v : sig) {
            sum += v;
        }
        return sig.length == 0 ? 0 : (int) (sum * 100 / (255L * sig.length));
    }

    private static double sigDiff(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (double) sum / a.length;
    }

    /** Frame rate, gaps, and how long after a place press the picture changes. */
    private void frameTiming(int[] sig) {
        long now = System.currentTimeMillis();
        synchronized (frameTimes) {
            frameTimes.addLast(now);
            while (!frameTimes.isEmpty() && now - frameTimes.peekFirst() > 5000) {
                frameTimes.removeFirst();
            }
        }
        double diff = sigDiff(sig, lastSig);
        if (pressPending) {
            double change = sigDiff(sig, pressSig);
            double threshold = Math.max(12, (baselineDiff < 0 ? 0 : baselineDiff) * 3);
            // The new place has arrived once the picture differs clearly from before the press
            // and isn't black (MADRIX's stream lags and can go black while switching).
            if (pressSig != null && change > threshold && sigBrightness(sig) > 2) {
                pressPending = false;
                lastPressResult = (now - pressAt) + " ms";
                log("Live picture changed " + (now - pressAt) + " ms after the place press");
            }
        } else if (lastSig != null) {
            baselineDiff = baselineDiff < 0 ? diff : baselineDiff * 0.9 + diff * 0.1;
        }
        lastSig = sig;
    }

    private void checkPressTimeout() {
        if (pressPending && System.currentTimeMillis() - pressAt > 10000) {
            pressPending = false;
            lastPressResult = "no clear change";
            log("No clear picture change within 10 s of the place press");
        }
    }

    /** Called when a place button is pressed, to time how long the live picture takes to follow. */
    void markPress() {
        if (!live() || lastSig == null) {
            return;
        }
        pressSig = lastSig;
        pressAt = System.currentTimeMillis();
        pressPending = true;
    }

    /**
     * True just after a place press while the stream still shows the old place (or black):
     * the button keeps its normal thumbnail until the live picture has caught up (max 10 s).
     */
    boolean holding() {
        return pressPending && System.currentTimeMillis() - pressAt <= 10000;
    }

    /** Frames per second and longest gap over the last 5 seconds. */
    private String rateText() {
        synchronized (frameTimes) {
            if (frameTimes.size() < 2) {
                return "no recent frames";
            }
            long gap = 0;
            Long prev = null;
            for (Long t : frameTimes) {
                if (prev != null) {
                    gap = Math.max(gap, t - prev);
                }
                prev = t;
            }
            long span = Math.max(1, frameTimes.peekLast() - frameTimes.peekFirst());
            double fps = (frameTimes.size() - 1) * 1000.0 / span;
            return String.format(Locale.US, "%.1f fps, longest gap %d ms (last 5 s)", fps, gap);
        }
    }

    /** Tells MADRIX (and other CITP peers) that we're here, every 2 seconds. */
    private void announce(MulticastSocket ms) {
        long now = System.currentTimeMillis();
        if (now - lastAnnounce < 2000) {
            return;
        }
        lastAnnounce = now;
        try {
            byte[] p = Citp.ploc(0, "LightingConsole", "MADRIX Deck", "Running");
            for (String g : new String[]{Citp.GROUP, Citp.LEGACY_GROUP}) {
                ms.send(new DatagramPacket(p, p.length, InetAddress.getByName(g), Citp.PORT));
            }
            if (!announceLogged) {
                announceLogged = true;
                log("Announcing as MADRIX Deck every 2 s");
            }
        } catch (Exception e) {
            log("Announce failed: " + e.getMessage());
        }
    }

    private static Bitmap decode(Citp.Frame f) {
        if ("RGB8".equals(f.format)) {
            int[] px = Citp.rgb8ToArgb(f);
            return px == null ? null : Bitmap.createBitmap(px, f.width, f.height, Bitmap.Config.ARGB_8888);
        }
        if (f.data.length == 0) {
            return null;
        }
        return BitmapFactory.decodeByteArray(f.data, 0, f.data.length); // JPEG / PNG
    }

    // ---------- TCP: sources + stream requests ----------

    private void tcpLoop() {
        while (running) {
            Network net = wifiSource.network();
            int port = manualPort > 0 ? manualPort : discoveredPort;
            if (!enabled || net == null || host == null || host.isEmpty() || port <= 0) {
                if (enabled && net != null && port <= 0) {
                    log("Waiting for MADRIX to announce CITP (Enable Server in MADRIX)");
                }
                sleep(1000);
                continue;
            }
            Socket s = null;
            try {
                s = net.getSocketFactory().createSocket();
                s.connect(new InetSocketAddress(host, port), 3000);
                tcpSocket = s;
                connected = true;
                sourceId = -1;
                log("Connected to " + host + ":" + port);
                OutputStream out = s.getOutputStream();
                out.write(Citp.pnam("MADRIX Deck"));
                out.write(Citp.gvsr(msexMinor));
                out.flush();
                final Socket rs = s;
                final boolean[] readerAlive = {true};
                Thread reader = new Thread(() -> {
                    readLoop(rs);
                    readerAlive[0] = false;
                }, "citp-read");
                reader.setDaemon(true);
                reader.start();

                long started = System.currentTimeMillis();
                long lastRequest = 0;
                boolean warnedNoFrames = false;
                boolean usingGuess = false;
                while (running && enabled && readerAlive[0] && !s.isClosed()) {
                    long now = System.currentTimeMillis();
                    if (now - lastRequest >= REQUEST_EVERY_MS) {
                        int src = sourceId;
                        if (src < 0 && now - started > 2500) {
                            src = 1; // no source list from MADRIX: try the first source id
                            if (!usingGuess) {
                                usingGuess = true;
                                log("No source list, requesting source 1");
                            }
                        }
                        if (src >= 0) {
                            int w = sourceWidth > 0 ? sourceWidth : STREAM_SIZE;
                            int h = sourceHeight > 0 ? sourceHeight : STREAM_SIZE;
                            out.write(Citp.rqst(msexMinor, src, "JPEG", w, h, STREAM_FPS, STREAM_TIMEOUT_S));
                            out.flush();
                            lastRequest = now;
                        }
                    }
                    if (!warnedNoFrames && lastRequest > 0 && now - started > 8000 && now - lastFrameAt > 8000) {
                        warnedNoFrames = true;
                        log("Stream requested but no frames arriving on UDP " + Citp.PORT);
                    }
                    sleep(200);
                }
            } catch (Exception e) {
                if (running) {
                    log("TCP " + host + ":" + port + ": " + e.getMessage());
                }
            } finally {
                connected = false;
                closeQuietly(s);
                tcpSocket = null;
                listener.onCitpStatus();
            }
            sleep(3000);
        }
    }

    private void readLoop(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] header = new byte[Citp.HEADER];
            while (running && !s.isClosed()) {
                in.readFully(header);
                int size = (header[8] & 0xFF) | (header[9] & 0xFF) << 8 | (header[10] & 0xFF) << 16 | (header[11] & 0xFF) << 24;
                if (size < Citp.HEADER || size > 1 << 22) {
                    log("Unexpected data from MADRIX, reconnecting");
                    return;
                }
                byte[] msg = new byte[size];
                System.arraycopy(header, 0, msg, 0, Citp.HEADER);
                in.readFully(msg, Citp.HEADER, size - Citp.HEADER);
                handleTcp(Citp.parse(msg, 0, size));
            }
        } catch (Exception e) {
            if (running && !s.isClosed()) {
                log("Connection closed: " + e.getMessage());
            }
        } finally {
            closeQuietly(s);
        }
    }

    private void handleTcp(Citp.Message m) {
        if (m == null || !"MSEX".equals(m.layer)) {
            return;
        }
        switch (m.type) {
            case "SInf":
                serverName = Citp.sinfName(m);
                log("Server: " + serverName + " (MSEX 1." + m.msexMinor + ")");
                break;
            case "VSrc": {
                List<Citp.Source> list = Citp.vsrc(m);
                List<String> names = new ArrayList<>();
                Citp.Source pick = null;
                for (Citp.Source s : list) {
                    names.add(s.id + " " + s.name + " " + s.width + "x" + s.height);
                    boolean isMain = s.name.toLowerCase(Locale.US).contains("main");
                    boolean pickIsMain = pick != null && pick.name.toLowerCase(Locale.US).contains("main");
                    if (pick == null || (isMain && !pickIsMain)) {
                        pick = s; // prefer a source named "Main...", otherwise the first
                    }
                }
                log("Sources: " + (names.isEmpty() ? "none" : String.join(", ", names)));
                if (pick != null) {
                    sourceId = pick.id;
                    sourceWidth = pick.width;
                    sourceHeight = pick.height;
                }
                break;
            }
            case "Nack":
                log("MADRIX declined a request (MSEX 1." + msexMinor + ")");
                if (msexMinor > 0) {
                    msexMinor = 0;
                    log("Retrying with MSEX 1.0");
                }
                break;
            default:
                break;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // checked by loop conditions
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }
}
