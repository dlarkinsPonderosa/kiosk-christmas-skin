// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.christmas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.HashMap;
import java.util.Map;

/**
 * A face strung from old-fashioned C9 Christmas bulbs on a green cord, drawn
 * in code, one frame per call.
 *
 * The eyes are rings of bulbs with a warm white globe for each pupil, the
 * brows are short strings, the nose is a red globe hanging from the cord
 * between the eyes, and the mouth is two strings joined at the corners. Lead
 * cords run off both sides, as if the face hangs from a real string.
 *
 * Resting: the bulbs twinkle and it blinks now and then. Listening: it leans
 * in, raises its brows and the bulbs flash with the user's voice. Thinking:
 * the lights chase round the whole face, the eyes glance up and aside, one
 * squints and one brow goes up. Answering: the lower string drops open with
 * the voice, two buck-tooth bulbs show, and the mouth's bulbs pulse.
 *
 * The glow round each bulb is one small soft image per colour, stretched and
 * added on: no blur, so it is cheap even on slow devices.
 */
final class Lights {
    private static final int[] MULTI = {0xFFFF3B2F, 0xFF2BD35A, 0xFF3A7DFF, 0xFFFFA41C};
    private static final int WARM = 0xFFFFC46B;
    private static final int PUPIL = 0xFFFFF1D0;
    private static final int RUDOLPH = 0xFFFF2A1A;
    private static final int TOOTH = 0xFFFFE2A8;
    private static final int CORD = 0xFF24452B;
    private static final int SOCKET = 0xFF1B3320;
    /** Leads 3 + 3, brows 4 + 4, eyes 9 + 9, mouth 20. */
    private static final int MAX = 64;
    /** The mouth: a corner bulb, nine along the top, a corner, nine along the bottom. Drawn last. */
    private static final int MOUTH_BULBS = 20;
    private static final float[] MOUTH_U = {-0.8f, -0.6f, -0.4f, -0.2f, 0f, 0.2f, 0.4f, 0.6f, 0.8f};
    private static final int RING = 9;

    private final float[] bx = new float[MAX], by = new float[MAX], bdx = new float[MAX], bdy = new float[MAX];
    private final float[] bk = new float[MAX], eased = new float[MAX];
    private int count;
    private boolean easedReady;

    private final Path wires = new Path();
    private final Matrix place = new Matrix();
    private final RectF oval = new RectF();
    private final Paint cord = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint socket = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint globe = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, Bitmap> sprites = new HashMap<>();

    /** 0 to 1: how far into the listening pose. */
    private float lean;
    /** 0 to 1: how far into the thinking pose. */
    private float ponder;
    /** 0 to 1: how far the mouth is open. */
    private float open;

    // The three globes: two pupils and the nose.
    private final float[] gx = new float[3], gy = new float[3], gr = new float[3], gk = new float[3];
    private final int[] gc = new int[3];
    private int globes;

