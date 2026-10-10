package au.com.peterrubie.madrixdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * One deck button: rounded colour tile, auto-sized label, highlight when active, flash on press.
 * Place buttons can also show a MADRIX thumbnail, the place number in a corner, the place name
 * along the bottom, and a dimmed look when the place is empty.
 */
final class DeckButtonView extends View {

    static final int EMPTY_GREY = 0x1c1c1c;

    final DeckLayout.Btn btn;
    private String text = "";
    private boolean active;
    private long flashUntil;

    // Place-button extras (null / false for other buttons)
    private String corner;
    private String bottom;
    private Bitmap thumb;
    private boolean empty;
    private boolean hidden;
    private int bgColor;
    private boolean live;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float density;

    DeckButtonView(Context ctx, DeckLayout.Btn btn) {
        super(ctx);
        this.btn = btn;
        this.bgColor = btn.bg;
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
        update(text, active, null, null, null, false, false, btn.bg);
    }

    void update(String text, boolean active, String corner, String bottom, Bitmap thumb, boolean empty, boolean hidden,
                int bg) {
        boolean changed = !text.equals(this.text) || active != this.active || !eq(corner, this.corner)
                || !eq(bottom, this.bottom) || thumb != this.thumb || empty != this.empty || hidden != this.hidden
                || bg != this.bgColor;
        if (changed) {
            this.bgColor = bg;
            this.text = text;
            this.active = active;
            this.corner = corner;
            this.bottom = bottom;
            this.thumb = thumb;
            this.empty = empty;
            this.hidden = hidden;
            setEnabled(!hidden);
            invalidate();
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    void setLive(boolean live) {
        if (live != this.live) {
            this.live = live;
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
        if (hidden) {
            return;
        }
        float w = getWidth(), h = getHeight();
        float inset = 3 * density;
        float radius = 10 * density;
        rect.set(inset, inset, w - inset, h - inset);

        int bg = (empty ? EMPTY_GREY : bgColor) & 0xFFFFFF;
        fill.setColor(active && thumb == null ? lighten(bg, 0.22f) : Color.rgb((bg >> 16) & 0xFF, (bg >> 8) & 0xFF, bg & 0xFF));
        canvas.drawRoundRect(rect, radius, radius, fill);

        boolean hasBottom = bottom != null && !bottom.isEmpty();
        float bandTop = rect.bottom - rect.height() * 0.3f;
        if (thumb != null || hasBottom) {
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (thumb != null) {
                fill.setColor(0xFF000000);
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, fill);
                // Whole picture, fitted into the space above the name band.
                RectF area = new RectF(rect.left, rect.top, rect.right, hasBottom ? bandTop : rect.bottom);
                drawFit(canvas, thumb, area);
            }
            if (hasBottom) {
                fill.setColor(0xB0000000);
                canvas.drawRect(rect.left, bandTop, rect.right, rect.bottom, fill);
            }
            canvas.restore();
        }

        if (active) {
            float bw = 4 * density;
            border.setStrokeWidth(bw);
            border.setColor(thumb == null && isLight(bg) ? Color.BLACK : Color.WHITE);
            RectF r2 = new RectF(rect.left + bw / 2, rect.top + bw / 2, rect.right - bw / 2, rect.bottom - bw / 2);
            canvas.drawRoundRect(r2, radius, radius, border);
        }

        if (SystemClock.uptimeMillis() < flashUntil) {
            fill.setColor(0x66FFFFFF);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }

        int fgColor = Color.rgb((btn.fg >> 16) & 0xFF, (btn.fg >> 8) & 0xFF, btn.fg & 0xFF);
        if (empty) {
            fgColor = 0x66FFFFFF;
        }
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

        if (corner != null && (thumb != null || hasBottom)) {
            // Place with thumbnail and/or name: number in the corner, name along the bottom,
            // or the number large in the space above the name when there's no thumbnail.
            if (thumb != null) {
                drawCornerNumber(canvas, corner);
                if (live) {
                    drawLiveTag(canvas);
                }
            } else {
                RectF upper = new RectF(rect.left, rect.top, rect.right, bandTop);
                drawLabel(canvas, new String[]{corner}, upper, fgColor, 0.5f);
            }
            if (hasBottom) {
                RectF band = new RectF(rect.left + 4 * density, bandTop, rect.right - 4 * density, rect.bottom);
                drawSingleLine(canvas, bottom, band, 0xFFFFFFFF);
            }
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        drawLabel(canvas, text.split("\n"), rect, fgColor, 0.42f);
    }

    private void drawFit(Canvas canvas, Bitmap bmp, RectF area) {
        float bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        float scale = Math.min(area.width() / bw, area.height() / bh);
        float w = bw * scale, h = bh * scale;
        RectF dst = new RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2);
        canvas.drawBitmap(bmp, new Rect(0, 0, (int) bw, (int) bh), dst, bitmapPaint);
    }

    private void drawCentreCrop(Canvas canvas, Bitmap bmp, RectF dst) {
        float bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) {
            return;
        }
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float sw = dst.width() / scale, sh = dst.height() / scale;
        int left = Math.round((bw - sw) / 2), top = Math.round((bh - sh) / 2);
        Rect src = new Rect(left, top, Math.round(left + sw), Math.round(top + sh));
        canvas.drawBitmap(bmp, src, dst, bitmapPaint);
    }

    /** Small red "LIVE" tag in the top-right corner. */
    private void drawLiveTag(Canvas canvas) {
        String tag = "LIVE";
        textPaint.setTextSize(Math.min(rect.height() * 0.14f, 11 * density));
        float tw = textPaint.measureText(tag);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float th = fm.descent - fm.ascent;
        float pad = 4 * density;
        RectF pill = new RectF(rect.right - pad - tw - 2 * pad, rect.top + pad, rect.right - pad, rect.top + pad + th + pad * 0.5f);
        fill.setColor(0xE0D32F2F);
        canvas.drawRoundRect(pill, 5 * density, 5 * density, fill);
        textPaint.setColor(0xFFFFFFFF);
        canvas.drawText(tag, pill.centerX(), pill.top + pad * 0.25f - fm.ascent, textPaint);
    }

    private void drawCornerNumber(Canvas canvas, String number) {
        textPaint.setTextSize(Math.min(rect.height() * 0.22f, 18 * density));
        float tw = textPaint.measureText(number);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float th = fm.descent - fm.ascent;
        float pad = 4 * density;
        RectF pill = new RectF(rect.left + pad, rect.top + pad, rect.left + pad + tw + 2 * pad, rect.top + pad + th + pad * 0.6f);
        fill.setColor(0xB0000000);
        canvas.drawRoundRect(pill, 6 * density, 6 * density, fill);
        textPaint.setColor(0xFFFFFFFF);
        canvas.drawText(number, pill.centerX(), pill.top + pad * 0.3f - fm.ascent, textPaint);
    }

    /** One line, shrunk to fit and cut with an ellipsis if it is still too long. */
    private void drawSingleLine(Canvas canvas, String s, RectF area, int color) {
        float size = Math.min(area.height() * 0.62f, 16 * density);
        textPaint.setTextSize(size);
        float maxW = area.width();
        float tw = textPaint.measureText(s);
        if (tw > maxW) {
            size = Math.max(9 * density, size * maxW / tw);
            textPaint.setTextSize(size);
        }
        String out = s;
        while (out.length() > 1 && textPaint.measureText(out) > maxW) {
            out = out.substring(0, out.length() - 2) + "…";
        }
        textPaint.setColor(color);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float y = area.centerY() - (fm.descent + fm.ascent) / 2;
        canvas.drawText(out, area.centerX(), y, textPaint);
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
