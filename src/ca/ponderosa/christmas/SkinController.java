// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.christmas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Draws the Voice Satellite turn while this plugin's skin is picked. Follows
 * the voice skin contract: draw only while selected, follow voice.overlay for
 * when to show, and honour the Appearance settings (Background, Text size,
 * Reactive activity bar). Both faces are made for a dark screen, so Theme
 * does not change them.
 */
final class SkinController {
    static final String KEY = "christmas_lights_snowman";
    /** Where the docked bubble sits above the bottom edge, in dp. */
    static final int DOCK_INSET = 24;
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.level", "voice.text", "voice.appearance"};

    private PluginHost host;
    private int generation;
    private boolean selected;
    private boolean skinHeard;
    private boolean appearanceHeard;
    private String overlay = "hidden";
    private String drawn;

    // Read by the view on the main thread.
    volatile float level;
    volatile String state = "idle";
    volatile String command = "";
    volatile String answer = "";
    volatile String tool = "";
    /** Null for Skin default, which is solid. */
    volatile Float background;
    volatile float textScale = 1;
    volatile boolean reactive = true;
    /** The Face setting: the snowman instead of the lights. */
    volatile boolean snowman;
    volatile boolean warm;
    volatile boolean glow = true;
    /** The snowman's Snowy night background, as opposed to Black. */
    volatile boolean night = true;
    /** The Frame rate setting: Auto, Full or 30 frames a second. */
    volatile String frameRate = "Auto";
    /** The showing view. Touched on the main thread only. */
    private SkinView view;
    /** What each look has learned about this device's speed. Kept across turns: each turn builds a new view. */
    private final FrameBudget fullBudget = new FrameBudget();
    private final FrameBudget dockedBudget = new FrameBudget();

    synchronized void start(PluginHost host) {
        this.host = host;
        int current = ++generation;
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            host.status("This Kiosk Satellite version has no voice skins.", true);
            return;
        }
        try {
            host.subscribe("voice.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the face guesses the phase from the words.
        }
        read(current);
    }

