package dev.nyx.checks.other;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "FastBreak", description = "Detects FastBreak / Nuker block-breaking exploits")
public class FastBreakCheck extends Check {

    private static final long MIN_BREAK_MS = 50;
    private static final int MAX_STOP_PACKETS = 2;
    private static final int MAX_CONSECUTIVE = 6;

    public FastBreakCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        long digStopCount = data.getDigStopCount();
        int consecutive = data.getConsecutiveBreaks();

        if (digStopCount > MAX_STOP_PACKETS) {
            flag(data, String.format("MultiStop:%d Consec:%d", digStopCount, consecutive));
            data.resetDigStopCount();
        }

        if (consecutive > MAX_CONSECUTIVE) {
            long now = System.currentTimeMillis();
            if (now - data.getLastDigStartTime() < MIN_BREAK_MS * consecutive) {
                flag(data, String.format("NoDelay Consec:%d T:%dms", consecutive, now - data.getLastDigStartTime()));
            }
        }

        if (digStopCount > 0 && data.getLastDigCompleteTime() - data.getLastDigStartTime() < MIN_BREAK_MS) {
            flag(data, String.format("InstantBreak T:%dms", data.getLastDigCompleteTime() - data.getLastDigStartTime()));
        }
    }
}
