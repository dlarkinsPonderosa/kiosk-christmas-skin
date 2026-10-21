// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.christmas;

/**
 * How far a mouth is open while answering, judged against the answer's own
 * recent loudness rather than a fixed scale. Rapid speech never lets the level
 * fall far between words, so a fixed scale left the mouth hanging open; here a
 * floor follows the quiet between words and a peak follows the loud ones, and
 * the mouth opens across the gap between them. A little of the plain level is
 * kept so louder answers still open wider.
 */
final class Mouth {
    private float floor, peak;
    private long last;

    /** @return 0 to 1 */
    float open(float level, boolean answering, long now) {
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        if (!answering) {
            // Start each answer fresh.
            floor = 0;
            peak = 0.3f;
            return 0;
        }
        // The floor drops straight to a quiet moment and creeps back up; the peak
        // jumps to a loud one and sinks slowly.
        floor = level < floor ? level : floor + (level - floor) * (1 - (float) Math.exp(-dt / 0.5f));
        peak = level > peak ? level : peak + (level - peak) * (1 - (float) Math.exp(-dt / 1.5f));
        float range = Math.max(0.12f, peak - floor);
        float relative = Math.max(0, Math.min(1, (level - floor) / range));
        return 0.8f * relative + 0.2f * Math.min(1, level * 1.5f);
    }
}
