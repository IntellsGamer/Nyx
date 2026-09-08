package dev.idebugger.nyx.checks.other;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastPlace detection from block-place packet timing.
 *
 * A vanilla client cannot emit two block placements in the same tick, and
 * holding right-click places at most one block every 4 ticks. Cheats place
 * per tick (or faster via packet injection). The place count per movement
 * tick is already tracked in the player data by the packet listener, so the
 * check evaluates it here plus the sustained place interval across ticks.
 *
 * The config previously shipped a "fastplace" section with no implementing
 * class — this check makes that section real.
 */
@CheckData(name = "FastPlace", description = "Detects fast block placement")
public class FastPlaceCheck extends Check {

    private static final int MAX_PLACES_PER_TICK = 1;

    // Consecutive sub-4-tick place intervals before flagging. Vanilla held
    // right-click emits ~1 place / 4 ticks; clicking manually is slower. A
    // couple of fast clicks are legit jitter-click bridging; sustained
    // per-tick placing is a cheat.
    private static final int FAST_INTERVAL_TICKS_TO_FLAG = 5;

    private final Map<UUID, State> stateMap = new ConcurrentHashMap<>();

    public FastPlaceCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public void onPlayerQuit(UUID uuid) {
        stateMap.remove(uuid);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        int placesThisTick = data.getPlaceCountThisTick();
        long lastPlace = data.getLastPlaceTime();
        if (lastPlace <= 0) return;

        State state = stateMap.computeIfAbsent(data.getUuid(), k -> new State());
        long now = System.currentTimeMillis();

        // Multiple placements inside a single movement tick is impossible
        // for a vanilla client (it emits at most one per tick).
        if (placesThisTick > MAX_PLACES_PER_TICK) {
            state.fastIntervals += 2;
            if (state.fastIntervals >= FAST_INTERVAL_TICKS_TO_FLAG * 2) {
                state.fastIntervals = 0;
                flag(data, String.format("Rate %d/tick", placesThisTick));
            }
            return;
        }

        if (placesThisTick == 0) {
            // No placement this tick: decay the streak slowly so isolated
            // fast clicks don't ladder into a flag.
            state.fastIntervals = Math.max(0, state.fastIntervals - 1);
            return;
        }

        // One placement this tick: compare with the last place time.
        long interval = now - lastPlace;
        // 4 ticks = 200ms is the vanilla held-click cadence; 50ms is one
        // tick. A legit fast clicker occasionally produces ~100-150ms
        // intervals, so only sub-1.5-tick intervals count toward the streak.
        if (state.lastPlaceTime > 0 && interval < 75) {
            state.fastIntervals++;
            if (state.fastIntervals >= FAST_INTERVAL_TICKS_TO_FLAG) {
                state.fastIntervals = 0;
                flag(data, String.format("Interval %dms", interval));
            }
        } else {
            state.fastIntervals = Math.max(0, state.fastIntervals - 1);
        }
        state.lastPlaceTime = now;
    }

    private static final class State {
        long lastPlaceTime;
        int fastIntervals;
    }
}
