package au.com.peterrubie.madrixdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Button layout: pages of buttons, each with a list of actions. Stored as JSON on the phone. */
public final class DeckLayout {

    public static final String KIND_ACTION = "action";
    public static final String KIND_SPEED_DOWN = "speed_down";
    public static final String KIND_SPEED_UP = "speed_up";
    public static final String KIND_SPEED_RESET = "speed_reset";
    public static final String KIND_FADE = "fade";

    public final List<Page> pages = new ArrayList<>();

    public static final class Page {
        public String name = "";
        public int number = 1;
        public int cols = 4;
        public int rows = 8;
        public final TreeMap<Integer, String> headers = new TreeMap<>();
        public final List<Btn> buttons = new ArrayList<>();

        public Btn at(int row, int col) {
            for (Btn b : buttons) {
                if (b.row == row && b.col == col) {
                    return b;
                }
            }
            return null;
        }

        public boolean rowEmpty(int row) {
            for (Btn b : buttons) {
                if (b.row == row) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class Btn {
        public int row;
        public int col;
        public String label = "";
        public String origLabel = "";
        public int bg = 0x000000;
        public int origBg = 0x000000;
        public int fg = 0xFFFFFF;
        public String kind = KIND_ACTION;
        public int fadeMs = 0;
        public final List<Act> actions = new ArrayList<>();
    }

    public static final class Act {
        public static final String OSC = "osc";
        public static final String PAGE = "page";
        public static final String PAGE_REL = "pagerel";
        public static final String WAIT = "wait";
        public static final String UNSUPPORTED = "unsupported";

        public String type = OSC;
        public String path = "";
        public final List<Object> args = new ArrayList<>();
        public int page;
        public int delta;
        public int ms;
        public String note = "";

        public static Act osc(String path, Object... args) {
            Act a = new Act();
            a.type = OSC;
            a.path = path;
            for (Object o : args) {
                a.args.add(o);
            }
            return a;
        }
    }

    public Page pageByNumber(int number) {
        for (Page p : pages) {
            if (p.number == number) {
                return p;
            }
        }
        return null;
    }

    public int indexOfNumber(int number) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).number == number) {
                return i;
            }
        }
        return -1;
    }

    public int buttonCount() {
        int n = 0;
        for (Page p : pages) {
            n += p.buttons.size();
        }
        return n;
    }

    // ---------- JSON ----------

    public static String colorHex(int rgb) {
        return String.format(Locale.US, "#%06x", rgb & 0xFFFFFF);
    }

    public static int parseColor(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray jp = new JSONArray();
        for (Page p : pages) {
            JSONObject o = new JSONObject();
            o.put("name", p.name);
            o.put("number", p.number);
            o.put("cols", p.cols);
            o.put("rows", p.rows);
            JSONObject h = new JSONObject();
            for (Map.Entry<Integer, String> e : p.headers.entrySet()) {
                h.put(String.valueOf(e.getKey()), e.getValue());
            }
            o.put("headers", h);
            JSONArray jb = new JSONArray();
            for (Btn b : p.buttons) {
                JSONObject ob = new JSONObject();
                ob.put("row", b.row);
                ob.put("col", b.col);
                ob.put("label", b.label);
                ob.put("origLabel", b.origLabel);
                ob.put("bg", colorHex(b.bg));
                ob.put("origBg", colorHex(b.origBg));
                ob.put("fg", colorHex(b.fg));
                ob.put("kind", b.kind);
                if (b.fadeMs != 0) {
                    ob.put("fadeMs", b.fadeMs);
                }
                JSONArray ja = new JSONArray();
                for (Act a : b.actions) {
                    JSONObject oa = new JSONObject();
                    oa.put("type", a.type);
                    switch (a.type) {
                        case Act.OSC: {
                            oa.put("path", a.path);
                            JSONArray args = new JSONArray();
                            for (Object v : a.args) {
                                JSONObject arg = new JSONObject();
                                if (v instanceof Integer) {
                                    arg.put("t", "i");
                                    arg.put("v", ((Integer) v).intValue());
                                } else if (v instanceof Float) {
                                    arg.put("t", "f");
                                    arg.put("v", (double) ((Float) v).floatValue());
                                } else {
                                    arg.put("t", "s");
                                    arg.put("v", String.valueOf(v));
                                }
                                args.put(arg);
                            }
                            oa.put("args", args);
                            break;
                        }
                        case Act.PAGE: oa.put("page", a.page); break;
                        case Act.PAGE_REL: oa.put("delta", a.delta); break;
                        case Act.WAIT: oa.put("ms", a.ms); break;
                        default: oa.put("note", a.note); break;
                    }
                    ja.put(oa);
                }
                ob.put("actions", ja);
                jb.put(ob);
            }
            o.put("buttons", jb);
            jp.put(o);
        }
        root.put("pages", jp);
        return root;
    }

    public static DeckLayout fromJson(JSONObject root) {
        DeckLayout layout = new DeckLayout();
        JSONArray jp = root.optJSONArray("pages");
        if (jp == null) {
            return layout;
        }
        for (int i = 0; i < jp.length(); i++) {
            JSONObject o = jp.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Page p = new Page();
            p.name = str(o, "name", "");
            p.number = o.optInt("number", i + 1);
            p.cols = Math.max(1, o.optInt("cols", 4));
            p.rows = Math.max(1, o.optInt("rows", 8));
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                Iterator<String> keys = h.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    try {
                        p.headers.put(Integer.parseInt(k), str(h, k, ""));
                    } catch (NumberFormatException ignored) {
                        // skip bad key
                    }
                }
            }
            JSONArray jb = o.optJSONArray("buttons");
            if (jb != null) {
                for (int j = 0; j < jb.length(); j++) {
                    JSONObject ob = jb.optJSONObject(j);
                    if (ob == null) {
                        continue;
                    }
                    Btn b = new Btn();
                    b.row = ob.optInt("row", 1);
                    b.col = ob.optInt("col", 1);
                    b.label = str(ob, "label", "");
                    b.origLabel = str(ob, "origLabel", b.label);
                    b.bg = parseColor(str(ob, "bg", "#000000"), 0);
                    b.origBg = parseColor(str(ob, "origBg", colorHex(b.bg)), b.bg);
                    b.fg = parseColor(str(ob, "fg", "#ffffff"), 0xFFFFFF);
                    b.kind = str(ob, "kind", KIND_ACTION);
                    b.fadeMs = ob.optInt("fadeMs", 0);
                    JSONArray ja = ob.optJSONArray("actions");
                    if (ja != null) {
                        for (int k = 0; k < ja.length(); k++) {
                            JSONObject oa = ja.optJSONObject(k);
                            if (oa == null) {
                                continue;
                            }
                            Act a = new Act();
                            a.type = str(oa, "type", Act.OSC);
                            a.path = str(oa, "path", "");
                            a.page = oa.optInt("page", 1);
                            a.delta = oa.optInt("delta", 0);
                            a.ms = oa.optInt("ms", 0);
                            a.note = str(oa, "note", "");
                            JSONArray args = oa.optJSONArray("args");
                            if (args != null) {
                                for (int m = 0; m < args.length(); m++) {
                                    JSONObject arg = args.optJSONObject(m);
                                    if (arg == null) {
                                        continue;
                                    }
                                    String t = str(arg, "t", "s");
                                    if ("i".equals(t)) {
                                        a.args.add(arg.optInt("v", 0));
                                    } else if ("f".equals(t)) {
                                        a.args.add((float) arg.optDouble("v", 0));
                                    } else {
                                        a.args.add(str(arg, "v", ""));
                                    }
                                }
                            }
                            b.actions.add(a);
                        }
                    }
                    p.buttons.add(b);
                }
            }
            layout.pages.add(p);
        }
        return layout;
    }

    /** optString that never returns the literal "null" for JSON nulls. */
    static String str(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) {
            return fallback;
        }
        return String.valueOf(v);
    }
}
