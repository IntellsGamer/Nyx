package dev.nyx.checks.elytra;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CheckData(name = "ExtraElytra", description = "Detects ExtraElytra hacks (packet spam, impossible speed/height control)")
public class ExtraElytraCheck extends Check {

    private static final int MAX_START_PACKETS = 5;
    private static final double IMPOSSIBLE_TOTAL_SPEED = 4.50;
    private static final double HORIZONTAL_LEVEL_SPEED = 2.70;
    private static final double IMPOSSIBLE_VSPEED = 0.70;
    private static final float PITCH_UP_CUTOFF = -60f;
    private static final float PITCH_DOWN_CUTOFF = 30f;
    private static final double FLAG_BUFFER = 6.0;
    private static final long FIREWORK_EXEMPT_MS = 5000;
    private static final long GLIDE_START_EXEMPT_MS = 3000;

    private final Map<UUID, ElytraState> stateMap = new ConcurrentHashMap<>();

    public ExtraElytraCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        int packets = data.getElytraStartPacketCount();
        if (packets > MAX_START_PACKETS) {
            flag(data, String.format("PacketSpam C:%d", packets));
            return;
        }

        if (!data.isGliding()) return;

        if (data.getPlayer().isInWaterOrBubbleColumn()) return;

        ElytraState state = stateMap.computeIfAbsent(data.getPlayer().getUniqueId(), k -> new ElytraState());

        long now = System.currentTimeMillis();

        if (now - data.getLastFireworkTime() < FIREWORK_EXEMPT_MS
            || now - data.getGlideStartTime() < GLIDE_START_EXEMPT_MS) {
            state.buffer = Math.max(0, state.buffer - 0.3);
            return;
        }

        double deltaY = data.getDeltaY();
        double horizSpeed = data.getHorizontalSpeed();
        double totalSpeed = Math.hypot(Math.hypot(data.getDeltaX(), data.getDeltaZ()), deltaY);

        var history = data.getRotationHistory();
        if (history.isEmpty()) return;
        float pitch = history.peekFirst().pitch();

        boolean suspected = false;

        if (totalSpeed > IMPOSSIBLE_TOTAL_SPEED) {
            state.buffer += 1.0;
            state.lastInfo = String.format("SpeedCtrl V:%.3f P:%.1f", totalSpeed, pitch);
            suspected = true;
        }

        if (horizSpeed > HORIZONTAL_LEVEL_SPEED && pitch < PITCH_DOWN_CUTOFF) {
            state.buffer += 1.0;
            state.lastInfo = String.format("SpeedCtrl S:%.3f P:%.1f", horizSpeed, pitch);
            suspected = true;
        }

        if (deltaY > IMPOSSIBLE_VSPEED && pitch > PITCH_UP_CUTOFF) {
            state.buffer += 1.0;
            state.lastInfo = String.format("HeightCtrl DY:%.3f P:%.1f", deltaY, pitch);
            suspected = true;
        }

        if (!suspected) {
            state.buffer = Math.max(0, state.buffer - 0.15);
        }

        if (state.buffer > FLAG_BUFFER) {
            state.buffer -= FLAG_BUFFER;
            flag(data, state.lastInfo);
        }
    }

    private static class ElytraState {
        double buffer = 0;
        String lastInfo = "";
    }
}