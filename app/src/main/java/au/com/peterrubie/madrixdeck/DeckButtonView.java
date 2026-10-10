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

        if (text.isEmpty()) {
            return;
        }
        String[] lines = text.split("\n");
        float maxW = rect.width() * 0.84f;
        float maxH = rect.height() * 0.78f;
        float size = Math.min(rect.height() * 0.42f, 34 * density);
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
        textPaint.setColor(Color.rgb((btn.fg >> 16) & 0xFF, (btn.fg >> 8) & 0xFF, btn.fg & 0xFF));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float lineH = (fm.descent - fm.ascent) * 1.05f;
        float total = lineH * lines.length;
        float y = rect.centerY() - total / 2 - fm.ascent;
        for (String l : lines) {
            canvas.drawText(l, rect.centerX(), y, textPaint);
            y += lineH;
        }
    }
}
