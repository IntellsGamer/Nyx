package dev.nyx.checks.other;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "FastUse", description = "Detects fast item usage exploits")
public class FastUseCheck extends Check {

    private static final long MIN_USE_INTERVAL_MS = 200;
    private static final long MIN_EAT_INTERVAL_MS = 300;
    private static final long MIN_POTION_INTERVAL_MS = 350;

    public FastUseCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        int rightCps = data.getRightClickCps();
        if (rightCps > 15) {
            flag(data, String.format("RightCPS:%d", rightCps));
        }
    }

    public void checkItemUse(NyxPlayerData data, long useStart, long useEnd, ItemAction action) {
        long duration = useEnd - useStart;

        long minDuration = switch (action) {
            case EAT -> MIN_EAT_INTERVAL_MS;
            case POTION -> MIN_POTION_INTERVAL_MS;
            case BOW -> 100;
            case OTHER -> MIN_USE_INTERVAL_MS;
        };

        if (duration < minDuration) {
            flag(data, String.format(
                "D:%dms MIN:%dms TYPE:%s",
                duration, minDuration, action.name()
            ));
        }
    }

    public enum ItemAction {
        EAT, POTION, BOW, OTHER
    }
}
