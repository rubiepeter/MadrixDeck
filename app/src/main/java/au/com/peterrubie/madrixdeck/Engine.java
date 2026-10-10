package au.com.peterrubie.madrixdeck;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs button actions, tracks MADRIX state (active place, speed, global colours) from what
 * we send and from OSC feedback, and performs colour crossfades in software.
 * Pure Java: no Android classes, so it can be tested on a desktop JVM.
 */
public final class Engine {

    public interface Sender {
        /** Returns false when the message could not be sent (no Wi-Fi, bad address). */
        boolean send(OscMessage m);
    }

    public interface Listener {
        void onStateChanged();

        /** page = absolute Companion page number, or delta != 0 for next/previous. */
        void onPageRequest(int page, int delta);
    }

    public static final int DECK_A = 0;
    public static final int DECK_B = 1;
    private static final String[] DECK_NAMES = {"A", "B"};

    private static final Pattern DECK = Pattern.compile("^/Deck([AB])/(Places/Index|Storages/Index|SpeedMaster)$");
    private static final Pattern COLOUR = Pattern.compile("^/ColorKit/GlobalColors/(\\d+)/(Rgb|Red|Green|Blue)$");
    private static final int FADE_FRAME_MS = 40;

    private final Sender sender;
    private final Listener listener;
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private final ExecutorService fades = Executors.newCachedThreadPool();

    // Settings
    public volatile double stepPercent = 10;
    public volatile double defaultSpeed = 1.0;
    /** "A", "B" or "AB". */
    public volatile String speedTarget = "A";
    public volatile int fadeMs = 0;

    // State
    private final Integer[] activePlace = new Integer[2];
    private final Integer[] activeStorage = new Integer[2];
    private final Double[] speed = new Double[2];
    /** Colour currently output per global colour bank (moves during a fade). */
    private final Map<Integer, int[]> colourNow = new ConcurrentHashMap<>();
    /** Colour last requested per bank (the fade's destination), used for highlighting. */
    private final Map<Integer, int[]> colourTarget = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> fadeGen = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> fading = new ConcurrentHashMap<>();
    private volatile boolean feedbackSeen = false;

    public Engine(Sender sender, Listener listener) {
        this.sender = sender;
        this.listener = listener;
    }

    public void shutdown() {
        actions.shutdownNow();
        fades.shutdownNow();
    }

    public boolean feedbackSeen() {
        return feedbackSeen;
    }

    // ---------- pressing buttons ----------

    public void press(DeckLayout.Btn b) {
        actions.execute(() -> run(b));
    }

