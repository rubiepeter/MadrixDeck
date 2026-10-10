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
                        .append(" of normal speed.\nStep size and target deck are in Settings.");
                break;
            case DeckLayout.KIND_SPEED_UP:
                sb.append("Speeds up ").append(engine.speedTargetName()).append(" by ").append(engine.stepText())
                        .append(" of normal speed.\nStep size and target deck are in Settings.");
                break;
            case DeckLayout.KIND_SPEED_RESET:
                sb.append("Resets ").append(engine.speedTargetName()).append(" to the default speed (")
                        .append(OscMessage.trimFloat((float) engine.defaultSpeed)).append(").\nCurrently ")
                        .append(engine.speedText()).append(". Default speed is in Settings.");
                break;
            case DeckLayout.KIND_FADE:
                sb.append(b.fadeMs == 0 ? "Colour changes snap instantly." :
                        "Colour changes crossfade over " + (b.fadeMs / 1000.0) + " s.");
                sb.append("\nThe lit button applies to every colour press.");
                break;
            default:
                if (b.actions.isEmpty()) {
                    sb.append("No actions.");
                }
                for (DeckLayout.Act a : b.actions) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append("• ").append(action(a, layout));
                }
                break;
        }
        sb.append("\n\nSends to MADRIX at ").append(host).append(':').append(port);
        return sb.toString();
    }

    public static String action(DeckLayout.Act a, DeckLayout layout) {
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
            return "Deck " + m.group(1) + ", Place " + (v + 1) + " (sends index " + v + ")\n   OSC " + raw;
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
