package au.com.peterrubie.madrixdeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.RoundedCorner;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class MainActivity extends Activity implements Engine.Listener, OscClient.StatusListener {

    private static final String PREFS = "madrix_deck";
    private static final String LAYOUT_FILE = "layout.json";
    private static final int REQ_IMPORT = 42;

    private static final String DEFAULT_HOST = "10.0.10.199";
    private static final int DEFAULT_PORT = 9001;
    private static final int DEFAULT_FB_PORT = 9002;

    private static final int[] SWATCHES = {
            0xcc6500, 0x000099, 0x990000, 0xff0000, 0xff8000, 0xcccc00, 0x00ff00, 0x00ffa6,
            0x00e4ff, 0x0000ff, 0x9a00ff, 0xff00c4, 0xffbf80, 0xbfe0ff, 0xffffff, 0x808080,
            0x44505c, 0x2b3a4a, 0x333333, 0x000000};

    private SharedPreferences prefs;
    private DeckLayout layout;
    private int pageIndex;
    private Engine engine;
    private OscClient client;

    private LinearLayout grid;
    private TextView pageTitle;
    private TextView status;
    private final List<DeckButtonView> views = new ArrayList<>();
    private boolean refreshPosted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        client = new OscClient(this, this);
        engine = new Engine(client, this);
        client.setIncoming(engine::onIncoming);
        applySettings();

        buildUi();
        layout = loadLayout();
        pageIndex = Math.max(0, Math.min(prefs.getInt("page", 0), layout.pages.size() - 1));
        renderPage();
        hideSystemBars();

        client.start();
    }

    // ---------- settings ----------

    private void applySettings() {
        engine.stepPercent = prefDouble("step", 10);
        engine.defaultSpeed = prefDouble("defspeed", 1.0);
        engine.speedTarget = prefs.getString("target", "A");
        engine.fadeMs = prefs.getInt("fadems", 0);
        client.configure(prefs.getString("host", DEFAULT_HOST), prefs.getInt("port", DEFAULT_PORT),
                prefs.getInt("fbport", DEFAULT_FB_PORT));
    }

    private double prefDouble(String key, double fallback) {
        try {
            return Double.parseDouble(prefs.getString(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- layout storage ----------

    private DeckLayout loadLayout() {
        File f = new File(getFilesDir(), LAYOUT_FILE);
        try {
            String json;
            if (f.exists()) {
                json = readAll(new java.io.FileInputStream(f));
            } else {
                json = readAll(getAssets().open("default_layout.json"));
            }
            DeckLayout l = DeckLayout.fromJson(new JSONObject(json));
            if (!l.pages.isEmpty()) {
                return l;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Layout could not be read, using built-in layout", Toast.LENGTH_LONG).show();
        }
        try {
            return DeckLayout.fromJson(new JSONObject(readAll(getAssets().open("default_layout.json"))));
        } catch (Exception e) {
            return new DeckLayout();
        }
    }

    private void saveLayout() {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), LAYOUT_FILE))) {
            out.write(layout.toJson().toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Toast.makeText(this, "Could not save layout: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---------- UI ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(2), dp(8), dp(4));
        bar.setOnClickListener(v -> showMenu());

        pageTitle = new TextView(this);
        pageTitle.setTextColor(Color.WHITE);
        pageTitle.setTextSize(16);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(pageTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        status = new TextView(this);
        status.setTextSize(12);
        status.setGravity(Gravity.END);
        bar.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Keep the top bar and bottom row clear of the screen's rounded corners.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RoundedCorner tl = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                RoundedCorner tr = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                RoundedCorner bl = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                RoundedCorner br = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                top = Math.max(tl == null ? 0 : tl.getRadius(), tr == null ? 0 : tr.getRadius());
                bottom = Math.max(bl == null ? 0 : bl.getRadius(), br == null ? 0 : br.getRadius());
            } else {
                top = dp(24);
                bottom = dp(24);
            }
            // Text sits about halfway down the bar, so it needs roughly 40% of the corner radius.
            int side = Math.max(dp(8), Math.round(top * 0.42f));
            bar.setPadding(side, dp(2), side, dp(4));
            root.setPadding(dp(4), dp(4), dp(4), Math.max(dp(4), Math.round(bottom * 0.12f)));
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    private void renderPage() {
        grid.removeAllViews();
        views.clear();
        if (layout.pages.isEmpty()) {
            pageTitle.setText("No buttons");
            updateStatus();
            return;
        }
        DeckLayout.Page page = layout.pages.get(pageIndex);
        pageTitle.setText(page.name);

        int lastRow = page.rows;
        for (DeckLayout.Btn b : page.buttons) {
            lastRow = Math.max(lastRow, b.row);
        }
        for (int r = 1; r <= lastRow; r++) {
            String header = page.headers.get(r);
            if (header != null) {
                TextView h = new TextView(this);
                h.setText(header);
                h.setTextColor(0xFFBBBBBB);
                h.setTextSize(13);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setLetterSpacing(0.12f);
                h.setPadding(dp(8), dp(6), dp(8), dp(2));
                grid.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (page.rowEmpty(r)) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 1; c <= page.cols; c++) {
                DeckLayout.Btn b = page.at(r, c);
                View cell;
                if (b == null) {
                    cell = new View(this);
                } else {
                    DeckButtonView v = new DeckButtonView(this, b);
                    v.setOnClickListener(x -> {
                        v.flash();
                        engine.press(b);
                    });
                    v.setOnLongClickListener(x -> {
                        showEditor(b);
                        return true;
                    });
                    views.add(v);
                    cell = v;
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        refreshButtons();
        updateStatus();
    }

    private String labelFor(DeckLayout.Btn b) {
        switch (b.kind) {
            case DeckLayout.KIND_SPEED_DOWN:
                return "SPEED\n− " + engine.stepText();
            case DeckLayout.KIND_SPEED_UP:
                return "SPEED\n+ " + engine.stepText();
            case DeckLayout.KIND_SPEED_RESET:
                return "RESET\n" + engine.speedText();
            default:
                return b.label == null ? "" : b.label;
        }
    }

    private void refreshButtons() {
        for (DeckButtonView v : views) {
            v.update(labelFor(v.btn), engine.isActive(v.btn));
        }
    }

    private void updateStatus() {
        if (status == null) {
            return;
        }
        boolean up = client.wifiUp();
        boolean ok = up && client.lastSendOk();
        String text = "MADRIX " + client.host() + "  " + (up ? "Wi-Fi ✓" : "Wi-Fi ✗")
                + (engine.feedbackSeen() ? "  FB ✓" : "");
        if (up && !client.lastSendOk() && !client.lastError().isEmpty()) {
            text += "  send error";
        }
        status.setText(text);
        status.setTextColor(ok ? 0xFF7CD992 : 0xFFFFA040);
    }

    // ---------- Engine / client callbacks (any thread) ----------

    @Override
    public void onStateChanged() {
        synchronized (this) {
            if (refreshPosted) {
                return;
            }
            refreshPosted = true;
        }
        runOnUiThread(() -> {
            synchronized (MainActivity.this) {
                refreshPosted = false;
            }
            refreshButtons();
            updateStatus();
            prefs.edit().putInt("fadems", engine.fadeMs).apply();
        });
    }

    @Override
    public void onPageRequest(int page, int delta) {
        runOnUiThread(() -> {
            if (layout.pages.isEmpty()) {
                return;
            }
            int target;
            if (delta != 0) {
                int n = layout.pages.size();
                target = ((pageIndex + delta) % n + n) % n;
            } else {
                target = layout.indexOfNumber(page);
                if (target < 0) {
                    return;
                }
            }
            if (target != pageIndex) {
                pageIndex = target;
                prefs.edit().putInt("page", pageIndex).apply();
                renderPage();
            }
        });
    }

    @Override
    public void onStatusChanged() {
        runOnUiThread(this::updateStatus);
    }

    // ---------- menu ----------

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        showMenu();
    }

    private void showMenu() {
        String[] items = {"Settings", "Import Companion file", "Reset all button edits",
                "Restore built-in layout", "Connection info", "Exit"};
        new AlertDialog.Builder(this)
                .setTitle("MADRIX Deck")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showSettings(); break;
                        case 1: startImport(); break;
                        case 2: confirmResetEdits(); break;
                        case 3: confirmRestoreBuiltIn(); break;
                        case 4: showConnectionInfo(); break;
                        default: finish(); break;
                    }
                })
                .show();
    }

    private void showConnectionInfo() {
        String msg = "MADRIX: " + client.host() + ":" + client.port()
                + "\nWi-Fi: " + (client.wifiUp() ? "connected (app is pinned to Wi-Fi)" : "waiting for Wi-Fi")
                + "\nLast send: " + (client.lastSendOk() ? "OK" : "failed " + client.lastError())
                + "\nFeedback port " + prefs.getInt("fbport", DEFAULT_FB_PORT) + ": "
                + (client.feedbackOnPort() ? "listening" : "port busy, listening on reply port only")
                + "\nFeedback received: " + (engine.feedbackSeen() ? "yes" : "not yet")
                + "\n\nFor feedback, tick Enable Feedback in MADRIX: Preferences > Remote Control > OSC.";
        new AlertDialog.Builder(this).setTitle("Connection").setMessage(msg)
                .setPositiveButton("OK", null).show();
    }

    private LinearLayout formColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(8), dp(20), dp(8));
        return col;
    }

    private EditText addField(LinearLayout parent, String label, String value, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(10), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private void showSettings() {
        LinearLayout col = formColumn();
        int num = InputType.TYPE_CLASS_NUMBER;
        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        EditText host = addField(col, "MADRIX IP address", prefs.getString("host", DEFAULT_HOST),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = addField(col, "MADRIX OSC port", String.valueOf(prefs.getInt("port", DEFAULT_PORT)), num);
        EditText fb = addField(col, "Feedback port (MADRIX Destination Network Port)",
                String.valueOf(prefs.getInt("fbport", DEFAULT_FB_PORT)), num);
        EditText step = addField(col, "Speed step (% of normal speed)", prefs.getString("step", "10"), dec);
        EditText def = addField(col, "Reset speed (1.0 = normal)", prefs.getString("defspeed", "1.0"),
                dec | InputType.TYPE_NUMBER_FLAG_SIGNED);

        TextView tl = new TextView(this);
        tl.setText("Speed buttons control");
        tl.setPadding(0, dp(12), 0, 0);
        col.addView(tl);
        RadioGroup rg = new RadioGroup(this);
        String[] codes = {"A", "B", "AB"};
        String[] names = {"Deck A", "Deck B", "Both decks"};
        String cur = prefs.getString("target", "A");
        for (int i = 0; i < codes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(View.generateViewId());
            rb.setTag(codes[i]);
            rg.addView(rb);
            if (codes[i].equals(cur)) {
                rb.setChecked(true);
            }
        }
        col.addView(rg);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    SharedPreferences.Editor ed = prefs.edit();
                    String h = host.getText().toString().trim();
                    if (!h.isEmpty()) {
                        ed.putString("host", h);
                    }
                    ed.putInt("port", parseInt(port, DEFAULT_PORT));
                    ed.putInt("fbport", parseInt(fb, DEFAULT_FB_PORT));
                    ed.putString("step", String.valueOf(Math.max(0.1, parseDouble(step, 10))));
                    ed.putString("defspeed", String.valueOf(Math.max(-20, Math.min(20, parseDouble(def, 1.0)))));
                    View checked = rg.findViewById(rg.getCheckedRadioButtonId());
                    if (checked != null) {
                        ed.putString("target", String.valueOf(checked.getTag()));
                    }
                    ed.apply();
                    applySettings();
                    refreshButtons();
                    updateStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int parseInt(EditText e, int fallback) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v > 0 && v < 65536 ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ---------- press-and-hold editor ----------

    private TextView sectionLabel(LinearLayout parent, String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(0, dp(14), 0, dp(2));
        parent.addView(l);
        return l;
    }

    /** Three 0-255 sliders (R, G, B), each with a number box, kept in sync both ways. */
    private final class RgbSliders {
        final SeekBar[] bars = new SeekBar[3];
        final EditText[] boxes = new EditText[3];
        boolean updating;
        Runnable onChange;

        RgbSliders(LinearLayout parent, int[] start) {
            String[] names = {"R", "G", "B"};
            int[] tints = {0xFFFF5555, 0xFF55DD55, 0xFF5599FF};
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView n = new TextView(MainActivity.this);
                n.setText(names[i]);
                n.setTextColor(tints[i]);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(n, new LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT));
                SeekBar bar = new SeekBar(MainActivity.this);
                bar.setMax(255);
                bar.setProgress(clamp255(start[i]));
                bar.setProgressTintList(ColorStateList.valueOf(tints[i]));
                bar.setThumbTintList(ColorStateList.valueOf(tints[i]));
                row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                EditText box = new EditText(MainActivity.this);
                box.setInputType(InputType.TYPE_CLASS_NUMBER);
                box.setSingleLine(true);
                box.setText(String.valueOf(clamp255(start[i])));
                row.addView(box, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
                bars[i] = bar;
                boxes[i] = box;
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                        if (updating) {
                            return;
                        }
                        updating = true;
                        boxes[idx].setText(String.valueOf(progress));
                        updating = false;
                        fire();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                    }
                });
                box.addTextChangedListener(new SimpleWatcher() {
                    @Override
                    public void afterTextChanged(Editable s) {
                        if (updating) {
                            return;
                        }
                        int v;
                        try {
                            v = clamp255(Integer.parseInt(s.toString().trim()));
                        } catch (NumberFormatException e) {
                            return;
                        }
                        updating = true;
                        bars[idx].setProgress(v);
                        updating = false;
                        fire();
                    }
                });
                parent.addView(row);
            }
        }

        int[] get() {
            return new int[]{bars[0].getProgress(), bars[1].getProgress(), bars[2].getProgress()};
        }

        void set(int[] c) {
            updating = true;
            for (int i = 0; i < 3; i++) {
                bars[i].setProgress(clamp255(c[i]));
                boxes[i].setText(String.valueOf(clamp255(c[i])));
            }
            updating = false;
        }

        private void fire() {
            if (onChange != null) {
                onChange.run();
            }
        }
    }

    /** TextWatcher with empty defaults so callers only override afterTextChanged. */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int[] rgbOf(int color) {
        return new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
    }

    private static int colorOf(int[] rgb) {
        return (clamp255(rgb[0]) << 16) | (clamp255(rgb[1]) << 8) | clamp255(rgb[2]);
    }

    private static int argInt(Object o) {
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : 0;
    }

    private RadioGroup targetGroup(LinearLayout parent) {
        RadioGroup rg = new RadioGroup(this);
        String[] codes = {"A", "B", "AB"};
        String[] names = {"Deck A", "Deck B", "Both decks"};
        String cur = prefs.getString("target", "A");
        for (int i = 0; i < codes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(View.generateViewId());
            rb.setTag(codes[i]);
            rg.addView(rb);
            if (codes[i].equals(cur)) {
                rb.setChecked(true);
            }
        }
        parent.addView(rg);
        return rg;
    }

    private void showEditor(DeckLayout.Btn b) {
        LinearLayout col = formColumn();

        TextView info = new TextView(this);
        info.setText(Describe.button(b, layout, engine, client.host(), client.port()));
        info.setTextIsSelectable(true);
        col.addView(info);

        // Label (speed buttons generate their own)
        boolean dynamicLabel = b.kind.startsWith("speed_");
        EditText label = null;
        if (!dynamicLabel) {
            sectionLabel(col, "Label (new line for a second line)");
            label = new EditText(this);
            label.setText(b.label);
            label.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            label.setSingleLine(false);
            label.setMaxLines(3);
            col.addView(label);
        }

        int dec = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;

        // Crossfade time
        EditText fadeField = null;
        if (DeckLayout.KIND_FADE.equals(b.kind)) {
            sectionLabel(col, "Crossfade time (seconds, 0 = snap)");
            fadeField = new EditText(this);
            fadeField.setInputType(dec);
            fadeField.setSingleLine(true);
            fadeField.setText(trimNumber(b.fadeMs / 1000.0));
            col.addView(fadeField);
        }

        // Speed step / reset
        EditText stepField = null;
        EditText resetField = null;
        RadioGroup targetRg = null;
        if (DeckLayout.KIND_SPEED_DOWN.equals(b.kind) || DeckLayout.KIND_SPEED_UP.equals(b.kind)) {
            sectionLabel(col, "Speed step (% of normal speed, used by both speed buttons)");
            stepField = new EditText(this);
            stepField.setInputType(dec);
            stepField.setSingleLine(true);
            stepField.setText(trimNumber(engine.stepPercent));
            col.addView(stepField);
        }
        if (DeckLayout.KIND_SPEED_RESET.equals(b.kind)) {
            sectionLabel(col, "Reset speed (1.0 = normal)");
            resetField = new EditText(this);
            resetField.setInputType(dec | InputType.TYPE_NUMBER_FLAG_SIGNED);
            resetField.setSingleLine(true);
            resetField.setText(trimNumber(engine.defaultSpeed));
            col.addView(resetField);
            sectionLabel(col, "Speed buttons control");
            targetRg = targetGroup(col);
        }

        // MADRIX output colour, sent live while adjusting
        final DeckLayout.Act colourAct = DeckLayout.colourAction(b);
        final RgbSliders output;
        final boolean[] previewed = {false};
        final int[] before = colourAct == null ? null : engine.currentColour(colourAct.path);
        if (colourAct != null) {
            sectionLabel(col, "MADRIX output (sent live as you adjust)");
            output = new RgbSliders(col, new int[]{argInt(colourAct.args.get(0)), argInt(colourAct.args.get(1)),
                    argInt(colourAct.args.get(2))});
            output.onChange = () -> {
                int[] c = output.get();
                previewed[0] = true;
                engine.sendLiveColour(colourAct.path, c[0], c[1], c[2]);
            };
        } else {
            output = null;
        }

        // Button colour
        sectionLabel(col, "Button colour");
        LinearLayout hexRow = new LinearLayout(this);
        hexRow.setOrientation(LinearLayout.HORIZONTAL);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        View preview = new View(this);
        GradientDrawable pd = new GradientDrawable();
        pd.setCornerRadius(dp(6));
        pd.setColor(0xFF000000 | b.bg);
        pd.setStroke(dp(1), 0xFF888888);
        preview.setBackground(pd);
        hexRow.addView(preview, new LinearLayout.LayoutParams(dp(44), dp(36)));
        EditText hex = new EditText(this);
        hex.setText(DeckLayout.colorHex(b.bg));
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setSingleLine(true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        hp.leftMargin = dp(10);
        hexRow.addView(hex, hp);
        col.addView(hexRow);

        RgbSliders buttonRgb = new RgbSliders(col, rgbOf(b.bg));
        final boolean[] syncing = {false};
        buttonRgb.onChange = () -> {
            if (syncing[0]) {
                return;
            }
            int c = colorOf(buttonRgb.get());
            syncing[0] = true;
            hex.setText(DeckLayout.colorHex(c));
            syncing[0] = false;
            pd.setColor(0xFF000000 | c);
        };
        hex.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                int c = DeckLayout.parseColor(s.toString(), -1);
                if (c < 0) {
                    return;
                }
                pd.setColor(0xFF000000 | c);
                if (!syncing[0]) {
                    syncing[0] = true;
                    buttonRgb.set(rgbOf(c));
                    syncing[0] = false;
                }
            }
        });

        if (output != null) {
            Button match = new Button(this);
            match.setText("Match button to output");
            match.setOnClickListener(v -> hex.setText(DeckLayout.colorHex(colorOf(output.get()))));
            col.addView(match);
        }

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setPadding(0, dp(8), 0, dp(4));
        for (int c : SWATCHES) {
            View s = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(6));
            g.setColor(0xFF000000 | c);
            g.setStroke(dp(1), 0xFF888888);
            s.setBackground(g);
            s.setOnClickListener(v -> hex.setText(DeckLayout.colorHex(c)));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(38), dp(38));
            sp.rightMargin = dp(6);
            sw.addView(s, sp);
        }
        hs.addView(sw);
        col.addView(hs);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        final EditText labelField = label;
        final EditText fadeF = fadeField;
        final EditText stepF = stepField;
        final EditText resetF = resetField;
        final RadioGroup targetF = targetRg;
        final boolean[] saved = {false};
        String title = Describe.shortName(b, layout) + " (row " + b.row + ", column " + b.col + ")";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    saved[0] = true;
                    if (labelField != null) {
                        b.label = labelField.getText().toString().trim();
                    }
                    b.bg = DeckLayout.parseColor(hex.getText().toString(), b.bg);
                    if (colourAct != null && output != null) {
                        int[] c = output.get();
                        boolean changed = argInt(colourAct.args.get(0)) != c[0] || argInt(colourAct.args.get(1)) != c[1]
                                || argInt(colourAct.args.get(2)) != c[2];
                        if (changed) {
                            if (colourAct.origArgs == null) {
                                colourAct.origArgs = new ArrayList<>(colourAct.args);
                            }
                            colourAct.args.set(0, c[0]);
                            colourAct.args.set(1, c[1]);
                            colourAct.args.set(2, c[2]);
                        }
                    }
                    if (fadeF != null) {
                        int oldMs = b.fadeMs;
                        int newMs = (int) Math.round(Math.max(0, Math.min(60, parseDouble(fadeF, oldMs / 1000.0))) * 1000);
                        String oldAuto = DeckLayout.fadeLabel(oldMs);
                        b.fadeMs = newMs;
                        if (b.label.equals(oldAuto)) {
                            b.label = DeckLayout.fadeLabel(newMs);
                        }
                        if (b.origLabel.equals(oldAuto)) {
                            b.origLabel = DeckLayout.fadeLabel(newMs);
                        }
                        if (engine.fadeMs == oldMs) {
                            engine.fadeMs = newMs;
                            prefs.edit().putInt("fadems", newMs).apply();
                        }
                    }
                    SharedPreferences.Editor ed = prefs.edit();
                    if (stepF != null) {
                        ed.putString("step", String.valueOf(Math.max(0.1, parseDouble(stepF, engine.stepPercent))));
                    }
                    if (resetF != null) {
                        ed.putString("defspeed", String.valueOf(Math.max(-20, Math.min(20,
                                parseDouble(resetF, engine.defaultSpeed)))));
                    }
                    if (targetF != null) {
                        View checked = targetF.findViewById(targetF.getCheckedRadioButtonId());
                        if (checked != null) {
                            ed.putString("target", String.valueOf(checked.getTag()));
                        }
                    }
                    ed.apply();
                    applySettings();
                    saveLayout();
                    renderPage();
                })
                .setNeutralButton("Original", (d, w) -> {
                    b.label = b.origLabel;
                    b.bg = b.origBg;
                    if (colourAct != null && colourAct.origArgs != null) {
                        colourAct.args.clear();
                        colourAct.args.addAll(colourAct.origArgs);
                        colourAct.origArgs = null;
                    }
                    saveLayout();
                    renderPage();
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> {
                    // Not saved: put the rig back to the colour it showed before live adjusting.
                    if (!saved[0] && previewed[0] && before != null && colourAct != null) {
                        engine.sendLiveColour(colourAct.path, before[0], before[1], before[2]);
                    }
                })
                .show();
    }

    private static String trimNumber(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(java.util.Locale.US, "%.3f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void confirmResetEdits() {
        new AlertDialog.Builder(this)
                .setTitle("Reset all button edits?")
                .setMessage("Labels and colours go back to how they were imported.")
                .setPositiveButton("Reset", (d, w) -> {
                    for (DeckLayout.Page p : layout.pages) {
                        for (DeckLayout.Btn b : p.buttons) {
                            b.label = b.origLabel;
                            b.bg = b.origBg;
                        }
                    }
                    saveLayout();
                    renderPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRestoreBuiltIn() {
        new AlertDialog.Builder(this)
                .setTitle("Restore built-in layout?")
                .setMessage("Replaces the current layout, including any imported file and button edits.")
                .setPositiveButton("Restore", (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    new File(getFilesDir(), LAYOUT_FILE).delete();
                    layout = loadLayout();
                    pageIndex = 0;
                    prefs.edit().putInt("page", 0).apply();
                    renderPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- Companion import ----------

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        CompanionImporter.Result r;
        try {
            byte[] raw = readBytes(getContentResolver().openInputStream(uri));
            if (raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
                raw = readBytes(new GZIPInputStream(new ByteArrayInputStream(raw)));
            }
            r = CompanionImporter.importConfig(new JSONObject(new String(raw, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Import failed")
                    .setMessage("That file couldn't be read as a Companion export.\n\n" + e.getMessage())
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (r.layout.pages.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Nothing to import")
                    .setMessage("No buttons were found in that file.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("Found ").append(r.layout.pages.size()).append(" page(s), ")
                .append(r.layout.buttonCount()).append(" buttons.");
        if (r.unsupported > 0) {
            msg.append("\n").append(r.unsupported).append(" action(s) aren't supported and will be skipped "
                    + "(press and hold a button to see which).");
        }
        for (String n : new java.util.LinkedHashSet<>(r.notes)) {
            msg.append("\n").append(n).append('.');
        }
        boolean hostDiffers = r.host != null && (!r.host.equals(client.host()) || r.port != client.port());
        if (hostDiffers) {
            msg.append("\n\nThe file sends to MADRIX at ").append(r.host).append(':').append(r.port)
                    .append(". The app will switch to that address.");
        }
        msg.append("\n\nThis replaces the current layout and any button edits.");
        final CompanionImporter.Result result = r;
        new AlertDialog.Builder(this)
                .setTitle("Import Companion layout?")
                .setMessage(msg.toString())
                .setPositiveButton("Import", (d, w) -> {
                    layout = result.layout;
                    saveLayout();
                    if (hostDiffers) {
                        SharedPreferences.Editor ed = prefs.edit().putString("host", result.host);
                        if (result.port > 0) {
                            ed.putInt("port", result.port);
                        }
                        ed.apply();
                        applySettings();
                    }
                    pageIndex = 0;
                    prefs.edit().putInt("page", 0).apply();
                    renderPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- lifecycle ----------

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    protected void onDestroy() {
        client.stop();
        engine.shutdown();
        super.onDestroy();
    }
}
