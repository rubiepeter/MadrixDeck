package au.com.peterrubie.madrixdeck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls MADRIX over Remote HTTP: Deck A's current storage and place every second, and for the
 * places on screen whether they hold content, their descriptions and thumbnails.
 * Pure Java: the actual HTTP and image decoding are supplied by the Android side.
 */
public final class HttpStatus {

    public interface Fetcher {
        String text(String command) throws Exception;

        byte[] bytes(String command) throws Exception;
    }

    public interface Listener {
        /** storage and place are 0-based, either may be null when unknown. */
        void onDeckState(Integer storage, Integer place);

        /** Names, empty/filled, thumbnails or connection state changed. */
        void onStatusChanged();
    }

    /** Turns thumbnail bytes into whatever the UI draws (a Bitmap on Android). */
    public interface ThumbDecoder {
        Object decode(byte[] data);
    }

    public interface PlaceSource {
        /** 0-based MADRIX place indexes currently shown on buttons. */
        List<Integer> visiblePlaces();
    }

    private static final long POLL_MS = 1000;
    private static final long DETAIL_MS = 15000;
    private static final long THUMB_MS = 60000;

    private final Fetcher fetcher;
    private final Listener listener;
    private final ThumbDecoder decoder;
    private final PlaceSource places;

    public volatile boolean enabled = true;
    public volatile boolean thumbnails = true;

    private volatile boolean running;
    private Thread thread;

    private volatile boolean ok;
    private volatile boolean everOk;
    private volatile String lastError = "";

    private Integer storage; // 1-based
    private List<Integer> lastVisible = Collections.emptyList();
    private long lastDetail;
    private long lastThumbs;
    private volatile boolean refreshRequested;

    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> filled = new ConcurrentHashMap<>();
    private final Map<Integer, Object> thumbs = new ConcurrentHashMap<>();

    public HttpStatus(Fetcher fetcher, Listener listener, ThumbDecoder decoder, PlaceSource places) {
        this.fetcher = fetcher;
        this.listener = listener;
        this.decoder = decoder;
        this.places = places;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "madrix-http");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Re-read names, empty/filled and thumbnails on the next poll (e.g. after changing the first place). */
    public void refresh() {
        refreshRequested = true;
    }

    public boolean ok() {
        return ok;
    }

    public boolean everOk() {
        return everOk;
    }

    public String lastError() {
        return lastError;
    }

    /** MADRIX description for a 0-based place, or null. */
    public String name(int place) {
        return names.get(place);
    }

    /** True/false when known, null when not yet read. */
    public Boolean filled(int place) {
        return filled.get(place);
    }

    public Object thumb(int place) {
        return thumbs.get(place);
    }

    public void clearCache() {
        names.clear();
        filled.clear();
        thumbs.clear();
    }

    private void loop() {
        while (running) {
            if (enabled) {
                pollOnce(System.currentTimeMillis());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** One poll cycle. Public for tests. */
    public void pollOnce(long now) {
        boolean wasOk = ok;
        try {
            String sReply = fetcher.text(HttpCommands.getStorageDeck("A"));
            String pReply = fetcher.text(HttpCommands.getPlaceDeck("A"));
            Integer s = HttpCommands.parseStorage(sReply);
            Integer p = HttpCommands.parsePlace(pReply);
            ok = true;
            everOk = true;
            lastError = "";
            listener.onDeckState(s == null ? null : s - 1, p == null ? null : p - 1);

            List<Integer> visible = new ArrayList<>(places.visiblePlaces());
            boolean storageChanged = s != null && !s.equals(storage);
            boolean visibleChanged = !visible.equals(lastVisible);
            if (storageChanged) {
                clearCache();
            }
            storage = s;
            int st = s == null ? 1 : s;
            boolean detailDue = storageChanged || visibleChanged || refreshRequested || now - lastDetail >= DETAIL_MS;
            boolean thumbsDue = thumbnails && (storageChanged || visibleChanged || refreshRequested || now - lastThumbs >= THUMB_MS);
            refreshRequested = false;
            lastVisible = visible;
            if (detailDue) {
                lastDetail = now;
                readFilled(st, visible);
                listener.onStatusChanged();
                readNames(st, visible);
                listener.onStatusChanged();
            }
            if (thumbsDue) {
                lastThumbs = now;
                readThumbs(st, visible);
            }
        } catch (Exception e) {
            ok = false;
            lastError = String.valueOf(e.getMessage());
        }
        if (wasOk != ok) {
            listener.onStatusChanged();
        }
    }

    private void readFilled(int st, List<Integer> visible) throws Exception {
        List<Boolean> all = Collections.emptyList();
        try {
            all = HttpCommands.parseFullStorage(fetcher.text(HttpCommands.fullStateStorage(st)));
        } catch (Exception ignored) {
            // fall back to one request per place
        }
        int maxNeeded = 0;
        for (int v : visible) {
            maxNeeded = Math.max(maxNeeded, v + 1);
        }
        if (all.size() >= maxNeeded && all.size() > 1) {
            for (int v : visible) {
                filled.put(v, all.get(v));
            }
            return;
        }
        for (int v : visible) {
            Boolean f = HttpCommands.parseFull(fetcher.text(HttpCommands.fullState(st, v + 1)));
            if (f != null) {
                filled.put(v, f);
            }
        }
    }

    private void readNames(int st, List<Integer> visible) throws Exception {
        for (int v : visible) {
            if (Boolean.FALSE.equals(filled.get(v))) {
                names.remove(v);
                continue;
            }
            String n = fetcher.text(HttpCommands.description(st, v + 1));
            n = n == null ? "" : n.trim();
            if (n.isEmpty()) {
                names.remove(v);
            } else {
                names.put(v, n);
            }
        }
    }

    private void readThumbs(int st, List<Integer> visible) {
        int done = 0;
        for (int v : visible) {
            if (!running && thread != null) {
                return;
            }
            if (Boolean.FALSE.equals(filled.get(v))) {
                thumbs.remove(v);
                continue;
            }
            try {
                byte[] data = fetcher.bytes(HttpCommands.thumbnail(st, v + 1));
                Object img = data == null || data.length == 0 ? null : decoder.decode(data);
                if (img != null) {
                    thumbs.put(v, img);
                } else {
                    thumbs.remove(v);
                }
            } catch (Exception e) {
                thumbs.remove(v);
            }
            if (++done % 8 == 0) {
                listener.onStatusChanged();
            }
        }
        listener.onStatusChanged();
    }
}
