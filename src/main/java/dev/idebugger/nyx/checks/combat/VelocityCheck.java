package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.ServerVelocity;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Anti-kinetic (anti-knockback) detection using a buffered server velocity.
 *
 * When the server sends an ENTITY_VELOCITY packet it is buffered in
 * NyxPlayerData (recordServerVelocity). Over the following movement ticks the
 * player is expected to move with the horizontal and vertical components of
 * that applied velocity, decaying each tick. We track how much of the knockback
 * is actually consumed and flag sustained under-consumption.
 *
 * Legitimate reductions are accounted for so armored / enchanted players are
 * not mistaken for cheaters:
 *  - netherite armour's knockback resistance (GENERIC_KNOCKBACK_RESISTANCE);
 *  - blast protection and explosion knockback resistance
 *    (GENERIC_EXPLOSION_KNOCKBACK_RESISTANCE) for TNT / creeper knockback;
 *  - huge knockbacks (wind charges, explosions) get a tolerance bump because
 *    their applied velocity is naturally consumed sloppier than melee hits.
 *
 * Every flag re-applies the expected velocity back onto the player (an actual
 * knockback), so resisting knockback no longer pays off even between the
 * config-driven punches.
 */
@CheckData(name = "Velocity", description = "Detects anti-knockback (armor/blast aware, re-applies knockback)")
public class VelocityCheck extends Check {

    private static final int MAX_BUFFER_TICKS = 12;

    // A mob volley (2+ attackers within a tick) or any recent non-knockback
    // damage (poison/wither/fall staggering the player after a punch) breaks the
    // clean consumption curve. Those windows are forgiven entirely: the buffered
    // expectations are dropped and the accumulator wiped so they cannot ladder VL.
    private static final long MULTI_HIT_WINDOW_MS = 1500;
    private static final long DAMAGE_RECENCY_MS = 1200;

    // Lenient on purpose: frictional ticks naturally eat less of the applied
    // velocity, so only a sustained majority of the 12-tick window flags. The
    // real deterrent is the knockback re-applied on every flag, not the count.
    private static final double MIN_HORIZ_RATIO = 0.70;
    private static final double MAX_HORIZ_RATIO = 2.0;

    /** Applied velocity magnitude above which we grant extra consumption tolerance (wind charges etc.). */
    private static final double STRONG_KNOCKBACK = 1.8;
    private static final double STRONG_TOLERANCE = 1.25;

    // 0.5 per under-consumed tick reaches the 2.0 threshold after ~5 in a row:
    // unambiguous anti-knockback. Legit friction ticks are usually isolated and
    // the -0.35 drain on good ticks keeps a normal player far below the line.
    private static final double FLAG_THRESHOLD = 2.0;
    private static final double FLAG_THRESHOLD_LOW_SENSITIVITY = 3.0;
    private static final double BAD_TICK_WEIGHT = 0.5;
    private static final double GOOD_TICK_DECAY = 0.35;

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

        long now = System.currentTimeMillis();

        // Mob volleys and damage staggers: the motion no longer follows any
        // single buffered vector, so enforce nothing until the dust settles.
        boolean messyWindow = (data.isVelocityMultiHit()
            && now - data.getLastVelocityMultiHitTime() < MULTI_HIT_WINDOW_MS)
            || (data.getLastDamageTime() > 0
            && now - data.getLastDamageTime() < DAMAGE_RECENCY_MS);
        if (messyWindow) {
            data.resetVelocityBuffer(0.0);
            data.clearServerVelocity();
            return;
        }

        Vector applied = sv.vector();

        // Netherite knockback resistance, blast protection and explosion
        // resistance legitimately reduce how far the player is pushed. The
        // sent velocity may or may not already reflect it depending on the
        // server fork, so discount the expected motion by the same amount to
        // keep armored players safe while the check stays hard on everyone else.
        double factor = knockoutFactor(data);

        double expectedHoriz = Math.hypot(applied.getX(), applied.getZ()) * factor;
        double expectedVert = applied.getY() * factor;
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

        // Wind charges / explosions land hard and are eaten with far more
        // variance than a melee hit: relax the required consumption a little.
        boolean strong = Math.hypot(applied.getX(), applied.getZ()) + Math.abs(applied.getY()) > STRONG_KNOCKBACK;
        double minHoriz = MIN_HORIZ_RATIO - (1.0 - sensitivity) * 0.15;
        if (strong) minHoriz *= STRONG_TOLERANCE;

        // One contribution per tick, from the worst of the two signals. Adding
        // horizontal and vertical independently is what let the old check ladder
        // to a flag in ~2 ticks and trigger kicks almost immediately.
        boolean anyHorizontal = false;
        boolean horizBad = false;
        boolean vertBad = false;

        if (expectedHoriz > 0.01) {
            anyHorizontal = true;
            double observed = Math.min(actualHoriz / expectedHoriz, MAX_HORIZ_RATIO);
            horizBad = observed < minHoriz;
        }
        if (expectedVert > 0.05 && !data.isOnGround()) {
            vertBad = actualVert / expectedVert < minHoriz;
        }

        if (horizBad || vertBad) {
            data.addVelocityBuffer(BAD_TICK_WEIGHT);
        } else {
            data.addVelocityBuffer(-GOOD_TICK_DECAY);
        }

        flagFromBuffer(data, expectedHoriz, expectedVert, actualHoriz, actualVert, anyHorizontal);

        if (data.getServerVelocityBufferTicks() >= MAX_BUFFER_TICKS) {
            data.clearServerVelocity();
        }
    }

    /**
     * Fraction of the knockback this player is legitimately allowed to ignore.
     * Netherite armour resistance and blast protection stack up to 80%.
     */
    private double knockoutFactor(NyxPlayerData data) {
        double resist = attribute(data, Attribute.KNOCKBACK_RESISTANCE);
        resist += attribute(data, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE);
        if (resist <= 0.01) return 1.0;
        return Math.max(0.2, 1.0 - Math.min(0.8, resist));
    }

    private double attribute(NyxPlayerData data, Attribute attr) {
        Player player = data.getPlayer();
        if (player == null) return 0;
        AttributeInstance instance = player.getAttribute(attr);
        return instance == null ? 0 : instance.getValue();
    }

    private double getSensitivity(NyxPlayerData data) {
        var config = getConfig();
        return config != null ? config.sensitivity() : 0.8;
    }

    private void flagFromBuffer(NyxPlayerData data, double eh, double ev, double ah, double av, boolean anyH) {
        double buffer = data.getVelocityBuffer();
        double threshold = FLAG_THRESHOLD;
        double sensitivity = getSensitivity(data);
        if (sensitivity < 0.5) threshold = FLAG_THRESHOLD_LOW_SENSITIVITY;

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

            // Resisting knockback must not pay off: shove the expected velocity
            // straight back onto the player, on the main thread.
            applyKnockback(data);

            data.resetVelocityBuffer(0.0);
        }
    }

    /**
     * Re-applies the still-buffered server velocity to the player, forcing the
     * knockback they tried to cancel to actually move them.
     */
    private void applyKnockback(NyxPlayerData data) {
        ServerVelocity sv = data.peekServerVelocity();
        if (sv == null) return;
        Vector applied = sv.vector();
        if (applied.lengthSquared() < 1e-6) return;

        Player player = data.getPlayer();
        if (player == null || !player.isOnline()) return;

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            player.setVelocity(applied.clone());
        });
    }
}