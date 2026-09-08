package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.IceType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(name = "Speed", description = "Detects horizontal speed violations")
public class SpeedCheck extends Check {

    private static final long FIREWORK_GRACE_MS = 4000;
    private static final long GLIDE_GRACE_MS = 3000;
    private static final long RIPTIDE_GRACE_MS = 4000;

    // Momentum picked up on ice legitimately carries further than the vanilla
    // caps allow while it fades out. The allowance already covers that coast, so
    // the extra grace window is kept tiny: only a couple of ticks of slop for
    // jitter, and sustained over-limit movement gets flagged right away.
    private static final int OVER_LIMIT_TICKS_TO_FLAG = 3;
    private static final int OVER_LIMIT_DECAY_TICKS = 3;

    private final Map<UUID, Integer> overLimitTicks = new ConcurrentHashMap<>();

    public SpeedCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public void onPlayerQuit(UUID uuid) {
        overLimitTicks.remove(uuid);
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

        // State-based exemptions instead of blanket time windows: a wall-clock
        // grace a cheater can re-arm forever (hold a firework and right-click
        // every few seconds) is replaced by "is the condition actually live".
        // A pending unconsumed server velocity IS the knockback/explosion
        // window — consume it, don't time it.
        if (data.hasServerVelocity()) return;

        // Firework/glide momentum: only exempt while the boost is still
        // physically plausible (the player is still moving faster than any
        // normal speed), not for a flat multi-second window afterwards.
        if (now - data.getLastFireworkTime() < FIREWORK_GRACE_MS && data.getHorizontalSpeed() > 0.5) return;
        if (now - data.getLastGlideTime() < GLIDE_GRACE_MS && data.getHorizontalSpeed() > 0.5) return;
        // Riptide: same treatment, plus the vertical component of the launch arc.
        if (now - data.getLastRiptideTime() < RIPTIDE_GRACE_MS
            && (data.getHorizontalSpeed() > 0.5 || data.getVerticalSpeed() > 0.1)) return;

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
        // reach ~1.6/1.8/2.6 blocks-tick, keep those generous caps. The boost
        // must scale airborne sprint-jump caps too or buffed players false-flag.
        IceType ice = data.getIceType();
        if (ice != null && ice != IceType.NONE) {
            return switch (ice) {
                case BLUE_ICE -> 2.6;
                case PACKED_ICE -> 1.8;
                default -> 1.6; // ICE, FROSTED_ICE
            };
        }

        return Math.max(Math.max(0.45 * boost, 0.45), momentum);
    }
    /**
     * Ground-speed multiplier from the movement-speed attribute. On modern
     * MC the Speed potion effect IS an attribute modifier, so getValue() already
     * includes it — the old code multiplied the potion effect in again on top,
     * inflating the cap by up to ~1.96x for Speed II players (a bypass letting
     * them move 96% over vanilla max with zero flags). The attribute alone is
     * the truth; it is never double-counted here.
     */
    private double speedBoost(NyxPlayerData data) {
        Player player = data.getPlayer();
        if (player == null) return 1.0;

        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null && attr.getBaseValue() > 0 && attr.getValue() > attr.getBaseValue()) {
            return attr.getValue() / attr.getBaseValue();
        }
        return 1.0;
    }
}
