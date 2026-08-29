package dev.nyx.prediction;

import org.bukkit.util.Vector;

public record MovementResult(
    double deltaX,
    double deltaY,
    double deltaZ,
    double horizontalSpeed,
    double verticalSpeed,
    boolean onGround,
    Vector predictedVelocity
) {

    public double excessHorizontal(double actualHorizontal) {
        return actualHorizontal - horizontalSpeed;
    }

    public double excessVertical(double actualVertical) {
        return actualVertical - verticalSpeed;
    }

    public static MovementResult zero() {
        return new MovementResult(0, 0, 0, 0, 0, true, new Vector());
    }
}
