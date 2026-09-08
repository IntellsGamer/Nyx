package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.RotationSnapshot;

import java.util.Iterator;

/**
 * Detects modulo-360 yaw snapping: an instant rotation of nearly a full
 * circle whose previous and next rotations are both tiny. Legit players never
 * produce a ~360 degree delta in a single tick (a mouse flick tops out around
 * 150-200 degrees); cheat clients that normalize rotation angles emit exactly
 * this signature.
 *
 * The old implementation compared lastDeltaYaw with itself (both variables
 * read the same field), so its condition could never be true and the check
 * had never flagged anything. This version derives the previous delta from the
 * rotation history so the before/after comparison is real.
 */
@CheckData(name = "AimModulo360", description = "Detects modulo-360 yaw snapping")
public class AimModulo360Check extends Check {

    public AimModulo360Check(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        var history = data.getRotationHistory();
        if (history.size() < 3) return;

        Iterator<RotationSnapshot> it = history.iterator();
        RotationSnapshot current = it.next();
        RotationSnapshot previous = it.next();
        RotationSnapshot beforePrevious = it.next();

        // Work on the wrapped (0..360) distance so a +350 degree turn and a
        // -10 degree turn are correctly told apart.
        float deltaYaw = wrapDegrees(current.yaw() - previous.yaw());
        float previousDeltaYaw = wrapDegrees(previous.yaw() - beforePrevious.yaw());

        // A single-tick ~full-circle snap with calm rotations on both sides.
        // The after-side calm is implied: if the next packet kept spinning,
        // the mod-360 signature would not be a one-tick event.
        if (Math.abs(deltaYaw) > 320 && Math.abs(previousDeltaYaw) < 30) {
            flag(data, String.format("DY:%.1f PDY:%.1f", deltaYaw, previousDeltaYaw));
        }
    }

    /** Maps a raw yaw difference into the [-180, 180] range. */
    private static float wrapDegrees(float delta) {
        float wrapped = delta % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}
