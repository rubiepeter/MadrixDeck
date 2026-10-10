package au.com.peterrubie.madrixdeck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a Bitfocus Companion export (.companionconfig, already un-gzipped and parsed)
 * into a DeckLayout. Supports Generic OSC send actions, waits and page changes.
 * Then adds the MADRIX extras: speed row, colour crossfade buttons and colour headers.
 */
public final class CompanionImporter {

    public static final class Result {
        public DeckLayout layout;
        public String host;
        public int port;
        public int unsupported;
        public int skippedControls;
        public final List<String> notes = new ArrayList<>();
    }

    private static final Pattern PLACE = Pattern.compile("^/Deck([AB])/Places/Index$");
    private static final Pattern COLOUR = Pattern.compile("^/ColorKit/GlobalColors/(\\d+)/(Rgb|Red|Green|Blue|White)$");
    private static final Pattern INT = Pattern.compile("^-?\\d+$");
    private static final Pattern FLOAT = Pattern.compile("^-?(\\d+\\.\\d*|\\.\\d+|\\d+\\.?\\d*[eE][-+]?\\d+)$");

    private static final class Conn {
        String type = "";
        String label = "";
        boolean enabled = true;
        String host;
        int port;
    }

    private final Map<String, Conn> conns = new HashMap<>();
    private final Result result = new Result();

    public static Result importConfig(JSONObject root) {
        CompanionImporter im = new CompanionImporter();
        im.run(root);
        return im.result;
    }

