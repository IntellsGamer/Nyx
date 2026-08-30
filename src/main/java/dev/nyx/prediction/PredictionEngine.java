package dev.nyx.prediction;

import dev.nyx.data.NyxPlayerData;
import org.bukkit.util.Vector;

public final class PredictionEngine {

    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final double TERMINAL_VELOCITY = -3.92;

    private PredictionEngine() {}

    public static MovementResult predict(NyxPlayerData data) {
        if (data == null) return MovementResult.zero();
        var current = data.getPositionHistory().peekFirst();
        if (current == null) return MovementResult.zero();

        boolean onGround = data.isOnGround();

        var serverVel = data.peekServerVelocity();
        Vector velocity = serverVel != null ? serverVel.vector().clone() : new Vector();

        double velY = velocity.getY();
        if (!onGround) {
            velY = velY * AIR_DRAG - GRAVITY;
            if (velY < TERMINAL_VELOCITY) velY = TERMINAL_VELOCITY;
        } else {
            velY = 0;
        }

        boolean predictedOnGround = onGround;
        if (velY <= 0 && Math.abs(velY) < 0.1) {
            predictedOnGround = true;
        }

        return new MovementResult(
            velocity.getX(), velY, velocity.getZ(),
            Math.hypot(velocity.getX(), velocity.getZ()),
            Math.abs(velY),
            predictedOnGround,
            new Vector(velocity.getX(), velY, velocity.getZ())
        );
    }

    public static MovementResult predictWithJump(NyxPlayerData data, boolean wasOnGround) {
        MovementResult result = predict(data);
        if (!wasOnGround || data.isInWater() || data.isInLava() || data.isClimbing()) {
            return result;
        }

        Vector vel = result.predictedVelocity();
        vel.setY(0.42);

        return new MovementResult(
            result.deltaX(), 0.42, result.deltaZ(),
            result.horizontalSpeed(), 0.42,
            false, vel
        );
    }
}
