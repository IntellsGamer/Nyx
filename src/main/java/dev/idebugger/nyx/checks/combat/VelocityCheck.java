package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.ServerVelocity;
import org.bukkit.util.Vector;

/**
 * Anti-kinetic (anti-knockback) detection using a buffered server velocity.
 *
 * When the server sends an ENTITY_VELOCITY packet it is buffered in
 * NyxPlayerData (recordServerVelocity). Over the following movement ticks the
 * player is expected to move with horizontal and vertical components derived
 * from that applied velocity (decaying each tick). We track the ratio of
 * observed movement to expected movement and flag when the player consumes
 * far less of the knockback than they should.
 *
 * A positive buffer lets a handful of noisy ticks pass; flags require
 * sustained under-consumption so a single lag spike can't auto-punish.
 */
@CheckData(name = "Velocity", description = "Detects anti-knockback (buffered server velocity)")
public class VelocityCheck extends Check {

    private static final int MAX_BUFFER_TICKS = 10;

    private static final double MIN_HORIZ_RATIO = 0.75;
    private static final double MAX_HORIZ_RATIO = 1.6;

    public VelocityCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        ServerVelocity sv = data.peekServerVelocity();
        if (sv == null) return;
        if (data.isInVehicle()) return;
        if (data.isGliding() || data.isWasGliding()) return;

        Vector applied = sv.vector();
        double expectedHoriz = Math.hypot(applied.getX(), applied.getZ());
        double expectedVert = applied.getY();
        if (expectedHoriz < 0.01 && expectedVert < 0.01) {
            data.clearServerVelocity();
            return;
        }

        data.incrementServerVelocityBufferTicks();

        // The tick the velocity is applied sees friction already reduce motion
        // (0.6 ground / 0.91 air), so skip the accumulation on that first tick
        // to avoid a false positive on legit players.
        if (data.getServerVelocityBufferTicks() <= 1) {
            if (data.getServerVelocityBufferTicks() >= MAX_BUFFER_TICKS) {
                data.clearServerVelocity();
            }
            return;
        }

        double actualHoriz = data.getHorizontalSpeed();
        double actualVert = data.getDeltaY();

        double sensitivity = getSensitivity(data);
        double minHoriz = MIN_HORIZ_RATIO - (1.0 - sensitivity) * 0.15;

        boolean anyHorizontal = false;

        if (expectedHoriz > 0.01) {
            anyHorizontal = true;
            double observed = Math.min(actualHoriz / expectedHoriz, MAX_HORIZ_RATIO);

            // A single under-consumption tick (ground friction is ~0.6, so only
            // ~60% moves on tick 1) is normal; a sustained under-consumption is not.
            if (observed < minHoriz) {
                double weight = sensitivity > 0.85 ? 1.0 : 0.6;
                data.addVelocityBuffer(weight);
            } else {
                data.addVelocityBuffer(-0.35);
            }
        } else {
            data.addVelocityBuffer(-0.5);
        }

        if (expectedVert > 0.05 && !data.isOnGround()) {
            // vertical knockback: actual upward (or reduced downward) compared to expected
            double vertRatio = actualVert / expectedVert;
            if (vertRatio < minHoriz) {
                data.addVelocityBuffer(0.75);
            }
        }

        flagFromBuffer(data, expectedHoriz, expectedVert, actualHoriz, actualVert, anyHorizontal);

        if (data.getServerVelocityBufferTicks() >= MAX_BUFFER_TICKS) {
            data.clearServerVelocity();
        }
    }

    private double getSensitivity(NyxPlayerData data) {
        var config = getConfig();
        return config != null ? config.sensitivity() : 0.8;
    }

    private void flagFromBuffer(NyxPlayerData data, double eh, double ev, double ah, double av, boolean anyH) {
        double buffer = data.getVelocityBuffer();
        double threshold = 3.0;
        double sensitivity = getSensitivity(data);
        if (sensitivity < 0.5) threshold = 4.0;

        if (buffer > threshold) {
            String info;
            if (anyH && ev > 0.05) {
                info = String.format("H:%.0f%% E:%.4f A:%.4f V:%.4f/%.4f", ah / Math.max(eh, 1e-9) * 100, eh, ah, av, ev);
            } else if (anyH) {
                info = String.format("H:%.0f%% E:%.4f A:%.4f", ah / Math.max(eh, 1e-9) * 100, eh, ah);
            } else {
                info = String.format("V:%.4f/%.4f", av, ev);
            }
            flag(data, info);
            data.resetVelocityBuffer(1.0);
        }
    }
}