    private void read(int current) {
        // Events win over these reads: they are newer.
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> voice = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(voice.get("skinSelected"));
                if ("hidden".equals(overlay) && voice.get("overlay") instanceof String) overlay = (String) voice.get("overlay");
                if (voice.get("state") instanceof String) state = (String) voice.get("state");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    synchronized void configure(Map<String, Object> settings) {
        snowman = "Snowman".equals(settings.get("face"));
        warm = "Warm white".equals(settings.get("bulbs"));
        glow = !Boolean.FALSE.equals(settings.get("glow"));
        night = !"Black".equals(settings.get("snowmanBackground"));
        Object chosen = settings.get("frameRate");
        frameRate = chosen instanceof String ? (String) chosen : "Auto";
        restyleLater();
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("ks.voice.skin".equals(event)) {
            selected = Boolean.TRUE.equals(payload.get("selected"));
            skinHeard = true;
        } else if ("ks.voice.overlay".equals(event)) {
            overlay = String.valueOf(payload.get("overlay"));
        } else if ("ks.voice.level".equals(event)) {
            Object value = payload.get("level");
            level = value instanceof Number ? ((Number) value).floatValue() : 0;
            return;
        } else if ("ks.voice.state".equals(event)) {
            Object value = payload.get("state");
            state = value instanceof String ? (String) value : "idle";
            return;
        } else if ("ks.voice.text".equals(event)) {
            words(payload);
            return;
        } else if ("ks.voice.appearance".equals(event)) {
            appearanceHeard = true;
            appearance(payload);
            return;
        } else {
            return;
        }
        update();
    }

    /** Which part of the turn the face is in: listening, thinking, answering or resting. */
    String phase() {
        String now = state;
        if ("listening".equals(now)) return "listening";
        if ("processing".equals(now)) return "thinking";
        if ("responding".equals(now)) return "answering";
        // No voice.state, or the answer stays up after it is spoken: guess from the words.
        if (!command.isEmpty() && answer.isEmpty() && tool.isEmpty()) return "thinking";
        return "resting";
    }

    private void appearance(Map<?, ?> settings) {
        background = settings.get("background") instanceof Number ? ((Number) settings.get("background")).floatValue() : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        restyleLater();
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? (String) text.get("command") : "";
        answer = text.get("answer") instanceof String ? (String) text.get("answer") : "";
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    private void restyleLater() {
        SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    /** Shows the skin while it is picked and the overlay is up, in the look that matches it. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, 300), new Skin(false));
        } else {
            // A bubble at the bottom. Touches reach the dashboard around and under it.
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, 250), new Skin(true));
        }
    }

    /** Transitions, or none on Kiosk Satellite versions without them. */
    private static OverlaySpec animated(OverlaySpec spec, String transition, int ms) {
        try {
            return spec.enter(transition).exit(transition).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = false;
        skinHeard = false;
        appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        level = 0;
        state = "idle";
        command = answer = tool = "";
        background = null;
        textScale = 1;
        reactive = true;
    }

    private final class Skin implements OverlayFactory {
        private final boolean docked;

        Skin(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((SkinView) view).restyle(theme);
        }

        @Override public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /**
     * Full screen: the face drawn across the whole screen (its cords and snow
     * reach the edges), centred on an empty square above the conversation.
     * Docked: a card with a small face beside the words.
     */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final FaceView face;
        /** Full screen only: the empty square in the column that the face centres on. */
        private final View spot;
        private final TextView said;
        private final TextView answered;
        private final LinearLayout card;
        private KsTheme ksTheme;

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.ksTheme = theme;
            this.docked = docked;
            face = new FaceView(context, docked);
            said = new TextView(context);
            answered = new TextView(context);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            if (docked) {
                spot = null;
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int faceSize = theme.px(80);
                card.addView(face, new LinearLayout.LayoutParams(faceSize, faceSize));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                answered.setMaxLines(3);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said);
                words.addView(answered);
                LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                wordsParams.leftMargin = theme.px(16);
                card.addView(words, wordsParams);
                int width = Math.min(theme.px(560), screenWidth - theme.px(48));
                addView(card, new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT));
            } else {
                card = null;
                addView(face, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                said.setGravity(Gravity.CENTER);
                answered.setGravity(Gravity.CENTER);
                // The words sit over snow and glow, so give them a shadow to stay readable.
                float blur = theme.px(6);
                said.setShadowLayer(blur, 0, theme.px(1), 0xCC000000);
                answered.setShadowLayer(blur, 0, theme.px(1), 0xCC000000);
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setGravity(Gravity.CENTER);
                int size = Math.round(Math.min(screenWidth, screenHeight) * 0.72f);
                spot = new View(context);
                column.addView(spot, new LinearLayout.LayoutParams(size, size));
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                line.topMargin = theme.px(8);
                column.addView(said, line);
                LinearLayout.LayoutParams answerLine = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                answerLine.topMargin = theme.px(8);
                column.addView(answered, answerLine);
                int side = theme.px(48);
                column.setPadding(side, 0, side, 0);
                addView(column, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            restyle();
            refresh();
        }

        void restyle(KsTheme next) {
            ksTheme = next;
            restyle();
        }

        void restyle() {
            float scale = textScale;
            if (docked) {
                GradientDrawable bubble = new GradientDrawable();
                bubble.setColor(0xFF000000);
                bubble.setCornerRadius(ksTheme.px(ksTheme.radiusCard()));
                bubble.setStroke(Math.max(1, ksTheme.px(1) / 2), 0x73C8202F);
                card.setBackground(bubble);
                int pad = ksTheme.px(12);
                card.setPadding(pad, pad, ksTheme.px(20), pad);
                ksTheme.styleText(said, 14 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 17 * scale, 500, "onSurface");
            } else {
                // The face view paints the backdrop itself, so the night sky can fill the screen.
                setBackgroundColor(Color.TRANSPARENT);
                ksTheme.styleText(said, 20 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 28 * scale, 500, "onSurface");
            }
            // Both faces are dark whatever the theme.
            said.setTextColor(0xFFB4BDB9);
            answered.setTextColor(0xFFF1EFE8);
            face.invalidate();
        }

        void refresh() {
            said.setText(command);
            said.setVisibility(command.isEmpty() ? GONE : VISIBLE);
            answered.setText(answer.isEmpty() ? tool : answer);
            answered.setVisibility(answer.isEmpty() && tool.isEmpty() ? GONE : VISIBLE);
        }

        /**
         * The face, redrawn every frame, or about 30 times a second when the
         * frame rate is capped. Eases toward the latest level so 30 readings a
         * second still move smoothly.
         */
        private final class FaceView extends View {
            private final boolean small;
            private final Lights lights = new Lights();
            private final Snowman snowmanFace = new Snowman();
            private final FrameBudget budget;
            private final Paint backdrop = new Paint();
            private final Path round = new Path();
            private float shown;
            private long last;

            FaceView(Context context, boolean small) {
                super(context);
                this.small = small;
                budget = small ? dockedBudget : fullBudget;
            }

            @Override protected void onDraw(Canvas canvas) {
                long now = SystemClock.uptimeMillis();
                boolean fresh = last == 0 || now - last > 250;
                float dt = fresh ? 0 : (now - last) / 1000f;
                last = now;
                boolean capped;
                if ("Full".equals(frameRate)) {
                    capped = false;
                } else if ("Auto".equals(frameRate)) {
                    budget.frame(dt, fresh);
                    capped = budget.slow();
                } else {
                    capped = true;
                }
                // With Reactive activity bar off nothing follows the voice. KS sends 0 then too.
                shown += ((reactive ? level : 0) - shown) * 0.3f;
                int width = getWidth(), height = getHeight();
                boolean isSnowman = snowman;
                float cx, cy, size;
                canvas.save();
                if (small) {
                    cx = width / 2f;
                    cy = height / 2f;
                    size = Math.min(width, height);
                    // Keep the cords and snow inside the bubble's little square.
                    float corner = size * 0.2f;
                    round.reset();
                    round.addRoundRect(0, 0, width, height, corner, corner, Path.Direction.CW);
                    canvas.clipPath(round);
                    if (isSnowman && night) Snowman.drawSky(canvas, width, height, 1, backdrop);
                } else {
                    if (spot.getWidth() == 0) {
                        canvas.restore();
                        postInvalidateOnAnimation();
                        return;
                    }
                    View column = (View) spot.getParent();
                    cx = column.getLeft() + spot.getLeft() + spot.getWidth() / 2f;
                    cy = column.getTop() + spot.getTop() + spot.getHeight() / 2f;
                    size = spot.getWidth();
                    // Solid by default; the Background setting can let the dashboard show through.
                    Float chosen = background;
                    float opacity = chosen != null ? chosen : 1f;
                    if (isSnowman && night) {
                        Snowman.drawSky(canvas, width, height, opacity, backdrop);
                    } else {
                        backdrop.setColor(Color.argb(Math.round(255 * opacity), 0, 0, 0));
                        canvas.drawRect(0, 0, width, height, backdrop);
                    }
                }
                if (isSnowman) {
                    snowmanFace.draw(canvas, width, height, cx, cy, size, shown, phase(), now, small, night);
                } else {
                    lights.draw(canvas, width, cx, cy, size, shown, phase(), now, small, warm, glow);
                }
                canvas.restore();
                if (capped) postInvalidateDelayed(33);
                else postInvalidateOnAnimation();
            }
        }
    }
}