    Lights() {
        cord.setStyle(Paint.Style.STROKE);
        cord.setStrokeCap(Paint.Cap.ROUND);
        cord.setStrokeJoin(Paint.Join.ROUND);
        cord.setColor(CORD);
        socket.setStyle(Paint.Style.STROKE);
        socket.setStrokeCap(Paint.Cap.ROUND);
        socket.setColor(SOCKET);
        glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /**
     * @param cx0 the centre of the face's square
     * @param size the face's square, which the face fills; cords and glow may draw past it
     * @param level the eased voice level, 0 to 1
     * @param phase listening, thinking, answering or resting
     * @param small the docked bubble's face
     * @param warm warm white bulbs instead of multicolour
     * @param glowOn the soft light round each bulb
     */
    void draw(Canvas canvas, int width, float cx0, float cy0, float size, float level, String phase, long now, boolean small, boolean warm, boolean glowOn) {
        boolean listening = "listening".equals(phase);
        boolean thinking = "thinking".equals(phase);
        boolean answering = "answering".equals(phase);
        lean += ((listening ? 1 : 0) - lean) * 0.06f;
        ponder += ((thinking ? 1 : 0) - ponder) * 0.08f;
        // Straight from the level, as on the pumpkin: it is already eased once, and easing it
        // again left the mouth a beat behind the words.
        open = answering ? Math.min(1, level * 1.5f) : listening ? 0.1f : 0;

        // A blink every few seconds: the eye rings squash flat for a moment.
        float bt = (now % 4700) / 1000f;
        float blink = bt < 0.18f ? (float) Math.sin(bt / 0.18f * Math.PI) : 0;

        float r = size * 0.46f;
        float cx = cx0, cy = cy0 + 0.05f * r;
        place.reset();
        place.postScale(1 + 0.45f * lean, 1 + 0.45f * lean, cx, cy);
        place.postRotate(2f * (float) Math.sin(now / 1700.0) + 8f * lean - 5f * ponder, cx, cy + 0.8f * r);
        place.postTranslate(0, r * 0.02f * (float) Math.sin(now / 1100.0));

        float glance = ponder * r * (0.06f + 0.015f * (float) Math.sin(now / 700.0));
        float lift = ponder * r * (0.05f + 0.012f * (float) Math.sin(now / 1100.0));
        count = 0;
        globes = 0;
        wires.reset();

        float ey = cy - 0.18f * r, er = 0.16f * r;
        float exL = cx - 0.36f * r, exR = cx + 0.36f * r;
        // The right eye narrows while thinking; both open a little wider while listening.
        float tallL = (1 - 0.85f * blink) * (1 + 0.05f * ponder) * (1 + 0.08f * lean);
        float tallR = (1 - 0.85f * blink) * (1 - 0.35f * ponder) * (1 + 0.08f * lean);
        // Far enough to run off the side of any screen.
        float reach = Math.max(2.8f * r, width / 2f + 0.3f * r);

        lead(-1, exL, ey, er, cx, r, reach);
        brow(-1, exL, ey, er, tallL, r, glance, lift);
        ring(-1, exL, ey, er, tallL, r, glance, lift);
        // A sagging cord between the eyes, with a red nose hanging from its lowest point.
        float px = exL + er, qx = exR - er, ccy = cy + 0.02f * r;
        wires.moveTo(px, ey);
        wires.quadTo(cx, ccy, qx, ey);
        float topX = 0.25f * px + 0.5f * cx + 0.25f * qx, topY = 0.5f * ey + 0.5f * ccy;
        float nx = cx + glance * 0.5f, ny = cy + 0.08f * r;
        wires.moveTo(topX, topY);
        wires.lineTo(nx, ny - 0.07f * r);
        int nose = globes;
        globe(nx, ny, 0.075f * r, RUDOLPH, 0);
        ring(1, exR, ey, er, tallR, r, glance, lift);
        brow(1, exR, ey, er, tallR, r, glance, lift);
        lead(1, exR, ey, er, cx, r, reach);

        // The mouth: two strings joined at the corners. The lower one drops away as it talks.
        float half = 0.5f * r * (1 - 0.15f * ponder);
        float mx = cx + glance * 0.3f + 0.04f * r * ponder, base = cy + 0.3f * r;
        wires.moveTo(mx - half, upper(-1, base, r));
        for (int i = -9; i <= 10; i++) wires.lineTo(mx + i / 10f * half, upper(i / 10f, base, r));
        for (int i = 9; i >= -10; i--) wires.lineTo(mx + i / 10f * half, lower(i / 10f, base, r));
        int mouthFrom = count;
        bulb(mx - half - 0.01f * r, upper(-1, base, r), -1, 0);
        for (float u : MOUTH_U) bulb(mx + u * half, upper(u, base, r), slope(u, base, r, half, false), -1);
        bulb(mx + half + 0.01f * r, upper(1, base, r), 1, 0);
        for (int i = MOUTH_U.length - 1; i >= 0; i--) {
            float u = MOUTH_U[i];
            bulb(mx + u * half, lower(u, base, r), -slope(u, base, r, half, true), 1);
        }

        // How bright each bulb wants to be in this phase. Thinking runs a chase round the whole
        // face. Thinking, listening flashes and the talking mouth go past full, which burns hotter.
        for (int i = 0; i < count; i++) {
            float s = seed(i);
            float tw = 0.5f + 0.5f * (float) Math.sin(now / (700 + 900 * s) + 20 * s);
            boolean mouth = i >= mouthFrom;
            float k;
            if (thinking) k = 1.25f * (0.15f + 0.85f * (float) Math.pow(0.5 + 0.5 * Math.cos(now / 1000.0 * 9.9 - i * 0.55), 6));
            else if (listening) k = 0.45f + 0.15f * tw + 0.9f * level * (0.55f + 0.45f * (float) Math.sin(i * 1.7 + now / 90.0));
            else if (answering) k = 0.78f + 0.15f * tw + (mouth ? 1.1f * level : 0);
            else k = 0.7f + 0.25f * tw;
            if (!easedReady) eased[i] = k;
            // Listening flashes follow the voice quicker, so they snap on. The talking mouth is
            // not eased at all, so its pulses land with the words.
            eased[i] += (k - eased[i]) * (answering && mouth ? 1 : listening ? 0.55f : 0.3f);
            bk[i] = Math.max(0, Math.min(answering ? 1.45f : 1.25f, eased[i]));
        }
        easedReady = true;
        gk[nose] = thinking ? 0.5f + 0.5f * (float) Math.sin(now / 160.0)
            : 0.8f + 0.15f * (float) Math.sin(now / 900.0) + 0.2f * level;

        float b = r * 0.05f;
        canvas.save();
        canvas.concat(place);
        cord.setStrokeWidth(r * 0.014f);
        canvas.drawPath(wires, cord);

        if (glowOn) {
            for (int i = 0; i < count; i++) {
                int c = warm ? WARM : MULTI[i % 4];
                float k = bk[i];
                float x = bx[i] + bdx[i] * 0.85f * b, y = by[i] + bdy[i] * 0.85f * b;
                float rad = b * 4.5f * (0.6f + 0.4f * k);
                glow(canvas, c, x, y, rad, Math.min(1, k));
                // Above full brightness a tighter second glow, and the talking mouth a white-hot core.
                if (k > 1) glow(canvas, c, x, y, rad * 0.5f, Math.min(1, (k - 1) * 4));
                if (k > 1.25f) glow(canvas, Color.WHITE, x, y, rad * 0.3f, Math.min(1, (k - 1.25f) * 2.5f));
            }
            for (int i = 0; i < globes; i++) glow(canvas, gc[i], gx[i], gy[i], gr[i] * 4, clamp(gk[i]) * 0.9f);
        }

        socket.setStrokeWidth(b * 0.45f);
        for (int i = 0; i < count; i++) {
            int c = warm ? WARM : MULTI[i % 4];
            float k = bk[i];
            canvas.drawLine(bx[i], by[i], bx[i] + bdx[i] * 0.4f * b, by[i] + bdy[i] * 0.4f * b, socket);
            // The glass: a C9-shaped oval pointing away from the cord, with a hot centre.
            float x = bx[i] + bdx[i] * 0.95f * b, y = by[i] + bdy[i] * 0.95f * b;
            float deg = (float) Math.toDegrees(Math.atan2(bdy[i], bdx[i]));
            canvas.save();
            canvas.rotate(deg, x, y);
            glass.setColor(mix(dim(c, 0.22f), c, k));
            oval.set(x - 0.66f * b, y - 0.42f * b, x + 0.66f * b, y + 0.42f * b);
            canvas.drawOval(oval, glass);
            glass.setColor(mix(c, Color.WHITE, 0.7f));
            glass.setAlpha(Math.round(255 * Math.min(1, 0.85f * k)));
            float hx = x - 0.12f * b;
            oval.set(hx - 0.32f * b, y - 0.18f * b, hx + 0.32f * b, y + 0.18f * b);
            canvas.drawOval(oval, glass);
            canvas.restore();
        }

        // Two buck teeth: white bulbs hanging from the top string, showing only as the mouth opens.
        float show = clamp((open - 0.12f) * 4);
        if (show > 0) {
            for (float u : new float[] {-0.11f, 0.11f}) {
                float tx = mx + u * half, ty = upper(u, base, r);
                // Grows down out of the string as it fades in. Fatter than the others, like a tooth.
                float s = 0.6f + 0.4f * show, len = b * s;
                if (glowOn) glow(canvas, TOOTH, tx, ty + len, b * 3, 0.8f * show);
                socket.setAlpha(Math.round(255 * show));
                socket.setStrokeWidth(b * 0.5f);
                canvas.drawLine(tx, ty, tx, ty + 0.4f * len, socket);
                socket.setAlpha(255);
                // Lit like the other bulbs; only the glow is turned down.
                glass.setColor(mix(dim(TOOTH, 0.22f), TOOTH, 0.85f));
                glass.setAlpha(Math.round(255 * show));
                oval.set(tx - 0.5f * b * s, ty + 1.05f * len - 0.75f * len, tx + 0.5f * b * s, ty + 1.05f * len + 0.75f * len);
                canvas.drawOval(oval, glass);
                glass.setColor(Color.WHITE);
                glass.setAlpha(Math.round(255 * 0.75f * show));
                oval.set(tx - 0.24f * b * s, ty + 0.95f * len - 0.4f * len, tx + 0.24f * b * s, ty + 0.95f * len + 0.4f * len);
                canvas.drawOval(oval, glass);
            }
        }

        for (int i = 0; i < globes; i++) {
            float k = clamp(gk[i]);
            if (i == nose) {
                glass.setColor(SOCKET);
                canvas.drawRect(gx[i] - 0.03f * r, gy[i] - gr[i] - 0.03f * r, gx[i] + 0.03f * r, gy[i] - gr[i] + 0.01f * r, glass);
            }
            globe.setShader(new RadialGradient(gx[i] - 0.3f * gr[i], gy[i] - 0.3f * gr[i], gr[i] * 1.3f,
                mix(gc[i], Color.WHITE, 0.6f * k), mix(dim(gc[i], 0.25f), gc[i], k), Shader.TileMode.CLAMP));
            canvas.drawCircle(gx[i], gy[i], gr[i], globe);
        }
        canvas.restore();
    }

    /** A lead cord from the outer side of an eye, off the edge of the screen, with three bulbs on it. */
    private void lead(int side, float ex, float ey, float er, float cx, float r, float reach) {
        float px = ex + side * er, py = ey;
        float qx = cx + side * reach, qy = ey - 0.9f * r;
        float cxp = (px + qx) / 2, cyp = Math.max(py, qy) + 0.3f * r;
        wires.moveTo(px, py);
        wires.quadTo(cxp, cyp, qx, qy);
        float[] ts = side < 0 ? new float[] {0.85f, 0.6f, 0.35f} : new float[] {0.35f, 0.6f, 0.85f};
        for (float t : ts) {
            float a = 1 - t;
            float x = a * a * px + 2 * a * t * cxp + t * t * qx;
            float y = a * a * py + 2 * a * t * cyp + t * t * qy;
            float tx = 2 * a * (cxp - px) + 2 * t * (qx - cxp);
            float ty = 2 * a * (cyp - py) + 2 * t * (qy - cyp);
            bulb(x, y, -ty * side, tx * side);
        }
    }

    /** Four bulbs on a short string above the eye. Listening raises both; thinking raises the right one and tips it. */
    private void brow(int side, float ex, float ey, float er, float tall, float r, float glance, float lift) {
        float browLift = 0.05f * r * lean + (side == 1 ? 0.07f * r * ponder : -0.01f * r * ponder);
        double a = side * 0.15 * (1 - ponder) + (side == 1 ? -0.22 * ponder : 0.08 * ponder);
        float x0 = ex + glance * 0.8f, y0 = ey - er * tall - 0.16f * r - browLift - lift;
        float ca = (float) Math.cos(a), sa = (float) Math.sin(a), len = 0.13f * r;
        wires.moveTo(x0 - ca * len * 1.4f, y0 - sa * len * 1.4f);
        wires.quadTo(x0, y0 + 0.02f * r, x0 + ca * len * 1.4f, y0 + sa * len * 1.4f);
        for (float s : new float[] {-1, -1 / 3f, 1 / 3f, 1}) bulb(x0 + ca * len * s, y0 + sa * len * s + 0.01f * r * (1 - s * s), sa, -ca);
    }

    /** An eye: a ring of bulbs pointing outward, and a warm white pupil that glances while thinking. */
    private void ring(int side, float ex, float ey, float er, float tall, float r, float glance, float lift) {
        oval.set(ex - er, ey - Math.max(0.5f, er * tall), ex + er, ey + Math.max(0.5f, er * tall));
        wires.addOval(oval, Path.Direction.CW);
        for (int i = 0; i < RING; i++) {
            double th = -Math.PI / 2 - side * i * Math.PI * 2 / RING;
            float c = (float) Math.cos(th), s = (float) Math.sin(th);
            bulb(ex + er * c, ey + er * tall * s, tall * c, s);
        }
        if (tall > 0.3f) globe(ex + glance, ey - lift * 1.5f, 0.06f * r * Math.min(1, tall), PUPIL, 0.95f);
    }

    private void bulb(float x, float y, float dx, float dy) {
        if (count == MAX) return;
        float d = (float) Math.hypot(dx, dy);
        if (d == 0) d = 1;
        bx[count] = x;
        by[count] = y;
        bdx[count] = dx / d;
        bdy[count] = dy / d;
        count++;
    }

    private void globe(float x, float y, float radius, int color, float k) {
        gx[globes] = x;
        gy[globes] = y;
        gr[globes] = radius;
        gc[globes] = color;
        gk[globes] = k;
        globes++;
    }

    /** Adds one colour's soft glow image, stretched to the given radius. */
    private void glow(Canvas canvas, int color, float x, float y, float radius, float alpha) {
        if (alpha <= 0) return;
        Bitmap image = sprites.get(color);
        if (image == null) {
            image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            int r = Color.red(color), g = Color.green(color), bl = Color.blue(color);
            p.setShader(new RadialGradient(32, 32, 32,
                new int[] {Color.argb(230, r, g, bl), Color.argb(128, r, g, bl), Color.argb(36, r, g, bl), Color.argb(0, r, g, bl)},
                new float[] {0, 0.18f, 0.5f, 1}, Shader.TileMode.CLAMP));
            new Canvas(image).drawRect(0, 0, 64, 64, p);
            sprites.put(color, image);
        }
        glowPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        oval.set(x - radius, y - radius, x + radius, y + radius);
        canvas.drawBitmap(image, null, oval, glowPaint);
    }

    private float upper(float u, float base, float r) {
        return base + 0.1f * r * (1 - u * u) * (1 - 0.6f * ponder);
    }

    private float lower(float u, float base, float r) {
        return upper(u, base, r) + (0.05f * r + 0.3f * r * open) * (float) Math.sqrt(Math.max(0, 1 - u * u));
    }

    /** The string's slope at u, which points each bulb straight out from it. */
    private float slope(float u, float base, float r, float half, boolean bottom) {
        float a = bottom ? lower(u + 0.01f, base, r) : upper(u + 0.01f, base, r);
        float z = bottom ? lower(u - 0.01f, base, r) : upper(u - 0.01f, base, r);
        return (a - z) / (0.02f * half);
    }

    private static float seed(int i) {
        double x = Math.sin(i * 12.9898 + 4.1) * 43758.5453;
        return (float) (x - Math.floor(x));
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int dim(int c, float k) {
        return Color.rgb(Math.round(Color.red(c) * k), Math.round(Color.green(c) * k), Math.round(Color.blue(c) * k));
    }

    private static int mix(int from, int to, float amount) {
        float a = clamp(amount);
        return Color.rgb(
            Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * a),
            Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * a),
            Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a));
    }
}
