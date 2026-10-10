package au.com.peterrubie.madrixdeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
    private static final int DEFAULT_HTTP_PORT = 80;

    private static final int[] SWATCHES = {
            0xcc6500, 0x000099, 0x990000, 0xff0000, 0xff8000, 0xcccc00, 0x00ff00, 0x00ffa6,
            0x00e4ff, 0x0000ff, 0x9a00ff, 0xff00c4, 0xffbf80, 0xbfe0ff, 0xffffff, 0x808080,
            0x44505c, 0x2b3a4a, 0x333333, 0x000000};

    private SharedPreferences prefs;
    private DeckLayout layout;
    private int pageIndex;
    private Engine engine;
    private OscClient client;
    private HttpLink http;
    private HttpStatus httpStatus;
    private volatile boolean sendViaHttp;
    private volatile boolean oscFeedback = true;
    private volatile boolean httpOkShown = true;
    private volatile List<Integer> visiblePlaces = new ArrayList<>();
    private CitpClient citp;
    private volatile Bitmap liveFrame;

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
        http = new HttpLink(client);
        engine = new Engine(this::route, this);
        client.setIncoming(m -> {
            if (oscFeedback) {
                engine.onIncoming(m);
            }
        });
        httpStatus = new HttpStatus(http, new HttpStatus.Listener() {
            @Override
            public void onDeckState(Integer storage, Integer place) {
                engine.applyExternal(Engine.DECK_A, storage, place);
            }

            @Override
            public void onStatusChanged() {
                MainActivity.this.onStateChanged();
            }
        }, MainActivity::decodeThumb, () -> visiblePlaces);

        citp = new CitpClient(this, client, new CitpClient.Listener() {
            @Override
            public void onFrame(Bitmap frame) {
                liveFrame = frame;
                MainActivity.this.onStateChanged();
            }

            @Override
            public void onCitpStatus() {
                MainActivity.this.onStatusChanged();
            }
        });

        layout = loadLayout();
        applySettings();
        engine.restoreState(prefs.getString("state", null));

        buildUi();
        pageIndex = Math.max(0, Math.min(prefs.getInt("page", 0), layout.pages.size() - 1));
        renderPage();
        hideSystemBars();

        client.start();
        httpStatus.start();
        citp.start();
    }

    /** Sends a message by HTTP when chosen in Settings and MADRIX has an HTTP equivalent, otherwise by OSC. */
    private boolean route(OscMessage m) {
        if (sendViaHttp) {
            String cmd = HttpCommands.fromOsc(m);
            if (cmd != null) {
                boolean ok = http.send(cmd);
                if (ok != httpOkShown) {
                    httpOkShown = ok;
                    onStatusChanged();
                }
                return ok;
            }
        }
        return client.send(m);
    }

    /** Thumbnail bytes to a small Bitmap (at most about 320 px across). */
    private static Object decodeThumb(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /** 0-based MADRIX places on all place buttons, for HTTP to read names, thumbnails and empty/filled. */
    private void updateVisiblePlaces() {
        if (layout == null) {
            return;
        }
        List<Integer> list = new ArrayList<>();
        for (DeckLayout.Page p : layout.pages) {
            for (DeckLayout.Btn b : p.buttons) {
                Integer place = engine.placeOf(b);
                if (place != null && place >= 0 && place < Engine.PLACE_COUNT && !list.contains(place)) {
                    list.add(place);
                }
            }
        }
        java.util.Collections.sort(list);
        visiblePlaces = list;
    }

    // ---------- settings ----------

    private void applySettings() {
        engine.speedStep = prefDouble("stepv", 0.1);
        engine.defaultSpeed = prefDouble("defspeed", 1.0);
        engine.speedTarget = prefs.getString("target", "A");
        engine.fadeMs = prefs.getInt("fadems", 0);
        int oldOffset = engine.placeOffset;
        engine.placeOffset = Math.max(0, prefs.getInt("firstplace", 1) - 1);
        sendViaHttp = "http".equals(prefs.getString("send", "osc"));
        oscFeedback = prefs.getBoolean("oscfb", true);
        String host = prefs.getString("host", DEFAULT_HOST);
        client.configure(host, prefs.getInt("port", DEFAULT_PORT), prefs.getInt("fbport", DEFAULT_FB_PORT));
        http.configure(host, prefs.getInt("httpport", DEFAULT_HTTP_PORT));
        httpStatus.enabled = prefs.getBoolean("httpstatus", true);
        citp.host = host;
        citp.manualPort = prefs.getInt("citpport", 0);
        citp.enabled = prefs.getBoolean("live", true);
        updateVisiblePlaces();
        if (oldOffset != engine.placeOffset) {
            httpStatus.refresh();
        }
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
                if (l.version < DeckLayout.CURRENT_VERSION) {
                    if (l.version < 2) {
                        // From 2.3 and earlier: grey place buttons with a divider.
                        for (DeckLayout.Page p : l.pages) {
                            CompanionImporter.stylePlaces(p);
                        }
                    }
                    if (l.version < 3) {
                        // From 2.4 and earlier: labels / colours typed on place buttons belong to the place.
                        DeckLayout.movePlaceEditsToPlaces(l);
                    }
                    if (l.version < 4) {
                        DeckLayout.applyFriendlyNames(l);
                    }
                    l.version = DeckLayout.CURRENT_VERSION;
                    layout = l;
                    saveLayout();
                }
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
            root.setPadding(dp(4), dp(4), dp(4), bottomPadding(bottom));
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    /**
     * Bottom padding so the corner buttons' rounded corners sit inside the screen's rounded
     * corner. Each button's corner circle (10 dp radius, inset 3 dp + 4 dp side padding) must
     * fit inside the screen corner circle of radius r.
     */
    private int bottomPadding(int r) {
        int min = dp(4);
        if (r <= 0) {
            return min;
        }
        float rb = dp(10);
        float inset = dp(4) + dp(3) + rb; // side padding + button inset + button corner radius
        float dx = r - inset;
        if (dx <= 0 || r - rb <= dx) {
            return Math.max(min, dx <= 0 ? min : Math.round(r - rb));
        }
        float dy = (float) Math.sqrt((r - rb) * (r - rb) - dx * dx);
        float pad = r - dy - (dp(3) + rb) + dp(2); // minus button inset and corner radius, plus a margin
        return Math.max(min, Math.round(pad));
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
            if (page.dividers.contains(r)) {
                View line = new View(this);
                line.setBackgroundColor(0xFF5A5A5A);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
                lp.topMargin = dp(4);
                lp.bottomMargin = dp(4);
                lp.leftMargin = dp(10);
                lp.rightMargin = dp(10);
                grid.addView(line, lp);
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
                        if (engine.placeOf(b) != null) {
                            citp.markPress();
                        }
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
        updateVisiblePlaces();
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
                return engine.speedText() + "\nRESET";
            default:
                return b.label == null ? "" : b.label;
        }
    }

    private void refreshButtons() {
        boolean useHttp = httpStatus.enabled;
        for (DeckButtonView v : views) {
            DeckLayout.Btn b = v.btn;
            Integer place = engine.placeOf(b);
            if (place == null) {
                v.update(labelFor(b), engine.isActive(b));
                continue;
            }
            boolean hidden = place < 0 || place >= Engine.PLACE_COUNT;
            DeckLayout.PlaceStyle st = layout.placeStyles.get(place);
            String label = st != null && st.label != null ? st.label.trim() : "";
            boolean autoLabel = label.isEmpty();
            int bg = st != null && st.bg != null ? st.bg : DeckLayout.PLACE_GREY;
            String number = String.valueOf(place + 1);
            String name = useHttp && !hidden ? httpStatus.name(place) : null;
            Object img = useHttp && !hidden ? httpStatus.thumb(place) : null;
            Bitmap thumb = img instanceof Bitmap ? (Bitmap) img : null;
            boolean empty = useHttp && !hidden && Boolean.FALSE.equals(httpStatus.filled(place));
            String bottom = empty ? null : (autoLabel ? name : label);
            boolean active = engine.isActive(b);
            boolean live = active && !hidden && citp.live() && liveFrame != null && !citp.holding();
            if (live) {
                // The selected place shows MADRIX's live Main Output instead of its stored thumbnail.
                thumb = liveFrame;
                empty = false;
            }
            v.update(autoLabel ? number : label, active, number, bottom, empty ? null : thumb, empty, hidden, bg);
            v.setLive(live);
        }
    }

    private void updateStatus() {
        if (status == null) {
            return;
        }
        boolean up = client.wifiUp();
        boolean oscOk = up && client.lastSendOk();
        boolean showHttp = sendViaHttp || httpStatus.enabled;
        boolean httpOk = httpStatus.enabled ? httpStatus.ok() : http.lastOk();
        String text = client.host() + "  " + (up ? "" : "Wi-Fi \u2717  ") + "OSC " + (oscOk ? "\u2713" : "\u2717")
                + (showHttp ? "  HTTP " + (httpOk ? "\u2713" : "\u2717") : "")
                + (oscFeedback && engine.feedbackSeen() ? "  FB \u2713" : "");
        status.setText(text);
        boolean good = sendViaHttp ? httpOk : oscOk;
        if (httpStatus.enabled && !httpOk) {
            good = false;
        }
        status.setTextColor(good ? 0xFF7CD992 : 0xFFFFA040);
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
            prefs.edit().putInt("fadems", engine.fadeMs).putString("state", engine.saveState()).apply();
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
                + "\nOSC feedback: " + (oscFeedback ? (engine.feedbackSeen() ? "received" : "on, nothing received yet") : "off")
                + "\n\nPresses sent by: " + (sendViaHttp ? "HTTP (OSC for anything without an HTTP command)" : "OSC")
                + "\nHTTP: " + http.url("...")
                + "\nHTTP status: " + (httpStatus.enabled ? (httpStatus.ok() ? "OK" : "failing " + httpStatus.lastError()) : "off")
                + "\n\nOSC feedback needs Enable Feedback in MADRIX: Preferences > Remote Control > OSC."
                + "\nHTTP needs Remote HTTP on in MADRIX: Preferences > Remote Control > Remote HTTP."
                + "\n\nLive preview (CITP): " + citp.summary()
                + "\nCITP needs Enable Server in MADRIX: Preferences > Options > Devices Network > Capture - CITP."
                + "\n\nCITP log:\n" + citp.logText();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Bitmap frame = citp.lastFrame();
        if (frame != null) {
            TextView cap = new TextView(this);
            cap.setText("Latest live frame (" + frame.getWidth() + "x" + frame.getHeight() + "):");
            cap.setPadding(dp(20), dp(8), dp(20), dp(4));
            box.addView(cap);
            ImageView img = new ImageView(this);
            img.setImageBitmap(frame);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundColor(0xFF000000);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
            ip.leftMargin = dp(20);
            ip.rightMargin = dp(20);
            box.addView(img, ip);
        }
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(tv);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Connection").setView(sv)
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

        sectionLabel(col, "MADRIX");
        EditText host = addField(col, "MADRIX IP address", prefs.getString("host", DEFAULT_HOST),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        sectionLabel(col, "Send presses with");
        RadioGroup sendRg = new RadioGroup(this);
        RadioButton viaOsc = new RadioButton(this);
        viaOsc.setText("OSC");
        viaOsc.setId(View.generateViewId());
        RadioButton viaHttp = new RadioButton(this);
        viaHttp.setText("HTTP (anything without an HTTP command still goes by OSC)");
        viaHttp.setId(View.generateViewId());
        sendRg.addView(viaOsc);
        sendRg.addView(viaHttp);
        if (sendViaHttp) {
            viaHttp.setChecked(true);
        } else {
            viaOsc.setChecked(true);
        }
        col.addView(sendRg);

        sectionLabel(col, "OSC");
        EditText port = addField(col, "MADRIX OSC port", String.valueOf(prefs.getInt("port", DEFAULT_PORT)), num);
        EditText fb = addField(col, "Feedback port (MADRIX Destination Network Port)",
                String.valueOf(prefs.getInt("fbport", DEFAULT_FB_PORT)), num);
        CheckBox useFb = new CheckBox(this);
        useFb.setText("Use OSC feedback");
        useFb.setChecked(oscFeedback);
        col.addView(useFb);

        sectionLabel(col, "HTTP (MADRIX Remote HTTP)");
        EditText httpPort = addField(col, "HTTP port", String.valueOf(prefs.getInt("httpport", DEFAULT_HTTP_PORT)), num);
        CheckBox useHttpStatus = new CheckBox(this);
        useHttpStatus.setText("Read status by HTTP: current place, empty places, names, thumbnails");
        useHttpStatus.setChecked(httpStatus.enabled);
        col.addView(useHttpStatus);
        Button test = new Button(this);
        test.setText("Test HTTP");
        test.setOnClickListener(v -> testHttp(host.getText().toString().trim(), parseInt(httpPort, DEFAULT_HTTP_PORT)));
        col.addView(test);

        sectionLabel(col, "Live preview (CITP)");
        CheckBox useLive = new CheckBox(this);
        useLive.setText("Show MADRIX's live output on the selected place");
        useLive.setChecked(citp.enabled);
        col.addView(useLive);
        EditText citpPort = addField(col, "CITP TCP port (0 = find automatically)",
                String.valueOf(prefs.getInt("citpport", 0)), num);

        sectionLabel(col, "Places");
        final int[] firstPlace = {Math.max(1, prefs.getInt("firstplace", 1))};
        Button first = new Button(this);
        first.setText(firstPlaceText(firstPlace[0]));
        first.setOnClickListener(v -> {
            String[] items = new String[16];
            for (int i = 0; i < 16; i++) {
                items[i] = firstPlaceText(1 + 16 * i);
            }
            new AlertDialog.Builder(this)
                    .setTitle("First place")
                    .setItems(items, (d, which) -> {
                        firstPlace[0] = 1 + 16 * which;
                        first.setText(firstPlaceText(firstPlace[0]));
                    })
                    .show();
        });
        col.addView(first);

        sectionLabel(col, "Speed");
        EditText step = addField(col, "Speed step (1.0 = normal speed)", trimNumber(engine.speedStep), dec);
        EditText def = addField(col, "Reset speed (1.0 = normal, 0 = paused)", trimNumber(engine.defaultSpeed),
                dec | InputType.TYPE_NUMBER_FLAG_SIGNED);
        TextView tl = new TextView(this);
        tl.setText("Speed buttons control");
        tl.setPadding(0, dp(12), 0, 0);
        col.addView(tl);
        RadioGroup rg = targetGroup(col);

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
                    ed.putString("send", viaHttp.isChecked() ? "http" : "osc");
                    ed.putInt("port", parseInt(port, DEFAULT_PORT));
                    ed.putInt("fbport", parseInt(fb, DEFAULT_FB_PORT));
                    ed.putBoolean("oscfb", useFb.isChecked());
                    ed.putInt("httpport", parseInt(httpPort, DEFAULT_HTTP_PORT));
                    ed.putBoolean("httpstatus", useHttpStatus.isChecked());
                    ed.putBoolean("live", useLive.isChecked());
                    int cp = 0;
                    try {
                        cp = Integer.parseInt(citpPort.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                        // keep 0 = automatic
                    }
                    ed.putInt("citpport", cp > 0 && cp < 65536 ? cp : 0);
                    ed.putInt("firstplace", firstPlace[0]);
                    ed.putString("stepv", String.valueOf(Math.max(0.001, Math.min(10, parseDouble(step, 0.1)))));
                    ed.putString("defspeed", String.valueOf(Math.max(-10, Math.min(10, parseDouble(def, 1.0)))));
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

    private static String firstPlaceText(int first) {
        int last = Math.min(Engine.PLACE_COUNT, first + 31);
        return "Places " + first + " to " + last;
    }

    /** Tries the HTTP commands the app relies on and shows MADRIX's raw replies. */
    private void testHttp(String host, int port) {
        HttpLink link = new HttpLink(client);
        link.configure(host.isEmpty() ? prefs.getString("host", DEFAULT_HOST) : host, port);
        Toast.makeText(this, "Testing " + link.url("..."), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(link.url("<command>")).append("\n\n");
            Integer storage = null;
            Integer place = null;
            String[] cmds = {HttpCommands.getStorageDeck("A"), HttpCommands.getPlaceDeck("A")};
            for (String c : cmds) {
                String reply = tryText(link, c, sb);
                if (c.startsWith("GetStorageDeck")) {
                    storage = HttpCommands.parseStorage(reply);
                } else {
                    place = HttpCommands.parsePlace(reply);
                }
            }
            int s = storage == null ? 1 : storage;
            int p = place == null ? 1 : place;
            tryText(link, HttpCommands.fullStateStorage(s), sb);
            tryText(link, HttpCommands.fullState(s, p), sb);
            tryText(link, HttpCommands.description(s, p), sb);
            String thumbCmd = HttpCommands.thumbnail(s, p);
            try {
                byte[] img = link.bytes(thumbCmd);
                Object bmp = decodeThumb(img);
                sb.append(thumbCmd).append("\n  ").append(img.length).append(" bytes, ")
                        .append(bmp != null ? "a valid image" : "not an image").append("\n\n");
            } catch (Exception e) {
                sb.append(thumbCmd).append("\n  FAILED: ").append(e.getMessage()).append("\n\n");
            }
            sb.append("Read as: storage ").append(storage).append(", place ").append(place);
            runOnUiThread(() -> {
                TextView tv = new TextView(this);
                tv.setText(sb.toString());
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(20), dp(8), dp(20), dp(8));
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("HTTP test").setView(sv)
                        .setPositiveButton("OK", null).show();
            });
        }, "http-test").start();
    }

    private static String tryText(HttpLink link, String cmd, StringBuilder sb) {
        try {
            String reply = link.text(cmd);
            String shown = reply.length() > 300 ? reply.substring(0, 300) + "…" : reply;
            sb.append(cmd).append("\n  \"").append(shown).append("\"\n\n");
            return reply;
        } catch (Exception e) {
            sb.append(cmd).append("\n  FAILED: ").append(e.getMessage()).append("\n\n");
            return null;
        }
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
        // Place buttons edit the place's own label / colour, not the button slot.
        final Integer place = engine.placeOf(b);
        final DeckLayout.PlaceStyle oldStyle = place == null ? null : layout.placeStyles.get(place);
        final String curLabel = place == null ? b.label
                : (oldStyle != null && oldStyle.label != null ? oldStyle.label : "");
        final int curBg = place == null ? b.bg
                : (oldStyle != null && oldStyle.bg != null ? oldStyle.bg : DeckLayout.PLACE_GREY);

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
            label.setText(curLabel);
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
            sectionLabel(col, "Speed step (1.0 = normal speed, used by both speed buttons)");
            stepField = new EditText(this);
            stepField.setInputType(dec);
            stepField.setSingleLine(true);
            stepField.setText(trimNumber(engine.speedStep));
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
        pd.setColor(0xFF000000 | curBg);
        pd.setStroke(dp(1), 0xFF888888);
        preview.setBackground(pd);
        hexRow.addView(preview, new LinearLayout.LayoutParams(dp(44), dp(36)));
        EditText hex = new EditText(this);
        hex.setText(DeckLayout.colorHex(curBg));
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setSingleLine(true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        hp.leftMargin = dp(10);
        hexRow.addView(hex, hp);
        col.addView(hexRow);

        RgbSliders buttonRgb = new RgbSliders(col, rgbOf(curBg));
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
        String title = Describe.shortName(b, layout, engine.placeOffset) + " (row " + b.row + ", column " + b.col + ")";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    saved[0] = true;
                    if (place != null) {
                        String l = labelField == null ? "" : labelField.getText().toString().trim();
                        int c = DeckLayout.parseColor(hex.getText().toString(), curBg);
                        DeckLayout.PlaceStyle st = new DeckLayout.PlaceStyle();
                        st.label = l.isEmpty() || l.equals(String.valueOf(place + 1)) ? null : l;
                        st.bg = c == DeckLayout.PLACE_GREY ? null : c;
                        if (st.label == null && st.bg == null) {
                            layout.placeStyles.remove(place);
                        } else {
                            layout.placeStyles.put(place, st);
                        }
                    } else {
                        if (labelField != null) {
                            b.label = labelField.getText().toString().trim();
                        }
                        b.bg = DeckLayout.parseColor(hex.getText().toString(), b.bg);
                    }
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
                        if (engine.fadeMs == oldMs) {
                            engine.fadeMs = newMs;
                            prefs.edit().putInt("fadems", newMs).apply();
                        }
                    }
                    SharedPreferences.Editor ed = prefs.edit();
                    if (stepF != null) {
                        ed.putString("stepv", String.valueOf(Math.max(0.001, Math.min(10, parseDouble(stepF, engine.speedStep)))));
                    }
                    if (resetF != null) {
                        ed.putString("defspeed", String.valueOf(Math.max(-10, Math.min(10,
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
                .setNeutralButton("Default", (d, w) -> {
                    if (place != null) {
                        layout.placeStyles.remove(place);
                    }
                    b.label = b.origLabel;
                    b.bg = b.origBg;
                    if (DeckLayout.KIND_FADE.equals(b.kind) && b.origFadeMs >= 0) {
                        int oldMs = b.fadeMs;
                        b.fadeMs = b.origFadeMs;
                        b.label = DeckLayout.fadeLabel(b.origFadeMs);
                        b.origLabel = b.label;
                        if (engine.fadeMs == oldMs) {
                            engine.fadeMs = b.fadeMs;
                            prefs.edit().putInt("fadems", b.fadeMs).apply();
                        }
                    }
                    if (DeckLayout.KIND_SPEED_DOWN.equals(b.kind) || DeckLayout.KIND_SPEED_UP.equals(b.kind)) {
                        prefs.edit().putString("stepv", "0.1").apply();
                        applySettings();
                    }
                    if (DeckLayout.KIND_SPEED_RESET.equals(b.kind)) {
                        prefs.edit().putString("defspeed", "1.0").putString("target", "A").apply();
                        applySettings();
                    }
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
                    layout.placeStyles.clear();
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

    private android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Low-latency Wi-Fi while the app is on screen (less power-save delay on the live preview). */
    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (wifiLock == null) {
                android.net.wifi.WifiManager wm =
                        (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "madrixdeck");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (Exception ignored) {
            // not critical
        }
    }

    @Override
    protected void onPause() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        httpStatus.stop();
        citp.stop();
        client.stop();
        engine.shutdown();
        super.onDestroy();
    }
}
