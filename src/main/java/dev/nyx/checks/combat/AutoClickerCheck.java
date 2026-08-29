package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

import java.util.Deque;

@CheckData(name = "AutoClicker", description = "Detects automated clicking patterns")
public class AutoClickerCheck extends Check {

    private static final double MIN_CPS_FOR_CHECK = 6.0;
    private static final int MIN_SAMPLES = 10;

    public AutoClickerCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        Deque<Long> attackTimes = data.getAttackTimes();
        if (attackTimes.size() < MIN_SAMPLES) return;

        int cps = data.getAttackCps();
        if (cps < MIN_CPS_FOR_CHECK) return;

        long[] times = new long[attackTimes.size()];
        int i = 0;
        for (long t : attackTimes) {
            times[i++] = t;
        }

        long[] intervals = new long[times.length - 1];
        for (int j = 0; j < intervals.length; j++) {
            intervals[j] = times[j + 1] - times[j];
        }

        if (intervals.length < 2) return;

        double mean = 0;
        for (long d : intervals) mean += d;
        mean /= intervals.length;

        double variance = 0;
        for (long d : intervals) {
            double diff = d - mean;
            variance += diff * diff;
        }
        variance /= intervals.length;

        double stdDev = Math.sqrt(variance);

        double cv = (mean > 0) ? stdDev / mean : 0;

        long minDelta = Long.MAX_VALUE;
        long maxDelta = Long.MIN_VALUE;
        for (long d : intervals) {
            if (d < minDelta) minDelta = d;
            if (d > maxDelta) maxDelta = d;
        }
        long range = maxDelta - minDelta;

        if (cps > 22) {
            flag(data, String.format("HighCPS:%d CV:%.2f", cps, cv));
            return;
        }

        if (range < 30 && cps > 12) {
            flag(data, String.format("Consistent R:%dms CPS:%d CV:%.2f", range, cps, cv));
            return;
        }

        if (cv < 0.2 && cps > 10) {
            double expectedStdDev = mean * 0.4;
            if (stdDev < expectedStdDev * 0.5) {
                flag(data, String.format("LowVar CPS:%d CV:%.2f STD:%.1f", cps, cv, stdDev));
            }
        }
    }
}
