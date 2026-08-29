package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(name = "AimAssist", description = "Detects aim-assist via GCD / rotation-smoothness analysis")
public class AimAssistCheck extends Check {

    private static final long EXPANDER = 16777216L;
    private static final long GCD_NOISE_FLOOR = 16384L;
    private static final double MIN_VALID_SENSITIVITY = -0.05;
    private static final double MAX_VALID_SENSITIVITY = 5.0;

    private static final float PITCH_SNAP_THRESHOLD = 20f;
    private static final long COMBAT_WINDOW_MS = 3000;
    private static final double BUFFER_FLAG = 6.0;
    private static final double BUFFER_DECREASE = 0.15;

    private final Map<UUID, AimState> aimDataMap = new ConcurrentHashMap<>();

    public AimAssistCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        long now = System.currentTimeMillis();
        if (now - data.getLastAttackTime() > COMBAT_WINDOW_MS) return;

        var history = data.getRotationHistory();
        if (history.size() < 3) return;

        AimState state = aimDataMap.computeIfAbsent(data.getPlayer().getUniqueId(), k -> new AimState());

        float deltaPitch = data.getLastDeltaPitch();
        float deltaYaw = data.getLastDeltaYaw();

        if (deltaPitch > PITCH_SNAP_THRESHOLD) {
            flag(data, String.format("PitchSnap DP:%.1f", deltaPitch));
            state.buffer = Math.max(0, state.buffer - 0.5);
            state.lastDeltaPitch = deltaPitch;
            state.lastDeltaYaw = deltaYaw;
            return;
        }

        if (deltaPitch > 15f || deltaYaw > 15f) {
            state.buffer = Math.max(0, state.buffer - 0.25);
            state.lastDeltaPitch = deltaPitch;
            state.lastDeltaYaw = deltaYaw;
            return;
        }

        if (deltaPitch < 0.2f) {
            state.lastDeltaPitch = deltaPitch;
            state.lastDeltaYaw = deltaYaw;
            return;
        }

        float accelPitch = Math.abs(deltaPitch - state.lastDeltaPitch);
        if (accelPitch < 0.05f && deltaPitch > 0f) {
            state.cinematicTicks = Math.min(20, state.cinematicTicks + 1);
        } else {
            state.cinematicTicks = Math.max(0, state.cinematicTicks - 2);
        }
        if (state.cinematicTicks > 5) {
            state.buffer = Math.max(0, state.buffer - 0.2);
            state.lastDeltaPitch = deltaPitch;
            state.lastDeltaYaw = deltaYaw;
            return;
        }

        long currentPitch = (long) (deltaPitch * EXPANDER);
        long lastPitch = (long) (state.lastDeltaPitch * EXPANDER);
        if (currentPitch <= 0 || lastPitch <= 0) {
            state.lastDeltaPitch = deltaPitch;
            state.lastDeltaYaw = deltaYaw;
            return;
        }

        long gcd = gcd(currentPitch, lastPitch);
        double step = (double) gcd / EXPANDER;

        if (step > 0.005) {
            double pixels = deltaPitch / step;
            double error = Math.abs(pixels - Math.round(pixels));

            if (error < 0.001) {
                double val = Math.cbrt(step / 1.2);
                double sens = (val - 0.2) / 0.6;

                if (sens < MIN_VALID_SENSITIVITY || sens > MAX_VALID_SENSITIVITY) {
                    state.buffer += 1.0;
                    if (state.buffer > BUFFER_FLAG) {
                        flag(data, String.format("BadSens S:%.5f Sens:%.0f%%", step, sens * 100));
                        state.buffer = 0;
                    }
                } else {
                    state.buffer = Math.max(0, state.buffer - BUFFER_DECREASE);
                }
            } else {
                state.buffer = Math.max(0, state.buffer - 0.05);
            }
        } else {
            state.buffer = Math.max(0, state.buffer - 0.05);
        }

        state.lastDeltaPitch = deltaPitch;
        state.lastDeltaYaw = deltaYaw;
    }

    private long gcd(long current, long previous) {
        return (previous <= GCD_NOISE_FLOOR) ? current : gcd(previous, current % previous);
    }

    private static class AimState {
        float lastDeltaPitch = 0f;
        float lastDeltaYaw = 0f;
        int cinematicTicks = 0;
        double buffer = 0;
    }
}
