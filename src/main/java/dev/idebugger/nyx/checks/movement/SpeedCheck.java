package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.IceType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(name = "Speed", description = "Detects horizontal speed violations")
public class SpeedCheck extends Check {

    private static final long FIREWORK_GRACE_MS = 4000;
    private static final long GLIDE_GRACE_MS = 3000;
    private static final long RIPTIDE_GRACE_MS = 4000;

    // Knockback (natural or re-applied by the velocity check) shoves the
    // player backward faster than walking. If they keep moving/jumping in that
    // direction the horizontal speed reads as over-cap for a few ticks. Skip the
    // check until the momentum settles so a legit hit can't flag as speed.
    private static final long KNOCKBACK_GRACE_MS = 2000;

    // Momentum picked up on ice legitimately carries further than the vanilla
    // caps allow while it fades out. The allowance already covers that coast, so
    // the extra grace window is kept tiny: only a couple of ticks of slop for
    // jitter, and sustained over-limit movement gets flagged right away.
    private static final int OVER_LIMIT_TICKS_TO_FLAG = 3;
    private static final int OVER_LIMIT_DECAY_TICKS = 3;

    private static final double BASE_MOVEMENT_SPEED = 0.1;

    private final Map<UUID, Integer> overLimitTicks = new ConcurrentHashMap<>();

    public SpeedCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;
        if (data.isInVehicle()) return;

        if (data.isGliding() || data.isWasGliding()) return;

        long now = System.currentTimeMillis();
        if (now - data.getLastFireworkTime() < FIREWORK_GRACE_MS) return;
        if (now - data.getLastGlideTime() < GLIDE_GRACE_MS) return;
        if (now - data.getLastRiptideTime() < RIPTIDE_GRACE_MS) return;

        long kbGrace = plugin.getNyxConfig().getKnockbackGracePeriodMs();
        if (kbGrace > 0) {
            long lastKb = Math.max(data.getLastKnockbackAppliedTime(), data.getLastVelocityTime());
            if (now - lastKb < Math.max(kbGrace, KNOCKBACK_GRACE_MS)) return;
        }

        double speed = data.getHorizontalSpeed();
        if (speed < 0.01) return;

        double max = getMaxSpeed(data);
        UUID uuid = data.getUuid();

        if (speed > max) {
            int count = overLimitTicks.merge(uuid, 1, Integer::sum);
            if (count >= OVER_LIMIT_TICKS_TO_FLAG) {
                overLimitTicks.put(uuid, 0);
                flag(data, String.format("S:%.3f M:%.3f x%d", speed, max, count));
            }
            return;
        }

        // Decay the watchdog and drop idle entries so the map never grows stale.
        overLimitTicks.compute(uuid, (k, v) -> {
            int next = (v == null ? 0 : v) - OVER_LIMIT_DECAY_TICKS;
            return next <= 0 ? null : next;
        });
    }

    private double getMaxSpeed(NyxPlayerData data) {
        if (data.isInWater()) return 0.35;
        if (data.isInLava()) return 0.30;
        if (data.isClimbing()) return 0.17;

        // Ice momentum: while the player is on (or just left) ice, the surface
        // hands out way more speed than normal sprinting. Use the decaying
        // allowance as a floor on every branch so a player sprint/jumping away
        // from ice is never clamped until the real speed has fallen back down.
        double momentum = data.getIceMomentumAllowance();

        // The Speed potion effect (+20% per level) and a raised movement-speed
        // attribute both legitimately raise the ground speed a player can reach.
        // Scale the vanilla caps by that boost so buffed players aren't mistaken
        // for cheaters. Momentum is a real measured speed, so it is never scaled.
        double boost = speedBoost(data);

        if (data.isOnGround() || data.isLastOnGround()) {
            IceType ice = data.getIceType();
            if (ice != null && ice != IceType.NONE) {
                return Math.max(ice.getMaxSpeed(), momentum);
            }
            if (data.isOnSlime()) return Math.max(0.40 * boost, momentum);
            if (data.isOnSoulSand()) return Math.max(0.20 * boost, momentum);
            if (data.isSneaking()) return Math.max(0.10 * boost, momentum);
            return Math.max((data.isSprinting() ? 0.35 : 0.28) * boost, momentum);
        }

        // Still airborne over the ice itself: sprint-jumps on ice legitimately
        // reach ~1.6/1.8/2.6 blocks-tick, keep those generous caps.
        IceType ice = data.getIceType();
        if (ice != null && ice != IceType.NONE) {
            return switch (ice) {
                case BLUE_ICE -> 2.6;
                case PACKED_ICE -> 1.8;
                default -> 1.6; // ICE, FROSTED_ICE
            };
        }

        return Math.max(0.45, momentum);
    }

    /**
     * Ground-speed multiplier from the movement-speed attribute and the Speed
     * potion effect. Returns 1.0 for an unbuffed player, so the vanilla caps are
     * left untouched. The movement-speed attribute has a base of 0.1 and scales
     * walk/sprint speed proportionally, and the Speed effect adds +20% per level
     * (Speed I = 1.2x, Speed II = 1.4x, ...).
     */
    private double speedBoost(NyxPlayerData data) {
        Player player = data.getPlayer();
        double boost = 1.0;

        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null && attr.getValue() > BASE_MOVEMENT_SPEED) {
            boost *= attr.getValue() / BASE_MOVEMENT_SPEED;
        }

        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            boost *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        }

        return boost;
    }
}
