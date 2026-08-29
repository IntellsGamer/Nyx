package dev.nyx.checks.movement;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "Timer", description = "Detects game speed manipulation via packet timing")
public class TimerCheck extends Check {

    private static final long TICK_INTERVAL_NS = 50_000_000L;

    public TimerCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 5) return;

        var history = data.getPositionHistory();

        int samples = Math.min(history.size(), 20);
        var iterator = history.iterator();

        long firstTime = 0;
        long lastTime = 0;
        int count = 0;

        while (iterator.hasNext() && count < samples) {
            var snapshot = iterator.next();
            if (count == 0) firstTime = snapshot.timestamp();
            if (count == samples - 1) lastTime = snapshot.timestamp();
            count++;
        }

        if (count < 2) return;

        long elapsedNs = lastTime - firstTime;
        long expectedNs = (count - 1) * TICK_INTERVAL_NS;

        if (expectedNs <= 0) return;

        double ratio = (double) elapsedNs / (double) expectedNs;

        double sensitivity = getConfig() != null ? getConfig().sensitivity() : 0.6;
        double threshold = 0.05 + (1.0 - sensitivity) * 0.1;

        if (ratio < 1.0 - threshold) {
            flag(data, String.format(
                "R:%.3f E:%dms A:%dms",
                ratio, expectedNs / 1_000_000L, elapsedNs / 1_000_000L
            ));
        }
    }
}
