// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.christmas;

/**
 * Whether a device can redraw the face on every screen refresh, learned from
 * its own frame times, for the Auto frame rate. Kept by SkinController for as
 * long as the plugin runs, so what one turn learns carries into the next: a
 * new overlay view is built for every turn.
 *
 * Only frames while the face is on screen count. After about 45 of them, an
 * average below 30 frames a second caps it at 30. After 3600 capped frames (a
 * minute or two of the face on screen, spread over many turns) it tries full
 * speed again, in case something else was slowing the device. Once it has had
 * to cap twice it stays capped.
 */
final class FrameBudget {
    private boolean slow;
    /** The average time between frames, in seconds. */
    private float frameTime;
    /** Frames seen since the last change. */
    private int frames;
    /** How many times it has capped. */
    private int strikes;

    synchronized boolean slow() {
        return slow;
    }

    /**
     * @param dt seconds since the previous frame
     * @param fresh the first frame after the face was hidden, which says nothing about speed
     */
    synchronized void frame(float dt, boolean fresh) {
        if (fresh) return;
        frameTime = frames == 0 ? dt : frameTime + (dt - frameTime) * 0.05f;
        frames++;
        if (!slow && frames > 45 && frameTime > 1 / 30f) {
            slow = true;
            strikes++;
            frames = 0;
        } else if (slow && frames > 3600 && strikes < 2) {
            slow = false;
            frames = 0;
        }
    }
}