    private void run(JSONObject root) {
        readConnections(root.optJSONObject("instances"));

        JSONObject pages = root.optJSONObject("pages");
        if (pages == null && root.optJSONObject("page") != null) {
            pages = new JSONObject();
            try {
                pages.put("1", root.optJSONObject("page"));
            } catch (Exception ignored) {
                // cannot happen for a fresh object
            }
        }
        DeckLayout layout = new DeckLayout();
        if (pages != null) {
            List<Integer> numbers = new ArrayList<>();
            Iterator<String> keys = pages.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    numbers.add(Integer.parseInt(k));
                } catch (NumberFormatException ignored) {
                    // not a page key
                }
            }
            Collections.sort(numbers);
            for (int n : numbers) {
                JSONObject pj = pages.optJSONObject(String.valueOf(n));
                if (pj == null) {
                    continue;
                }
                DeckLayout.Page page = readPage(pj, n);
                if (!page.buttons.isEmpty()) {
                    layout.pages.add(page);
                }
            }
        }
        for (DeckLayout.Page p : layout.pages) {
            enhance(p);
        }
        result.layout = layout;
    }

    private void readConnections(JSONObject inst) {
        if (inst == null) {
            return;
        }
        Iterator<String> keys = inst.keys();
        Conn best = null;
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject o = inst.optJSONObject(id);
            if (o == null) {
                continue;
            }
            Conn c = new Conn();
            c.type = firstNonEmpty(DeckLayout.str(o, "instance_type", ""), DeckLayout.str(o, "moduleId", ""));
            c.label = DeckLayout.str(o, "label", id);
            c.enabled = !(o.opt("enabled") instanceof Boolean) || (Boolean) o.opt("enabled");
            JSONObject cfg = o.optJSONObject("config");
            if (cfg != null) {
                c.host = DeckLayout.str(cfg, "host", null);
                c.port = toInt(cfg.opt("targetPort"), toInt(cfg.opt("port"), 0));
            }
            conns.put(id, c);
            if ("generic-osc".equals(c.type) && c.enabled && c.host != null && !c.host.isEmpty()) {
                boolean madrix = c.label.toLowerCase(Locale.US).contains("madrix");
                if (best == null || madrix) {
                    best = c;
                }
            }
        }
        if (best != null) {
            result.host = best.host;
            result.port = best.port;
        }
    }

    private DeckLayout.Page readPage(JSONObject pj, int number) {
        DeckLayout.Page page = new DeckLayout.Page();
        page.number = number;
        page.name = DeckLayout.str(pj, "name", "Page " + number);
        if ("PAGE".equals(page.name) || page.name.isEmpty()) {
            page.name = "Page " + number;
        }
        JSONObject grid = pj.optJSONObject("gridSize");
        int minCol = 0, maxCol = 7, minRow = 0, maxRow = 3;
        if (grid != null) {
            minCol = toInt(grid.opt("minColumn"), minCol);
            maxCol = toInt(grid.opt("maxColumn"), maxCol);
            minRow = toInt(grid.opt("minRow"), minRow);
            maxRow = toInt(grid.opt("maxRow"), maxRow);
        }
        page.cols = Math.max(1, maxCol - minCol + 1);
        page.rows = Math.max(1, maxRow - minRow + 1);

        JSONObject controls = pj.optJSONObject("controls");
        if (controls == null) {
            return page;
        }
        Iterator<String> rk = controls.keys();
        while (rk.hasNext()) {
            String rkey = rk.next();
            JSONObject row = controls.optJSONObject(rkey);
            int r = toInt(rkey, Integer.MIN_VALUE);
            if (row == null || r == Integer.MIN_VALUE) {
                continue;
            }
            Iterator<String> ck = row.keys();
            while (ck.hasNext()) {
                String ckey = ck.next();
                JSONObject ctl = row.optJSONObject(ckey);
                int c = toInt(ckey, Integer.MIN_VALUE);
                if (ctl == null || c == Integer.MIN_VALUE) {
                    continue;
                }
                if (r < minRow || r > maxRow || c < minCol || c > maxCol) {
                    result.skippedControls++;
                    continue;
                }
                String type = DeckLayout.str(ctl, "type", "");
                if (!"button-layered".equals(type) && !"button".equals(type)) {
                    result.skippedControls++;
                    continue;
                }
                DeckLayout.Btn b = readButton(ctl, type);
                b.row = r - minRow + 1;
                b.col = c - minCol + 1;
                page.buttons.add(b);
            }
        }
        page.buttons.sort((a, b) -> a.row != b.row ? a.row - b.row : a.col - b.col);
        return page;
    }

    private DeckLayout.Btn readButton(JSONObject ctl, String type) {
        DeckLayout.Btn b = new DeckLayout.Btn();
        JSONObject style = ctl.optJSONObject("style");
        if (style != null) {
            if ("button-layered".equals(type)) {
                JSONArray layers = style.optJSONArray("layers");
                if (layers != null) {
                    for (int i = 0; i < layers.length(); i++) {
                        JSONObject L = layers.optJSONObject(i);
                        if (L == null) {
                            continue;
                        }
                        String lt = DeckLayout.str(L, "type", "");
                        if ("text".equals(lt) && b.label.isEmpty()) {
                            b.label = cleanText(layerValue(L, "text"));
                            b.fg = toInt(layerValue(L, "color"), 0xFFFFFF) & 0xFFFFFF;
                        } else if ("box".equals(lt) && "box0".equals(DeckLayout.str(L, "id", ""))) {
                            b.bg = toInt(layerValue(L, "color"), 0) & 0xFFFFFF;
                        }
                    }
                }
            } else {
                b.label = cleanText(style.opt("text"));
                b.bg = toInt(style.opt("bgcolor"), 0) & 0xFFFFFF;
                b.fg = toInt(style.opt("color"), 0xFFFFFF) & 0xFFFFFF;
            }
        }
        b.origLabel = b.label;
        b.origBg = b.bg;

        JSONObject steps = ctl.optJSONObject("steps");
        if (steps != null) {
            TreeSet<Integer> stepKeys = new TreeSet<>();
            Iterator<String> sk = steps.keys();
            while (sk.hasNext()) {
                int v = toInt(sk.next(), Integer.MIN_VALUE);
                if (v != Integer.MIN_VALUE) {
                    stepKeys.add(v);
                }
            }
            if (!stepKeys.isEmpty()) {
                if (stepKeys.size() > 1) {
                    result.notes.add("Only the first step of multi-step buttons is used");
                }
                JSONObject step = steps.optJSONObject(String.valueOf(stepKeys.first()));
                JSONObject sets = step == null ? null : step.optJSONObject("action_sets");
                if (sets != null) {
                    parseActions(sets.optJSONArray("down"), b.actions, new int[]{0});
                    parseActions(sets.optJSONArray("up"), b.actions, new int[]{0});
                }
            }
        }
        return b;
    }

    /** lastDelay tracks Companion 3.x absolute per-action delays, converted to waits. */
    private void parseActions(JSONArray list, List<DeckLayout.Act> out, int[] lastDelay) {
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a == null) {
                continue;
            }
            if (Boolean.TRUE.equals(a.opt("disabled"))) {
                continue;
            }
            int delay = toInt(a.opt("delay"), 0);
            if (delay > lastDelay[0]) {
                DeckLayout.Act w = new DeckLayout.Act();
                w.type = DeckLayout.Act.WAIT;
                w.ms = delay - lastDelay[0];
                out.add(w);
                lastDelay[0] = delay;
            }
            String def = firstNonEmpty(DeckLayout.str(a, "definitionId", ""), DeckLayout.str(a, "action", ""));
            String connId = firstNonEmpty(DeckLayout.str(a, "connectionId", ""), DeckLayout.str(a, "instance", ""));
            JSONObject opts = a.optJSONObject("options");
            if (opts == null) {
                opts = new JSONObject();
            }

            // Action groups (Companion 4+) carry their actions in children; ordinary actions
            // may have an empty children object, which must not hide the action itself.
            if (def.startsWith("logic_")) {
                unsupported(out, "internal: " + def + " (conditional actions)");
                continue;
            }
            JSONObject children = a.optJSONObject("children");
            boolean hasChildren = false;
            if (children != null) {
                Iterator<String> groups = children.keys();
                while (groups.hasNext()) {
                    JSONArray group = children.optJSONArray(groups.next());
                    if (group != null && group.length() > 0) {
                        hasChildren = true;
                        parseActions(group, out, new int[]{0});
                    }
                }
            }
            if (hasChildren || def.startsWith("action_group")) {
                continue;
            }

            if ("internal".equals(connId)) {
                parseInternal(def, opts, out);
                continue;
            }
            Conn c = conns.get(connId);
            if (c == null || !"generic-osc".equals(c.type)) {
                unsupported(out, (c == null ? connId : c.label) + ": " + def);
                continue;
            }
            if (!c.enabled) {
                unsupported(out, c.label + " (disabled connection): " + def);
                continue;
            }
            parseOsc(def, opts, out, c.label);
        }
    }

    private void parseInternal(String def, JSONObject opts, List<DeckLayout.Act> out) {
        switch (def) {
            case "wait": {
                DeckLayout.Act w = new DeckLayout.Act();
                w.type = DeckLayout.Act.WAIT;
                w.ms = Math.max(0, toInt(optValue(opts, "time"), 0));
                out.add(w);
                return;
            }
            case "set_page":
            case "set_page_byindex": {
                Object pv = optValue(opts, "page");
                String ps = pv == null ? "" : String.valueOf(pv);
                DeckLayout.Act p = new DeckLayout.Act();
                if ("back".equals(ps) || "-1".equals(ps)) {
                    p.type = DeckLayout.Act.PAGE_REL;
                    p.delta = -1;
                } else if ("forward".equals(ps) || "+1".equals(ps)) {
                    p.type = DeckLayout.Act.PAGE_REL;
                    p.delta = 1;
                } else {
                    int n = toInt(pv, Integer.MIN_VALUE);
                    if (n == Integer.MIN_VALUE) {
                        unsupported(out, "internal: " + def + " " + ps);
                        return;
                    }
                    p.type = DeckLayout.Act.PAGE;
                    p.page = n;
                }
                out.add(p);
                return;
            }
            case "inc_page":
            case "dec_page": {
                DeckLayout.Act p = new DeckLayout.Act();
                p.type = DeckLayout.Act.PAGE_REL;
                p.delta = "inc_page".equals(def) ? 1 : -1;
                out.add(p);
                return;
            }
            default:
                unsupported(out, "internal: " + def);
        }
    }

    private void parseOsc(String def, JSONObject opts, List<DeckLayout.Act> out, String connLabel) {
        Object pathV = optValue(opts, "path");
        String path = pathV == null ? "" : String.valueOf(pathV).trim();
        if (path.isEmpty() || path.contains("$(") || isExpression(opts, "path")) {
            unsupported(out, connLabel + ": " + def + " (variable path " + path + ")");
            return;
        }
        DeckLayout.Act act = DeckLayout.Act.osc(path);
        switch (def) {
            case "send_int": {
                Object v = optValue(opts, "int");
                if (isVariable(opts, "int", v)) {
                    unsupported(out, connLabel + ": send_int " + path + " (variable value)");
                    return;
                }
                act.args.add((int) Math.round(toDouble(v, 0)));
                break;
            }
            case "send_float": {
                Object v = optValue(opts, "float");
                if (isVariable(opts, "float", v)) {
                    unsupported(out, connLabel + ": send_float " + path + " (variable value)");
                    return;
                }
                act.args.add((float) toDouble(v, 0));
                break;
            }
            case "send_string": {
                Object v = optValue(opts, "string");
                if (isVariable(opts, "string", v)) {
                    unsupported(out, connLabel + ": send_string " + path + " (variable value)");
                    return;
                }
                act.args.add(v == null ? "" : String.valueOf(v));
                break;
            }
            case "send_multiple": {
                Object v = optValue(opts, "arguments");
                if (isVariable(opts, "arguments", v)) {
                    unsupported(out, connLabel + ": send_multiple " + path + " (variable value)");
                    return;
                }
                act.args.addAll(parseArgumentString(v == null ? "" : String.valueOf(v)));
                break;
            }
            case "send_blank":
            case "send_no_args":
                break;
            default:
                unsupported(out, connLabel + ": " + def + " " + path);
                return;
        }
        out.add(act);
    }

    /** Same rules as Companion's Generic OSC module: ints, floats, otherwise strings (quotes allowed). */
    static List<Object> parseArgumentString(String s) {
        List<Object> args = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(s);
        while (m.find()) {
            if (m.group(1) != null) {
                args.add(m.group(1));
            } else if (m.group(2) != null) {
                args.add(m.group(2));
            } else {
                String t = m.group(3);
                if (INT.matcher(t).matches()) {
                    try {
                        args.add(Integer.parseInt(t));
                    } catch (NumberFormatException e) {
                        args.add((float) Double.parseDouble(t));
                    }
                } else if (FLOAT.matcher(t).matches()) {
                    args.add((float) Double.parseDouble(t));
                } else {
                    args.add(t);
                }
            }
        }
        return args;
    }

    // ---------- MADRIX extras ----------

    private void enhance(DeckLayout.Page page) {
        boolean hasPlaces = false;
        boolean hasSpeed = false;
        boolean hasFade = false;
        TreeMap<Integer, Integer> bankFirstRow = new TreeMap<>();
        for (DeckLayout.Btn b : page.buttons) {
            if (b.kind.startsWith("speed_")) {
                hasSpeed = true;
            }
            if (DeckLayout.KIND_FADE.equals(b.kind)) {
                hasFade = true;
            }
            for (DeckLayout.Act a : b.actions) {
                if (!DeckLayout.Act.OSC.equals(a.type)) {
                    continue;
                }
                if (PLACE.matcher(a.path).matches()) {
                    hasPlaces = true;
                }
                Matcher cm = COLOUR.matcher(a.path);
                if (cm.matches()) {
                    int bank = Integer.parseInt(cm.group(1));
                    Integer cur = bankFirstRow.get(bank);
                    if (cur == null || b.row < cur) {
                        bankFirstRow.put(bank, b.row);
                    }
                }
            }
        }

        if (hasPlaces && !hasSpeed && page.cols >= 3) {
            int row = lastRow(page) + 1;
            page.buttons.add(special(row, 1, DeckLayout.KIND_SPEED_DOWN, 0x2b3a4a, 0));
            page.buttons.add(special(row, 2, DeckLayout.KIND_SPEED_RESET, 0x44505c, 0));
            page.buttons.add(special(row, 3, DeckLayout.KIND_SPEED_UP, 0x2b3a4a, 0));
            page.rows = Math.max(page.rows, row);
        }

        if (!bankFirstRow.isEmpty() && !hasFade && page.cols >= 3) {
            int row = lastRow(page);
            List<Integer> free = new ArrayList<>();
            for (int c = 1; c <= page.cols; c++) {
                if (page.at(row, c) == null) {
                    free.add(c);
                }
            }
            if (free.size() < 3) {
                row = row + 1;
                free.clear();
                free.add(1);
                free.add(2);
                free.add(3);
            }
            page.buttons.add(special(row, free.get(0), DeckLayout.KIND_FADE, 0x333333, 0));
            page.buttons.add(special(row, free.get(1), DeckLayout.KIND_FADE, 0x333333, 1000));
            page.buttons.add(special(row, free.get(2), DeckLayout.KIND_FADE, 0x333333, 2000));
            page.rows = Math.max(page.rows, row);
        }

        if (!bankFirstRow.isEmpty() && page.headers.isEmpty()) {
            for (Map.Entry<Integer, Integer> e : bankFirstRow.entrySet()) {
                int bank = e.getKey();
                String name = bank >= 1 && bank <= 26 ? String.valueOf((char) ('A' + bank - 1)) : String.valueOf(bank);
                page.headers.put(e.getValue(), "COLOUR " + name);
            }
        }
        page.buttons.sort((a, b) -> a.row != b.row ? a.row - b.row : a.col - b.col);
    }

    private static int lastRow(DeckLayout.Page page) {
        int max = 0;
        for (DeckLayout.Btn b : page.buttons) {
            max = Math.max(max, b.row);
        }
        return max;
    }

    private static DeckLayout.Btn special(int row, int col, String kind, int bg, int fadeMs) {
        DeckLayout.Btn b = new DeckLayout.Btn();
        b.row = row;
        b.col = col;
        b.kind = kind;
        b.bg = bg;
        b.origBg = bg;
        b.fg = 0xFFFFFF;
        b.fadeMs = fadeMs;
        if (DeckLayout.KIND_FADE.equals(kind)) {
            b.label = fadeMs == 0 ? "SNAP" : (fadeMs / 1000) + "s";
        }
        b.origLabel = b.label;
        return b;
    }

    // ---------- helpers ----------

    private void unsupported(List<DeckLayout.Act> out, String note) {
        DeckLayout.Act a = new DeckLayout.Act();
        a.type = DeckLayout.Act.UNSUPPORTED;
        a.note = note;
        out.add(a);
        result.unsupported++;
    }

    /** Companion 4+/5 wraps option values as {value, isExpression}; older versions store them directly. */
    private static Object optValue(JSONObject opts, String key) {
        Object v = opts.opt(key);
        if (v instanceof JSONObject && ((JSONObject) v).has("value")) {
            Object inner = ((JSONObject) v).opt("value");
            return inner == JSONObject.NULL ? null : inner;
        }
        return v == JSONObject.NULL ? null : v;
    }

    private static boolean isExpression(JSONObject opts, String key) {
        Object v = opts.opt(key);
        return v instanceof JSONObject && Boolean.TRUE.equals(((JSONObject) v).opt("isExpression"));
    }

    private static boolean isVariable(JSONObject opts, String key, Object value) {
        return isExpression(opts, key) || (value instanceof String && ((String) value).contains("$("));
    }

    private static Object layerValue(JSONObject layer, String key) {
        Object v = layer.opt(key);
        if (v instanceof JSONObject) {
            Object inner = ((JSONObject) v).opt("value");
            return inner == JSONObject.NULL ? null : inner;
        }
        return v == JSONObject.NULL ? null : v;
    }

    private static String cleanText(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v).replace("\\n", "\n");
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    static int toInt(Object v, int fallback) {
        if (v instanceof Number) {
            return (int) Math.round(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble(((String) v).trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    static double toDouble(Object v, double fallback) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