    private void run(DeckLayout.Btn b) {
        switch (b.kind) {
            case DeckLayout.KIND_SPEED_DOWN: changeSpeed(-1); return;
            case DeckLayout.KIND_SPEED_UP: changeSpeed(1); return;
            case DeckLayout.KIND_SPEED_RESET: changeSpeed(0); return;
            case DeckLayout.KIND_FADE:
                fadeMs = b.fadeMs;
                notifyChanged();
                return;
            default:
                break;
        }
        for (DeckLayout.Act a : b.actions) {
            switch (a.type) {
                case DeckLayout.Act.WAIT:
                    if (a.ms > 0) {
                        try {
                            Thread.sleep(a.ms);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    break;
                case DeckLayout.Act.PAGE:
                    listener.onPageRequest(a.page, 0);
                    break;
                case DeckLayout.Act.PAGE_REL:
                    listener.onPageRequest(0, a.delta);
                    break;
                case DeckLayout.Act.OSC:
                    sendOsc(a.path, a.args);
                    break;
                default:
                    break; // unsupported actions are skipped
            }
        }
    }

    private void sendOsc(String path, List<Object> args) {
        Matcher cm = COLOUR.matcher(path);
        if (cm.matches() && "Rgb".equals(cm.group(2)) && args.size() >= 3) {
            int bank = Integer.parseInt(cm.group(1));
            int[] to = {clampByte(args.get(0)), clampByte(args.get(1)), clampByte(args.get(2))};
            colourTarget.put(bank, to.clone());
            notifyChanged();
            int[] from = colourNow.get(bank);
            int gen = gen(bank).incrementAndGet();
            int ms = fadeMs;
            if (ms > 0 && from != null) {
                fading.put(bank, true);
                fades.execute(() -> runFade(bank, path, from.clone(), to, ms, gen));
            } else {
                fading.put(bank, false);
                sender.send(OscMessage.of(path, to[0], to[1], to[2]));
                colourNow.put(bank, to);
            }
            return;
        }
        sender.send(new OscMessage(path, args));
        // Track the selection even if the send failed, so the highlight always shows
        // what was last pressed (the top bar shows when sending isn't working).
        applyState(path, args, false);
    }

    private void runFade(int bank, String path, int[] from, int[] to, int ms, int gen) {
        int frames = Math.max(1, ms / FADE_FRAME_MS);
        long start = System.nanoTime();
        for (int i = 1; i <= frames; i++) {
            if (gen(bank).get() != gen) {
                return; // a newer colour press took over
            }
            double t = (double) i / frames;
            int[] c = {
                    (int) Math.round(from[0] + (to[0] - from[0]) * t),
                    (int) Math.round(from[1] + (to[1] - from[1]) * t),
                    (int) Math.round(from[2] + (to[2] - from[2]) * t)};
            if (sender.send(OscMessage.of(path, c[0], c[1], c[2]))) {
                colourNow.put(bank, c);
            }
            long due = start + (long) i * ms * 1_000_000L / frames;
            long wait = (due - System.nanoTime()) / 1_000_000L;
            if (wait > 0 && i < frames) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (gen(bank).get() == gen) {
            fading.put(bank, false);
        }
    }

    // ---------- live colour adjustment from the editor ----------

    private final java.util.concurrent.atomic.AtomicReference<Object[]> pendingLive =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean liveScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Sends a colour immediately (no crossfade) while the user drags the RGB sliders.
     * Rapid calls are coalesced so only the latest value is sent.
     */
    public void sendLiveColour(String path, int r, int g, int b) {
        Matcher cm = COLOUR.matcher(path);
        if (!cm.matches() || !"Rgb".equals(cm.group(2))) {
            return;
        }
        int bank = Integer.parseInt(cm.group(1));
        gen(bank).incrementAndGet(); // stop any running fade on this bank
        fading.put(bank, false);
        pendingLive.set(new Object[]{path, bank, new int[]{clampByte(r), clampByte(g), clampByte(b)}});
        if (liveScheduled.compareAndSet(false, true)) {
            actions.execute(this::flushLive);
        }
    }

    private void flushLive() {
        liveScheduled.set(false);
        Object[] p = pendingLive.getAndSet(null);
        if (p == null) {
            return;
        }
        int bank = (Integer) p[1];
        int[] c = (int[]) p[2];
        if (sender.send(OscMessage.of((String) p[0], c[0], c[1], c[2]))) {
            colourNow.put(bank, c);
            colourTarget.put(bank, c.clone());
            notifyChanged();
        }
    }

    /** Colour MADRIX is currently showing for a global colour bank, or null if unknown. */
    public int[] currentColour(String path) {
        Matcher cm = COLOUR.matcher(path);
        if (!cm.matches()) {
            return null;
        }
        int[] c = colourNow.get(Integer.parseInt(cm.group(1)));
        return c == null ? null : c.clone();
    }

    private AtomicInteger gen(int bank) {
        return fadeGen.computeIfAbsent(bank, k -> new AtomicInteger());
    }

    private void changeSpeed(int direction) {
        for (int d : targetDecks()) {
            double cur = speed[d] != null ? speed[d] : defaultSpeed;
            double next = direction == 0 ? defaultSpeed : cur + direction * stepPercent / 100.0;
            next = Math.max(-20.0, Math.min(20.0, Math.round(next * 10000.0) / 10000.0));
            sender.send(OscMessage.of("/Deck" + DECK_NAMES[d] + "/SpeedMaster", (float) next));
            speed[d] = next;
        }
        notifyChanged();
    }

    private int[] targetDecks() {
        String t = speedTarget;
        if ("B".equals(t)) {
            return new int[]{DECK_B};
        }
        if ("AB".equals(t)) {
            return new int[]{DECK_A, DECK_B};
        }
        return new int[]{DECK_A};
    }

    // ---------- feedback from MADRIX ----------

    public void onIncoming(OscMessage m) {
        feedbackSeen = true;
        Matcher cm = COLOUR.matcher(m.address);
        if (cm.matches()) {
            int bank = Integer.parseInt(cm.group(1));
            if (Boolean.TRUE.equals(fading.get(bank))) {
                return; // ignore echoes of our own fade steps
            }
            int[] cur = colourNow.containsKey(bank) ? colourNow.get(bank).clone() : null;
            if ("Rgb".equals(cm.group(2)) && m.args.size() >= 3) {
                cur = new int[]{clampByte(m.args.get(0)), clampByte(m.args.get(1)), clampByte(m.args.get(2))};
            } else if (cur != null && m.number(0) != null) {
                int idx = "Red".equals(cm.group(2)) ? 0 : "Green".equals(cm.group(2)) ? 1 : 2;
                cur[idx] = clampByte(m.args.get(0));
            }
            if (cur != null) {
                colourNow.put(bank, cur);
                colourTarget.put(bank, cur.clone());
                notifyChanged();
            }
            return;
        }
        if (applyState(m.address, m.args, true)) {
            notifyChanged();
        }
    }

    /** Updates tracked deck state. Returns true when something changed. */
    private boolean applyState(String path, List<Object> args, boolean fromFeedback) {
        Matcher dm = DECK.matcher(path);
        if (!dm.matches() || args.isEmpty() || !(args.get(0) instanceof Number)) {
            return false;
        }
        int d = "A".equals(dm.group(1)) ? DECK_A : DECK_B;
        Number n = (Number) args.get(0);
        switch (dm.group(2)) {
            case "Places/Index": {
                Integer v = n.intValue();
                boolean changed = !v.equals(activePlace[d]);
                activePlace[d] = v;
                if (changed && !fromFeedback) {
                    notifyChanged();
                }
                return changed;
            }
            case "Storages/Index": {
                Integer v = n.intValue();
                boolean changed = !v.equals(activeStorage[d]);
                activeStorage[d] = v;
                if (changed && !fromFeedback) {
                    notifyChanged();
                }
                return changed;
            }
            default: {
                double v = Math.round(n.doubleValue() * 10000.0) / 10000.0;
                boolean changed = speed[d] == null || speed[d] != v;
                speed[d] = v;
                if (changed && !fromFeedback) {
                    notifyChanged();
                }
                return changed;
            }
        }
    }

    private void notifyChanged() {
        listener.onStateChanged();
    }

    // ---------- queries for the UI ----------

    /** True when this button represents the current place, colour or fade mode. */
    public boolean isActive(DeckLayout.Btn b) {
        if (DeckLayout.KIND_FADE.equals(b.kind)) {
            return b.fadeMs == fadeMs;
        }
        if (DeckLayout.KIND_SPEED_RESET.equals(b.kind)) {
            for (int d : targetDecks()) {
                if (speed[d] != null && Math.abs(speed[d] - defaultSpeed) > 0.00005) {
                    return true;
                }
            }
            return false;
        }
        if (!DeckLayout.KIND_ACTION.equals(b.kind)) {
            return false;
        }
        Integer placeDeck = null;
        Integer place = null;
        Integer storage = null;
        int[] rgb = null;
        Integer bank = null;
        for (DeckLayout.Act a : b.actions) {
            if (!DeckLayout.Act.OSC.equals(a.type) || a.args.isEmpty() || !(a.args.get(0) instanceof Number)) {
                continue;
            }
            Matcher dm = DECK.matcher(a.path);
            if (dm.matches()) {
                int d = "A".equals(dm.group(1)) ? DECK_A : DECK_B;
                if ("Places/Index".equals(dm.group(2))) {
                    placeDeck = d;
                    place = ((Number) a.args.get(0)).intValue();
                } else if ("Storages/Index".equals(dm.group(2))) {
                    storage = ((Number) a.args.get(0)).intValue();
                }
                continue;
            }
            Matcher cm = COLOUR.matcher(a.path);
            if (cm.matches() && "Rgb".equals(cm.group(2)) && a.args.size() >= 3) {
                bank = Integer.parseInt(cm.group(1));
                rgb = new int[]{clampByte(a.args.get(0)), clampByte(a.args.get(1)), clampByte(a.args.get(2))};
            }
        }
        if (placeDeck != null) {
            if (!place.equals(activePlace[placeDeck])) {
                return false;
            }
            return storage == null || activeStorage[placeDeck] == null || storage.equals(activeStorage[placeDeck]);
        }
        if (bank != null) {
            int[] t = colourTarget.get(bank);
            return t != null && t[0] == rgb[0] && t[1] == rgb[1] && t[2] == rgb[2];
        }
        return false;
    }

    /** Selection to remember between launches: places, storages, speeds and colour targets. */
    public String saveState() {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < 2; d++) {
            if (activePlace[d] != null) {
                sb.append("place").append(d).append('=').append(activePlace[d]).append(';');
            }
            if (activeStorage[d] != null) {
                sb.append("storage").append(d).append('=').append(activeStorage[d]).append(';');
            }
            if (speed[d] != null) {
                sb.append("speed").append(d).append('=').append(speed[d]).append(';');
            }
        }
        for (Map.Entry<Integer, int[]> e : colourTarget.entrySet()) {
            int[] c = e.getValue();
            sb.append("colour").append(e.getKey()).append('=').append(c[0]).append(',').append(c[1]).append(',')
                    .append(c[2]).append(';');
        }
        return sb.toString();
    }

    public void restoreState(String state) {
        if (state == null) {
            return;
        }
        for (String part : state.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String val = part.substring(eq + 1);
            try {
                if (key.startsWith("place")) {
                    activePlace[Integer.parseInt(key.substring(5))] = Integer.parseInt(val);
                } else if (key.startsWith("storage")) {
                    activeStorage[Integer.parseInt(key.substring(7))] = Integer.parseInt(val);
                } else if (key.startsWith("speed")) {
                    speed[Integer.parseInt(key.substring(5))] = Double.parseDouble(val);
                } else if (key.startsWith("colour")) {
                    String[] c = val.split(",");
                    // Highlight only: the first colour press after launch still snaps, since the
                    // rig may have changed while the app was closed.
                    colourTarget.put(Integer.parseInt(key.substring(6)),
                            new int[]{Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])});
                }
            } catch (RuntimeException ignored) {
                // skip a damaged entry
            }
        }
    }

    /** Speed of the first target deck as a percentage of normal (1.0 = 100%). */
    public String speedText() {
        int d = targetDecks()[0];
        double v = speed[d] != null ? speed[d] : defaultSpeed;
        return Math.round(v * 100) + "%";
    }

    public String stepText() {
        double s = stepPercent;
        return (s == Math.rint(s) ? String.valueOf((long) s) : String.format(Locale.US, "%.1f", s)) + "%";
    }

    public String speedTargetName() {
        String t = speedTarget;
        return "AB".equals(t) ? "Deck A and Deck B" : "Deck " + ("B".equals(t) ? "B" : "A");
    }

    private static int clampByte(Object o) {
        int v = o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : 0;
        return Math.max(0, Math.min(255, v));
    }
}
