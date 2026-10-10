package au.com.peterrubie.madrixdeck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MADRIX 5 Remote HTTP: maps the app's OSC messages to HTTP remote commands and parses replies.
 * Commands are sent as http://IP:PORT/RemoteCommands/?Command. Storage and place numbers are 1-based.
 */
public final class HttpCommands {

    private static final Pattern DECK = Pattern.compile("^/Deck([AB])/(Places/Index|Storages/Index|SpeedMaster)$");
    private static final Pattern COLOUR = Pattern.compile("^/ColorKit/GlobalColors/(\\d+)/Rgb$");
    private static final Pattern STORAGE_PLACE = Pattern.compile("S\\s*(\\d+)\\s*P\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIRST_INT = Pattern.compile("-?\\d+");

    private HttpCommands() {
    }

    /** HTTP command for an OSC message, or null when there is no HTTP equivalent (sent by OSC instead). */
    public static String fromOsc(OscMessage m) {
        Matcher dm = DECK.matcher(m.address);
        if (dm.matches() && !m.args.isEmpty() && m.args.get(0) instanceof Number) {
            String deck = dm.group(1);
            Number n = (Number) m.args.get(0);
            switch (dm.group(2)) {
                case "Places/Index": return "SetStorageDeck" + deck + "=P" + (n.intValue() + 1);
                case "Storages/Index": return "SetStorageDeck" + deck + "=S" + (n.intValue() + 1);
                default: return "SetStorageSpeedDeck" + deck + "=" + num(n.doubleValue());
            }
        }
        Matcher cm = COLOUR.matcher(m.address);
        if (cm.matches() && m.args.size() >= 3) {
            return "SetGlobalColor=" + cm.group(1) + "_R" + byteArg(m.args.get(0)) + "G" + byteArg(m.args.get(1))
                    + "B" + byteArg(m.args.get(2));
        }
        if ("/Fade/Time".equals(m.address) && !m.args.isEmpty() && m.args.get(0) instanceof Number) {
            return "SetFadeTime=" + num(((Number) m.args.get(0)).doubleValue());
        }
        return null;
    }

    public static String getStorageDeck(String deck) {
        return "GetStorageDeck" + deck;
    }

    public static String getPlaceDeck(String deck) {
        return "GetStoragePlaceDeck" + deck;
    }

    /** storage and place are 1-based. */
    public static String fullState(int storage, int place) {
        return "GetStoragePlaceFullState=S" + storage + "P" + place;
    }

    public static String fullStateStorage(int storage) {
        return "GetStoragePlaceFullState=S" + storage;
    }

    public static String description(int storage, int place) {
        return "GetStoragePlaceDescription=S" + storage + "P" + place;
    }

    public static String thumbnail(int storage, int place) {
        return "GetStoragePlaceThumb.png=S" + storage + "P" + place;
    }

    /** First integer in a reply such as "3", "S3", "Storage: 3". Null when there is none. */
    public static Integer parseInt(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = FIRST_INT.matcher(body);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Place number (1-based) from a reply such as "5" or "S3P5". */
    public static Integer parsePlace(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = STORAGE_PLACE.matcher(body);
        if (m.find()) {
            return Integer.parseInt(m.group(2));
        }
        return parseInt(body);
    }

    /** Storage number (1-based) from a reply such as "3" or "S3P5". */
    public static Integer parseStorage(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = STORAGE_PLACE.matcher(body);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return parseInt(body);
    }

    /** "1"/"0" (or true/false) for one place. Null when the reply is not recognisable. */
    public static Boolean parseFull(String body) {
        if (body == null) {
            return null;
        }
        String t = body.trim().toLowerCase(Locale.US);
        if (t.equals("1") || t.equals("true")) {
            return Boolean.TRUE;
        }
        if (t.equals("0") || t.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Whole-storage full state: a run of 0/1 values, optionally separated by commas, semicolons,
     * spaces or new lines. Returns an empty list when the reply isn't in that shape.
     */
    public static List<Boolean> parseFullStorage(String body) {
        List<Boolean> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        String t = body.trim();
        if (t.isEmpty() || !t.matches("[01,;\\s]+")) {
            return out;
        }
        for (char c : t.toCharArray()) {
            if (c == '0') {
                out.add(Boolean.FALSE);
            } else if (c == '1') {
                out.add(Boolean.TRUE);
            }
        }
        return out;
    }

    static String num(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.US, "%.4f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static int byteArg(Object o) {
        int v = o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : 0;
        return Math.max(0, Math.min(255, v));
    }
}
