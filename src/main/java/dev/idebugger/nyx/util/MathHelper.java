package dev.idebugger.nyx.util;

public final class MathHelper {

    private MathHelper() {}

    public static final double GRAVITY = 0.08;
    public static final double AIR_DRAG = 0.98;
    public static final double GROUND_DRAG = 0.6;
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double PLAYER_EYE_HEIGHT = 1.62;
    public static final double SPRINT_SPEED = 1.3;
    public static final double SNEAK_SPEED = 0.3;
    public static final double WATER_DRAG = 0.8;
    public static final double LAVA_DRAG = 0.5;
    public static final double JUMP_MOMENTUM = 0.42;

    public static double hypot(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    public static double magnitude(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    public static double getMotionMultiplier(boolean onGround, boolean inWater, boolean inLava) {
        if (inWater) return WATER_DRAG;
        if (inLava) return LAVA_DRAG;
        if (onGround) return GROUND_DRAG;
        return 1.0;
    }

    public static double calculateFriction(boolean onGround, boolean inWater, boolean inLava, boolean onIce) {
        if (inWater) return 0.8;
        if (inLava) return 0.5;
        if (onIce) return 0.98;
        return onGround ? 0.546 : 0.91;
    }

    public static double calculateJumpVelocity(double jumpBoostAmplifier) {
        double jumpVelocity = JUMP_MOMENTUM;
        if (jumpBoostAmplifier > 0) {
            jumpVelocity += 0.1 * jumpBoostAmplifier;
        }
        return jumpVelocity;
    }

    public static double applySpeedEffect(double speed, int amplifier, double movementSpeed) {
        return movementSpeed * (1.0 + 0.2 * amplifier);
    }

    public static double applySlownessEffect(double speed, int amplifier, double movementSpeed) {
        return movementSpeed * (1.0 - 0.15 * amplifier);
    }

    public static double getTerminalVelocity() {
        return -3.92;
    }

    public static double gcd(double a, double b) {
        if (a < 1.0E-4) return b;
        if (b < 1.0E-4) return a;
        if (a < b) return gcd(b, a);
        return gcd(b, a % b);
    }

    public static double getDirection(double current, double target, double speed) {
        double diff = target - current;
        double maxChange = speed * 0.6;
        if (diff > maxChange) diff = maxChange;
        if (diff < -maxChange) diff = -maxChange;
        return current + diff;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
