// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.christmas;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * A snowman's head drawn in code, one frame per call: coal eyes, a carrot
 * nose, a smile of coal dots, a top hat with a holly sprig and a red scarf,
 * with snow falling round him.
 *
 * Resting: a gentle bob and the odd blink. Listening: he leans in, and the
 * user's voice blows the snow about and bumps his hat. Thinking: the snow
 * swirls fast round his head, he looks up and aside, one eye squints and the
 * carrot wiggles. Answering: the thin line joining his coal smile opens into
 * a black mouth, every other dot riding the lower lip down like a jaw.
 */
final class Snowman {
    private static final float[] KNOBS = {1, 0.86f, 1.05f, 0.92f, 1.1f, 0.95f, 0.88f, 1.04f, 0.97f};
    private static final int COAL = 0xFF18181E;
    private static final int FLAKES = 90, FLAKES_SMALL = 16;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path band = new Path();
    private final Path tail = new Path();
    private final Path crown = new Path();
    private final Matrix place = new Matrix();
    private final RectF oval = new RectF();

    private float lean, ponder, open, hop, swirl;
    private long last;
    private float headCx = Float.NaN, headCy, headR;

    private float[] fx, fy, fs, fv, fph;
    private int flakeW, flakeH;

    Snowman() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        snow.setColor(0xD9FFFFFF);
    }

    /** The night sky behind him: dark blue, a little lighter toward the bottom. */
    static void drawSky(Canvas canvas, int width, int height, float opacity, Paint paint) {
        int a = Math.round(255 * opacity);
        paint.setShader(new LinearGradient(0, 0, 0, height, (a << 24) | 0x050B1C, (a << 24) | 0x16294A, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
    }

    /**
     * @param cx0 the centre of the face's square
     * @param size the face's square; the snow covers the whole canvas
     * @param level the eased voice level, 0 to 1
     * @param phase listening, thinking, answering or resting
     * @param small the docked bubble's face
     * @param night moonlight behind him, for the Snowy night background
     */
    void draw(Canvas canvas, int width, int height, float cx0, float cy0, float size, float level, String phase, long now, boolean small, boolean night) {
        float dt = last == 0 || now - last > 250 ? 0 : (now - last) / 1000f;
        last = now;
        boolean listening = "listening".equals(phase);
        boolean thinking = "thinking".equals(phase);
        boolean answering = "answering".equals(phase);
        lean += ((listening ? 1 : 0) - lean) * 0.06f;
        ponder += ((thinking ? 1 : 0) - ponder) * 0.08f;
        open += ((answering ? Math.min(1, level * 1.5f) : 0) - open) * 0.4f;
        swirl += ((thinking ? 1 : 0) - swirl) * 0.07f;

        float r = size * (small ? 0.3f : 0.325f);
        float cx = cx0, cy = cy0 + 0.25f * r;
        // Loud syllables bump the hat up off his head.
        hop += ((answering || listening ? level * 0.12f * r : 0) - hop) * 0.35f;

        if (night) {
            fill.setShader(new RadialGradient(cx, cy - 0.2f * r, 2.4f * r, 0x3896B9FF, 0x0096B9FF, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy - 0.2f * r, 2.4f * r, fill);
            fill.setShader(null);
        }

        moveSnow(width, height, cx, cy, r, size, level, listening, now, dt, small);
        drawSnow(canvas, false);

        float bt = (now % 5300) / 1000f;
        float blink = bt < 0.16f ? (float) Math.sin(bt / 0.16f * Math.PI) : 0;
        place.reset();
        place.postScale(1 + 0.2f * lean, 1 + 0.2f * lean, cx, cy);
        place.postRotate(2f * (float) Math.sin(now / 1700.0) + 7f * lean - 5f * ponder, cx, cy + r);
        place.postTranslate(0, r * 0.03f * (float) Math.sin(now / 1100.0));
        canvas.save();
        canvas.concat(place);

        // Head, lit from the upper left.
        if (cx != headCx || cy != headCy || r != headR) {
            head.setShader(new RadialGradient(cx - 0.25f * r, cy - 0.3f * r, 1.35f * r,
                new int[] {0xFFFFFFFF, 0xFFEEF4FB, 0xFFC9D6E8, 0xFF9DB0CB}, new float[] {0, 0.5f, 0.85f, 1}, Shader.TileMode.CLAMP));
            headCx = cx;
            headCy = cy;
            headR = r;
        }
        canvas.drawCircle(cx, cy, r, head);

        float glance = ponder * r * (0.08f + 0.02f * (float) Math.sin(now / 700.0));
        float lift = ponder * r * (0.05f + 0.012f * (float) Math.sin(now / 1100.0));

        // Coal eyes. The right one squints while thinking.
        for (int side = -1; side <= 1; side += 2) {
            float tall = (1 - 0.9f * blink) * (side == 1 ? 1 - 0.55f * ponder : 1 + 0.06f * ponder);
            float ex = cx + glance + side * 0.3f * r, ey = cy - 0.25f * r - lift;
            float er = 0.085f * r * (1 + 0.12f * lean);
            lump(canvas, ex, ey, er, tall, side == 1 ? 2 : 5);
            if (tall > 0.4f) {
                fill.setColor(0x8CFFFFFF);
                canvas.drawCircle(ex - 0.3f * er, ey - 0.35f * er * tall, 0.22f * er, fill);
            }
        }

        drawMouth(canvas, cx, cy, r, glance);
        drawCarrot(canvas, cx + glance * 0.5f, cy + 0.03f * r, r, now);
        drawScarf(canvas, cx, cy, r, now);
        drawHat(canvas, cx, cy, r);
        canvas.restore();
        drawSnow(canvas, true);
    }

    /**
     * A smile of coal dots joined by a thin black line. Talking, the line
     * opens into a black mouth: every other dot rides the lower lip down, so
     * it opens like a jaw.
     */
    private void drawMouth(Canvas canvas, float cx, float cy, float r, float glance) {
        float half = 0.4f * r * (1 - 0.3f * ponder);
        float mx = cx + glance * 0.3f + 0.06f * r * ponder, base = cy + 0.36f * r;
        float depth = 0.13f * r * (1 - 0.7f * ponder);
        path.reset();
        path.moveTo(mx - half, lip(-1, base, depth, r, false));
        for (int i = -19; i <= 20; i++) path.lineTo(mx + i / 20f * half, lip(i / 20f, base, depth, r, false));
        for (int i = 19; i >= -20; i--) path.lineTo(mx + i / 20f * half, lip(i / 20f, base, depth, r, true));
        path.close();
        fill.setColor(0xFF0E0E13);
        canvas.drawPath(path, fill);
        line.setColor(COAL);
        line.setStrokeWidth(0.014f * r);
        canvas.drawPath(path, line);
        for (int i = 0; i < 7; i++) {
            float u = -1 + i / 3f;
            lump(canvas, mx + u * half, lip(u, base, depth, r, i % 2 == 1), 0.04f * r * (1 - 0.25f * Math.abs(u)), 1, i);
        }
    }

    private float lip(float u, float base, float depth, float r, boolean lower) {
        float shape = (float) Math.sqrt(Math.max(0, 1 - u * u));
        return base + depth * (1 - u * u) + (lower ? open * 0.17f * r * shape : -open * 0.04f * r * shape);
    }

    /** A carrot pointing off to one side. It wiggles while he thinks. */
    private void drawCarrot(Canvas canvas, float nx, float ny, float r, long now) {
        double ang = 0.35 + ponder * 0.18 * Math.sin(now / 110.0) + 0.05 * Math.sin(now / 900.0);
        float len = 0.45f * r * (1 + 0.08f * lean), w = 0.075f * r;
        canvas.save();
        canvas.translate(nx, ny);
        canvas.rotate((float) Math.toDegrees(ang));
        path.reset();
        path.moveTo(0, -w);
        path.quadTo(len * 0.5f, -w * 0.6f, len, 0.02f * r);
        path.quadTo(len * 0.5f, w * 0.75f, 0, w);
        path.quadTo(-w * 0.5f, 0, 0, -w);
        fill.setShader(new LinearGradient(0, -w, 0, w, new int[] {0xFFFFAD4A, 0xFFF07A17, 0xFFB9500A}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        line.setColor(0x80783200);
        line.setStrokeWidth(0.012f * r);
        for (float k : new float[] {0.22f, 0.42f, 0.62f}) {
            canvas.drawLine(len * k, -w * (1 - k) * 0.85f, len * k + 0.025f * r, -w * (1 - k) * 0.2f, line);
        }
        canvas.restore();
    }

    /** A red scarf with green stripes round his neck, its tail hanging on the right. */
    private void drawScarf(Canvas canvas, float cx, float cy, float r, long now) {
        band.reset();
        band.moveTo(cx - 0.8f * r, cy + 0.6f * r);
        band.quadTo(cx, cy + 0.95f * r, cx + 0.8f * r, cy + 0.6f * r);
        band.lineTo(cx + 0.84f * r, cy + 0.86f * r);
        band.quadTo(cx, cy + 1.22f * r, cx - 0.84f * r, cy + 0.86f * r);
        band.close();
        float sway = 0.03f * r * (float) Math.sin(now / 600.0);
        tail.reset();
        tail.moveTo(cx + 0.36f * r, cy + 0.92f * r);
        tail.lineTo(cx + 0.58f * r, cy + 0.88f * r);
        tail.lineTo(cx + 0.66f * r + sway, cy + 1.55f * r);
        tail.lineTo(cx + 0.44f * r + sway, cy + 1.57f * r);
        tail.close();
        for (Path p : new Path[] {tail, band}) {
            fill.setColor(0xFFC21D2C);
            canvas.drawPath(p, fill);
            canvas.save();
            canvas.clipPath(p);
            line.setColor(0xFF2E7D4F);
            line.setStrokeWidth(0.045f * r);
            path.reset();
            path.moveTo(cx - 0.9f * r, cy + 0.75f * r);
            path.quadTo(cx, cy + 1.12f * r, cx + 0.9f * r, cy + 0.75f * r);
            canvas.drawPath(path, line);
            canvas.drawLine(cx + 0.3f * r, cy + 1.2f * r, cx + 0.8f * r, cy + 1.18f * r, line);
            canvas.drawLine(cx + 0.3f * r, cy + 1.38f * r, cx + 0.8f * r, cy + 1.36f * r, line);
            fill.setColor(0x2E000000);
            canvas.drawRect(cx - r, cy + 0.6f * r, cx + r, cy + 0.66f * r, fill);
            canvas.restore();
        }
    }

    /** A top hat, tipped back, with a holly sprig in its red band. Hops with loud syllables. */
    private void drawHat(Canvas canvas, float cx, float cy, float r) {
        canvas.save();
        canvas.translate(cx + 0.08f * r, cy - 0.8f * r - hop);
        canvas.rotate((float) Math.toDegrees(-0.14f - 0.6f * hop / r));
        crown.reset();
        crown.moveTo(-0.3f * r, 0);
        crown.lineTo(-0.27f * r, -0.66f * r);
        crown.quadTo(0, -0.7f * r, 0.27f * r, -0.66f * r);
        crown.lineTo(0.3f * r, 0);
        crown.close();
        shade.setShader(new LinearGradient(-0.3f * r, 0, 0.3f * r, 0, new int[] {0xFF3A3A46, 0xFF17171D, 0xFF0B0B0F}, new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP));
        canvas.drawPath(crown, shade);
        canvas.save();
        canvas.clipPath(crown);
        fill.setColor(0xFFB0142A);
        canvas.drawRect(-0.32f * r, -0.15f * r, 0.32f * r, -0.03f * r, fill);
        canvas.restore();
        fill.setColor(0xFF2F7D3A);
        for (float a : new float[] {-0.6f, 0.5f}) {
            float lx = 0.14f * r + (float) Math.cos(a) * 0.05f * r, ly = -0.1f * r + (float) Math.sin(a) * 0.03f * r;
            canvas.save();
            canvas.rotate((float) Math.toDegrees(a), lx, ly);
            oval.set(lx - 0.07f * r, ly - 0.028f * r, lx + 0.07f * r, ly + 0.028f * r);
            canvas.drawOval(oval, fill);
            canvas.restore();
        }
        fill.setColor(0xFFE3263A);
        canvas.drawCircle(0.1f * r, -0.09f * r, 0.022f * r, fill);
        canvas.drawCircle(0.135f * r, -0.065f * r, 0.022f * r, fill);
        canvas.drawCircle(0.15f * r, -0.105f * r, 0.022f * r, fill);
        fill.setColor(0xFF111117);
        oval.set(-0.55f * r, -0.085f * r, 0.55f * r, 0.085f * r);
        canvas.drawOval(oval, fill);
        line.setColor(0x1FFFFFFF);
        line.setStrokeWidth(0.01f * r);
        oval.set(-0.52f * r, -0.075f * r, 0.52f * r, 0.065f * r);
        canvas.drawArc(oval, 189, 162, false, line);
        canvas.restore();
    }

    /** A lump of coal: a knobbly round shape. */
    private void lump(Canvas canvas, float x, float y, float rad, float tall, int k) {
        path.reset();
        for (int i = 0; i < 9; i++) {
            double a = i / 9.0 * Math.PI * 2 + k;
            float rr = rad * KNOBS[(i + k * 3) % 9];
            float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr * tall;
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        fill.setColor(COAL);
        canvas.drawPath(path, fill);
        line.setColor(COAL);
        line.setStrokeWidth(rad * 0.25f);
        canvas.drawPath(path, line);
    }

    /** Snow drifts down; the voice gusts it sideways while listening; thinking swirls it round his head. */
    private void moveSnow(int width, int height, float cx, float cy, float r, float size, float level, boolean listening, long now, float dt, boolean small) {
        if (fx == null || flakeW != width || flakeH != height) {
            int n = small ? FLAKES_SMALL : FLAKES;
            fx = new float[n];
            fy = new float[n];
            fs = new float[n];
            fv = new float[n];
            fph = new float[n];
            for (int i = 0; i < n; i++) {
                fx[i] = seed(i * 3 + 1) * width;
                fy[i] = seed(i * 3 + 2) * height;
                fs[i] = size * (0.004f + 0.008f * seed(i * 3 + 3));
                fv[i] = size * (0.05f + 0.08f * seed(i * 7 + 5));
                fph[i] = seed(i * 5) * 10;
            }
            flakeW = width;
            flakeH = height;
        }
        float gust = listening ? level * size * 0.5f * (float) Math.sin(now / 400.0) : 0;
        for (int i = 0; i < fx.length; i++) {
            float vx = (float) Math.sin(now / 1300.0 + fph[i]) * fv[i] * 0.3f + gust, vy = fv[i];
            if (swirl > 0.01f) {
                float dx = fx[i] - cx, dy = fy[i] - cy, d = (float) Math.hypot(dx, dy) + 1;
                float vt = swirl * size * 2.4f / (1 + d / (1.5f * r));
                vx += -dy / d * vt;
                vy += dx / d * vt - fv[i] * swirl;
            }
            fx[i] += vx * dt;
            fy[i] += vy * dt;
            if (fy[i] > height + 5) {
                fy[i] = -5;
                fx[i] = (float) Math.random() * width;
            }
            if (fy[i] < -10) fy[i] = height + 4;
            if (fx[i] < -5) fx[i] += width + 10;
            else if (fx[i] > width + 5) fx[i] -= width + 10;
        }
    }

    /** Half the flakes fall behind him, half in front, a little larger. */
    private void drawSnow(Canvas canvas, boolean front) {
        for (int i = front ? 0 : 1; i < fx.length; i += 2) canvas.drawCircle(fx[i], fy[i], fs[i] * (front ? 1.2f : 0.8f), snow);
    }

    private static float seed(int i) {
        double x = Math.sin(i * 12.9898 + 4.1) * 43758.5453;
        return (float) (x - Math.floor(x));
    }
}
