package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aim-assist detection using the well-known GCD / sensitivity-extraction
 * method (the same publicly documented approach used by GrimAC, originally
 * derived from Kauri).
 *
 * Legitimate mouse movement produces rotation deltas whose pairwise GCD
 * converges to a stable "divisor" that maps back to a real in-game mouse
 * sensitivity. Aim-assist / aimbots alter the rotation stream so this derived
 * sensitivity drifts outside any physically-possible value.
 *
 * Rather than a naive GCD of two consecutive samples (which is very noisy and
 * false-positive prone), we collect GCDs into a running frequency table and,
 * once we have enough significant samples, take the most common (modal)
 * divisor. The modal divisor is far more stable, which is what makes this
 * heuristic reliable on the average player and a good "public tuner".
 *
 * We only trust an estimate after a minimum amount of rotational data has been
 * gathered during a combat window, and require the offending sensitivity to
 * persist across a buffer before flagging.
 */
@CheckData(name = "AimAssist", description = "Detects aim-assist via GCD / sensitivity extraction analysis")
public class AimAssistCheck extends Check {

    private static final double MINIMUM_DIVISOR = 0.001;
    private static final int SIGNIFICANT_SAMPLES = 25;
    private static final int TOTAL_SAMPLES = 60;
    private static final long COMBAT_WINDOW_MS = 4000;

    private static final double VALID_SENSITIVITY_MIN = 0.001;
    private static final double VALID_SENSITIVITY_MAX = 5.0;
    private static final double SENSITIVITY_TOLERANCE = 0.05;

    private static final double BUFFER_FLAG = 5.0;
    private static final double BUFFER_DECREASE = 0.15;

    private final Map<UUID, AimState> stateMap = new ConcurrentHashMap<>();

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

        float deltaYaw = data.getLastDeltaYaw();
        float deltaPitch = data.getLastDeltaPitch();

        // Limit analysis to meaningful, sub-5 degree rotations; tiny rotations
        // and snap rotations are both uninformative for sensitivity extraction.
        if (deltaYaw >= 5 || deltaPitch >= 5) {
            stateMap.computeIfAbsent(data.getPlayer().getUniqueId(), k -> new AimState()).reset();
            return;
        }

        AimState state = stateMap.computeIfAbsent(data.getPlayer().getUniqueId(), k -> new AimState());

        if (deltaYaw > 0) {
            double divisor = gcd(deltaYaw, state.lastYaw);
            if (divisor > MINIMUM_DIVISOR) {
                state.yawMode.add(divisor);
            }
            state.lastYaw = deltaYaw;
            state.totalYawSamples++;
        }

        if (deltaPitch > 0) {
            double divisor = gcd(deltaPitch, state.lastPitch);
            if (divisor > MINIMUM_DIVISOR) {
                state.pitchMode.add(divisor);
            }
            state.lastPitch = deltaPitch;
            state.totalPitchSamples++;
        }

        evaluate(state, data);
    }

    private void evaluate(AimState state, NyxPlayerData data) {
        double sensitivity = -1;
        String axis = null;

        // Use whichever rotation axis has collected enough significant data.
        if (state.yawMode.size() >= SIGNIFICANT_SAMPLES) {
            double modal = state.yawMode.getMode();
            if (modal > MINIMUM_DIVISOR) {
                sensitivity = convertToSensitivity(modal);
                axis = "Yaw";
            }
        }
        if (state.pitchMode.size() >= SIGNIFICANT_SAMPLES) {
            double modal = state.pitchMode.getMode();
            if (modal > MINIMUM_DIVISOR) {
                double pitchSensitivity = convertToSensitivity(modal);
                // Prefer whichever axis is more suspicious (further from valid range).
                if (axis == null || !isPlausible(pitchSensitivity) && isPlausible(sensitivity)) {
                    sensitivity = pitchSensitivity;
                    axis = "Pitch";
                }
            }
        }

        if (sensitivity < 0) return;

        if (isPlausible(sensitivity)) {
            state.buffer = Math.max(0, state.buffer - BUFFER_DECREASE);
        } else {
            state.buffer++;
            if (state.buffer > BUFFER_FLAG) {
                flag(data, String.format(
                    "Axis:%s Sens:%.3f", axis, sensitivity
                ));
                state.buffer = 0;
                state.reset();
            }
        }
    }

    private boolean isPlausible(double sensitivity) {
        return sensitivity >= VALID_SENSITIVITY_MIN - SENSITIVITY_TOLERANCE
            && sensitivity <= VALID_SENSITIVITY_MAX + SENSITIVITY_TOLERANCE;
    }

    // The reverse of Vanilla's mouse-sensitivity -> angle-step conversion.
    // angleStep = 0.15 * sensitivity^3 * 8  =>  sensitivity = cbrt(step / (0.15*8))
    static double convertToSensitivity(double divisor) {
        double inner = divisor / 0.15 / 8.0;
        double cbrt = Math.cbrt(inner);
        return (cbrt - 0.2) / 0.6;
    }

    private double gcd(double current, double previous) {
        if (previous < 1.0E-4) return current;
        if (current < 1.0E-4) return previous;
        return gcd(previous, current % previous);
    }

    private static final class AimState {
        private final RunningMode yawMode = new RunningMode(TOTAL_SAMPLES);
        private final RunningMode pitchMode = new RunningMode(TOTAL_SAMPLES);
        float lastYaw;
        float lastPitch;
        int totalYawSamples;
        int totalPitchSamples;
        double buffer;

        void reset() {
            yawMode.clear();
            pitchMode.clear();
            lastYaw = 0;
            lastPitch = 0;
            totalYawSamples = 0;
            totalPitchSamples = 0;
        }
    }

    /**
     * Tracks the frequency of GCD divisor values within a sliding window, so we
     * can retrieve the most common (modal) divisor once enough samples accrue.
     */
    private static final class RunningMode {
        private final int maxSize;
        private final Map<Double, Integer> counts = new LinkedHashMap<>(64, 0.75f);
        private int size;

        RunningMode(int maxSize) {
            this.maxSize = maxSize;
        }

        synchronized void add(double value) {
            value = Math.round(value * 1_000_000d) / 1_000_000d;
            counts.merge(value, 1, Integer::sum);
            size++;
            if (size > maxSize) {
                counts.merge(firstKey(), -1, Integer::sum);
                if (counts.get(firstKey()) <= 0) counts.remove(firstKey());
            }
        }

        private double firstKey() {
            for (double k : counts.keySet()) return k;
            return 0;
        }

        int size() {
            return size;
        }

        synchronized double getMode() {
            int best = -1;
            double mode = -1;
            for (Map.Entry<Double, Integer> e : counts.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    mode = e.getKey();
                }
            }
            return mode;
        }

        synchronized void clear() {
            counts.clear();
            size = 0;
        }
    }
}
