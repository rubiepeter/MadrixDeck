package au.com.peterrubie.madrixdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** One deck button: rounded colour tile, auto-sized label, highlight when active, flash on press. */
final class DeckButtonView extends View {

    final DeckLayout.Btn btn;
    private String text = "";
    private boolean active;
    private long flashUntil;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    DeckButtonView(Context ctx, DeckLayout.Btn btn) {
        super(ctx);
        this.btn = btn;
        density = ctx.getResources().getDisplayMetrics().density;
        border.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(3 * density, 0, density, 0xAA000000);
        setClickable(true);
        setLongClickable(true);
        setHapticFeedbackEnabled(true);
    }

    void update(String text, boolean active) {
        if (!text.equals(this.text) || active != this.active) {
            this.text = text;
            this.active = active;
            invalidate();
        }
    }

    void flash() {
        flashUntil = SystemClock.uptimeMillis() + 160;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
        postDelayed(this::invalidate, 180);
    }

    private static int lighten(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = (int) (r + (255 - r) * amount);
        g = (int) (g + (255 - g) * amount);
        b = (int) (b + (255 - b) * amount);
        return Color.rgb(r, g, b);
    }

    private static boolean isLight(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 170;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float inset = 3 * density;
        float radius = 10 * density;
        rect.set(inset, inset, w - inset, h - inset);

        int bg = btn.bg & 0xFFFFFF;
        fill.setColor(active ? lighten(bg, 0.22f) : Color.rgb((bg >> 16) & 0xFF, (bg >> 8) & 0xFF, bg & 0xFF));
        canvas.drawRoundRect(rect, radius, radius, fill);

        if (active) {
            float bw = 4 * density;
            border.setStrokeWidth(bw);
            border.setColor(isLight(bg) ? Color.BLACK : Color.WHITE);
            RectF r2 = new RectF(rect.left + bw / 2, rect.top + bw / 2, rect.right - bw / 2, rect.bottom - bw / 2);
            canvas.drawRoundRect(r2, radius, radius, border);
        }

        if (SystemClock.uptimeMillis() < flashUntil) {
            fill.setColor(0x66FFFFFF);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }

        int fgColor = Color.rgb((btn.fg >> 16) & 0xFF, (btn.fg >> 8) & 0xFF, btn.fg & 0xFF);
        if (DeckLayout.isPageButton(btn)) {
            float iconH = rect.height() * (text.isEmpty() ? 0.5f : 0.36f);
            float cy = text.isEmpty() ? rect.centerY() : rect.top + rect.height() * 0.38f;
            drawPageIcon(canvas, rect.centerX(), cy, iconH, fgColor, active ? lighten(bg, 0.22f) : (0xFF000000 | bg));
            if (!text.isEmpty()) {
                RectF lower = new RectF(rect.left, rect.top + rect.height() * 0.62f, rect.right, rect.bottom - rect.height() * 0.04f);
                drawLabel(canvas, text.split("\n"), lower, fgColor, 0.62f);
            }
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        drawLabel(canvas, text.split("\n"), rect, fgColor, 0.42f);
    }

    /** Two stacked pages with an arrow: "go to another page". */
    private void drawPageIcon(Canvas canvas, float cx, float cy, float h, int color, int fillBehind) {
        float w = h * 0.78f;
        float off = h * 0.16f;
        float r = h * 0.1f;
        border.setStrokeWidth(Math.max(2f * density, h * 0.07f));
        border.setColor(color);
        // back page, offset up and right
        RectF back = new RectF(cx - w / 2 + off, cy - h / 2 - off * 0.5f, cx + w / 2 + off, cy + h / 2 - off * 0.5f);
        canvas.drawRoundRect(back, r, r, border);
        // front page, filled with the button colour so it hides the back page outline
        RectF front = new RectF(cx - w / 2 - off * 0.5f, cy - h / 2 + off * 0.5f, cx + w / 2 - off * 0.5f, cy + h / 2 + off * 0.5f);
        fill.setColor(fillBehind);
        canvas.drawRoundRect(front, r, r, fill);
        canvas.drawRoundRect(front, r, r, border);
        // arrow across the front page
        float ax1 = front.left + front.width() * 0.22f;
        float ax2 = front.right - front.width() * 0.2f;
        float ay = front.centerY();
        float head = front.width() * 0.22f;
        canvas.drawLine(ax1, ay, ax2, ay, border);
        canvas.drawLine(ax2, ay, ax2 - head, ay - head, border);
        canvas.drawLine(ax2, ay, ax2 - head, ay + head, border);
    }

    private void drawLabel(Canvas canvas, String[] lines, RectF area, int color, float heightFactor) {
        float maxW = area.width() * 0.84f;
        float maxH = area.height() * 0.78f;
        float size = Math.min(area.height() * heightFactor, 34 * density);
        if (lines.length > 1) {
            size = Math.min(size, maxH / (lines.length * 1.15f));
        }
        textPaint.setTextSize(size);
        float widest = 0;
        for (String l : lines) {
            widest = Math.max(widest, textPaint.measureText(l));
        }
        if (widest > maxW) {
            size = Math.max(9 * density, size * maxW / widest);
            textPaint.setTextSize(size);
        }
        textPaint.setColor(color);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.05f;
        float total = lineH * lines.length;
        float y = area.centerY() - total / 2 - fm.ascent;
        for (String l : lines) {
            canvas.drawText(l, area.centerX(), y, textPaint);
            y += lineH;
        }
    }
}
