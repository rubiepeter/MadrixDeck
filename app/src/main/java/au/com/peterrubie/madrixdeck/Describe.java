package au.com.peterrubie.madrixdeck;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-English description of what a button does, for the press-and-hold info panel. */
public final class Describe {

    private static final Pattern PLACE = Pattern.compile("^/Deck([AB])/Places/Index$");
    private static final Pattern STORAGE = Pattern.compile("^/Deck([AB])/Storages/Index$");
    private static final Pattern SPEED = Pattern.compile("^/Deck([AB])/SpeedMaster$");
    private static final Pattern COLOUR = Pattern.compile("^/ColorKit/GlobalColors/(\\d+)/Rgb$");

    private Describe() {
    }

    public static String button(DeckLayout.Btn b, DeckLayout layout, Engine engine, String host, int port) {
        StringBuilder sb = new StringBuilder();
        switch (b.kind) {
            case DeckLayout.KIND_SPEED_DOWN:
                sb.append("Slows ").append(engine.speedTargetName()).append(" by ").append(engine.stepText())
                        .append(" (1.0 = normal speed, 0 = paused, negative = reverse).");
                break;
            case DeckLayout.KIND_SPEED_UP:
                sb.append("Speeds up ").append(engine.speedTargetName()).append(" by ").append(engine.stepText())
                        .append(" (1.0 = normal speed, up to 10).");
                break;
            case DeckLayout.KIND_SPEED_RESET:
                sb.append("Resets ").append(engine.speedTargetName()).append(" to the default speed (")
                        .append(OscMessage.trimFloat((float) engine.defaultSpeed)).append(").\nCurrently ")
                        .append(engine.speedText()).append(".");
                break;
            case DeckLayout.KIND_FADE:
                sb.append(b.fadeMs == 0 ? "Colour and place changes snap instantly." :
                        "Colour and place changes crossfade over " + (b.fadeMs / 1000.0) + " s.");
                sb.append("\nThe lit button applies to every colour and place press.")
                        .append("\nPlaces use MADRIX's crossfade time: OSC /Fade/Time ")
                        .append(OscMessage.trimFloat(b.fadeMs / 1000f));
                break;
            default:
                if (b.actions.isEmpty()) {
                    sb.append("No actions.");
                }
                for (DeckLayout.Act a : b.actions) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append("• ").append(action(a, layout, engine.placeOffset));
                }
                break;
        }
        sb.append("\n\nSends to MADRIX at ").append(host).append(':').append(port);
        return sb.toString();
    }

    /** Short name for the editor title, e.g. "Place 29", "Colour A", "Go to COL", "Speed +". */
    public static String shortName(DeckLayout.Btn b, DeckLayout layout) {
        return shortName(b, layout, 0);
    }

    public static String shortName(DeckLayout.Btn b, DeckLayout layout, int placeOffset) {
        switch (b.kind) {
            case DeckLayout.KIND_SPEED_DOWN: return "Speed −";
            case DeckLayout.KIND_SPEED_UP: return "Speed +";
            case DeckLayout.KIND_SPEED_RESET: return "Speed reset";
            case DeckLayout.KIND_FADE: return "Crossfade " + DeckLayout.fadeLabel(b.fadeMs);
            default: break;
        }
        for (DeckLayout.Act a : b.actions) {
            if (DeckLayout.Act.PAGE.equals(a.type)) {
                DeckLayout.Page p = layout.pageByNumber(a.page);
                return "Go to " + (p != null ? p.name : "page " + a.page);
            }
            if (DeckLayout.Act.PAGE_REL.equals(a.type)) {
                return a.delta > 0 ? "Next page" : "Previous page";
            }
            if (!DeckLayout.Act.OSC.equals(a.type)) {
                continue;
            }
            Integer v = a.args.isEmpty() || !(a.args.get(0) instanceof Number) ? null : ((Number) a.args.get(0)).intValue();
            Matcher m = PLACE.matcher(a.path);
            if (m.matches() && v != null) {
                return "Place " + (v + placeOffset + 1);
            }
            m = COLOUR.matcher(a.path);
            if (m.matches()) {
                int bank = Integer.parseInt(m.group(1));
                return "Colour " + (bank >= 1 && bank <= 26 ? String.valueOf((char) ('A' + bank - 1)) : String.valueOf(bank));
            }
        }
        String label = b.label == null ? "" : b.label.replace('\n', ' ').trim();
        return label.isEmpty() ? "Button" : label;
    }

    public static String action(DeckLayout.Act a, DeckLayout layout) {
        return action(a, layout, 0);
    }

    public static String action(DeckLayout.Act a, DeckLayout layout, int placeOffset) {
        switch (a.type) {
            case DeckLayout.Act.WAIT:
                return "Wait " + a.ms + " ms";
            case DeckLayout.Act.PAGE: {
                DeckLayout.Page p = layout.pageByNumber(a.page);
                return "Go to page " + a.page + (p != null ? " (" + p.name + ")" : " (not in this layout)");
            }
            case DeckLayout.Act.PAGE_REL:
                return a.delta > 0 ? "Next page" : "Previous page";
            case DeckLayout.Act.UNSUPPORTED:
                return "Not supported, skipped: " + a.note;
            default:
                break;
        }
        String raw = new OscMessage(a.path, a.args).toString();
        Integer v = a.args.isEmpty() || !(a.args.get(0) instanceof Number) ? null : ((Number) a.args.get(0)).intValue();
        Matcher m = PLACE.matcher(a.path);
        if (m.matches() && v != null) {
            int place = v + placeOffset;
            String osc = new OscMessage(a.path, java.util.Collections.singletonList(place)).toString();
            return "Deck " + m.group(1) + ", Place " + (place + 1) + " (sends index " + place + ")"
                    + (placeOffset != 0 ? "\n   Button " + (v + 1) + ", shifted by the First place setting" : "")
                    + "\n   Uses the current crossfade setting\n   OSC " + osc
                    + "\n   HTTP SetStorageDeck" + m.group(1) + "=P" + (place + 1);
        }
        m = STORAGE.matcher(a.path);
        if (m.matches() && v != null) {
            return "Deck " + m.group(1) + ", Storage " + (v + 1) + " (sends index " + v + ")\n   OSC " + raw;
        }
        m = SPEED.matcher(a.path);
        if (m.matches() && !a.args.isEmpty()) {
            return "Deck " + m.group(1) + " Speed Master = " + a.args.get(0) + "\n   OSC " + raw;
        }
        m = COLOUR.matcher(a.path);
        if (m.matches() && a.args.size() >= 3) {
            int bank = Integer.parseInt(m.group(1));
            String name = bank >= 1 && bank <= 26 ? String.valueOf((char) ('A' + bank - 1)) : String.valueOf(bank);
            return "Colour " + name + " (Global Colour " + bank + "), RGB " + a.args.get(0) + " " + a.args.get(1)
                    + " " + a.args.get(2) + "\n   Uses the current crossfade setting\n   OSC " + raw;
        }
        return "OSC " + raw;
    }
}
