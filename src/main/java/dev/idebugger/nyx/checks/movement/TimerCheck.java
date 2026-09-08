package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

/**
 * Timer / game-speed detection using a running balance instead of raw
 * wall-clock ratios.
 *
 * Each movement packet adds one expected tick (50ms) to the balance and
 * subtracts the real elapsed time since the previous packet. A legit client
 * hovers near zero: jitter cancels itself out, and a lag burst that delays
 * packets creates credit that the following catch-up burst legitimately
 * spends. A timer cheater's balance climbs monotonically because their
 * client emits ticks faster than real time — the balance is a credit/debit
 * ledger that can only go into sustained debt through actual time dilation.
 *
 * This is immune to the two failure modes of the old ratio check: post-lag
 * catch-up bursts (which produced false positives for exactly the laggy
 * players that must be tolerated) and subtle slow timers (1.05x) that hid
 * under a wide ratio threshold.
 */
@CheckData(name = "Timer", description = "Detects game speed manipulation via tick-balance analysis")
public class TimerCheck extends Check {

    private static final double TICK_MS = 50.0;

    // Balance at which the client has unequivocally gained time. A cheater at
    // 1.05x reaches 25 ticks of debt in ~8 seconds; legit jitter never comes
    // close because every delayed packet first deposits credit.
    private static final double FLAG_BALANCE_MS = 1250.0;

    // How much credit (negative balance) a client may bank from a lag burst.
    // Catch-up right after a stall legitimately spends it, but a balance far
    // below this floor means packets stopped arriving for a long stretch and
    // the ledger should re-baseline rather than let a cheater pre-pay for a
    // fast-forward.
    private static final double MAX_CREDIT_MS = 1000.0;

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

        long now = System.nanoTime();

        // The check is meaningless across teleports: the client re-syncs its
        // position and the packet gap is not player movement.
        if (data.getLastTeleportTime() > 0
            && System.currentTimeMillis() - data.getLastTeleportTime() < 500) {
            data.setTimerLastRealNs(now);
            data.setTimerBalance(0);
            return;
        }

        long last = data.getTimerLastRealNs();
        if (last == 0) {
            // First packet of a session (or after a teleport / world change):
            // establish the baseline only.
            data.setTimerLastRealNs(now);
            return;
        }

        double elapsedMs = (now - last) / 1_000_000.0;
        data.setTimerLastRealNs(now);

        // Ignore absurd gaps (server lag spike, pause, world switch): they
        // are credit the cheater should not get to bank.
        if (elapsedMs > 1000.0) return;

        // One expected tick per movement packet.
        double balance = data.getTimerBalance() + TICK_MS - elapsedMs;
        if (balance < -MAX_CREDIT_MS) {
            // Too much banked credit: clamp and move on without flagging. A
            // genuine catch-up burst will bring the balance back up.
            balance = -MAX_CREDIT_MS;
        }
        data.setTimerBalance(balance);

        double sensitivity = getConfig() != null ? getConfig().sensitivity() : 0.6;
        double flagAt = FLAG_BALANCE_MS * (0.5 + (1.0 - sensitivity));

        if (balance > flagAt) {
            flag(data, String.format("Balance:%.0fms", balance));
            // Charge the flag off the ledger so it must re-earn the debt.
            data.addTimerBalance(-flagAt);
        }
    }
}
