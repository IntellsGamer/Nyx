package dev.nyx.data;

import dev.nyx.Nyx;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class NyxPlayerData {

    private final Player player;
    private final UUID uuid;

    private final ConcurrentLinkedDeque<MovementSnapshot> positionHistory;
    private final ConcurrentLinkedDeque<RotationSnapshot> rotationHistory;
    private final ConcurrentLinkedDeque<Long> transactionTimestamps;
    private final ConcurrentHashMap<String, Integer> violations;
    private final ConcurrentHashMap<Integer, Boolean> pendingTransactions;

    private Vector velocity;
    private Vector acceleration;
    private double deltaX, deltaY, deltaZ;
    private double lastDeltaX, lastDeltaY, lastDeltaZ;
    private double horizontalSpeed;
    private double verticalSpeed;
    private boolean onGround;
    private boolean lastOnGround;
    private boolean inWater;
    private boolean inLava;
    private boolean inWeb;
    private boolean onIce;
    private IceType iceType = IceType.NONE;
    private boolean onSlime;
    private boolean onSoulSand;
    private boolean onHoney;
    private boolean climbing;
    private boolean gliding;
    private boolean wasGliding;
    private long lastGlideTime;
    private long glideStartTime;
    private boolean swimming;
    private boolean sneaking;
    private boolean sprinting;
    private boolean inVehicle;
    private long lastVehicleEnterTime;
    private boolean handRaised;
    private long lastHandRaisedTime;
    private double packetRawY;
    private boolean packetRawOnGround;
    private boolean wasPositionPacket;
    private double lastPosPacketY = Double.NaN;
    private boolean lastPosPacketOnGround;
    private boolean prevPosPacketOnGround;
    private double accumulatedPacketFall;
    private boolean wurstPatternDetected;
    private Location lastSafeLocation;
    private int nofallCorrections;
    private boolean startGlidingThisTick;
    private boolean startGlidingLastTick;
    private boolean clientSprinting;
    private boolean tryingToRiptide;
    private long lastRiptideTime;
    private long lastFireworkTime;
    private boolean glideWithoutJump;
    private float vehicleForward;
    private float vehicleHorizontal;

    private long lastAttackTime;
    private int attackCps;
    private final Deque<Long> attackTimes;
    private int cps;

    private long lastTransactionId;
    private long lastTransactionTimestamp;
    private long joinTime;

    private boolean exempt;
    private boolean alerted;

    private int ping;
    private long lastVelocityTime;
    private int serverAirTicks;
    private double serverFallDistance;

    private int lastAttackedEntityId = -1;
    private boolean hasInteractedThisTick;
    private int interactedEntitiesThisTick;
    private int lastInteractEntityId = -1;
    private boolean sentAnimationSinceLastAttack;
    private boolean sentAttack;
    private boolean sentAttackThisTick;
    private boolean sentAnimationThisTick;
    private long lastAnimationTime;
    private float lastDeltaYaw;
    private float lastDeltaPitch;

    private int elytraStartPacketCount;
    private long lastRightClickTime;
    private final Deque<Long> rightClickTimes;
    private int rightClickCps;

    private long digStopCount;
    private long lastDigStartTime;
    private long lastDigCompleteTime;
    private int consecutiveBreaks;

    private int placeCountThisTick;
    private long lastPlaceTime;
    private int placeBlockX, placeBlockY, placeBlockZ;
    private int lastPlaceBlockX, lastPlaceBlockY, lastPlaceBlockZ;
    private int placeFace;
    private int lastPlaceFace;
    private boolean placedScaffoldThisTick;

    private final Deque<ServerVelocity> serverVelocityBuffer = new ArrayDeque<>();
    private static final long SERVER_VELOCITY_TIMEOUT_MS = 3000;
    private double velocityBuffer;
    private int serverVelocityBufferTicks;

    private static final int MAX_HISTORY = 30;
    private static final double TELEPORT_THRESHOLD = 10.0;

    public NyxPlayerData(Player player) {
        this.player = player;
        this.uuid = player.getUniqueId();
        this.positionHistory = new ConcurrentLinkedDeque<>();
        this.rotationHistory = new ConcurrentLinkedDeque<>();
        this.transactionTimestamps = new ConcurrentLinkedDeque<>();
        this.violations = new ConcurrentHashMap<>();
        this.pendingTransactions = new ConcurrentHashMap<>();
        this.attackTimes = new ArrayDeque<>();
        this.rightClickTimes = new ArrayDeque<>();
        this.velocity = new Vector();
        this.acceleration = new Vector();
        this.joinTime = System.currentTimeMillis();
        this.lastTransactionId = 0;
        this.alerted = false;
    }

    public void addMovementSnapshot(Location to, boolean onGround) {
        MovementSnapshot last = positionHistory.peekFirst();

        if (last != null) {
            double dist = Math.sqrt(
                Math.pow(to.getX() - last.location().getX(), 2) +
                Math.pow(to.getY() - last.location().getY(), 2) +
                Math.pow(to.getZ() - last.location().getZ(), 2)
            );
            if (dist > TELEPORT_THRESHOLD) {
                positionHistory.clear();
                this.deltaX = 0;
                this.deltaY = 0;
                this.deltaZ = 0;
                this.lastDeltaX = 0;
                this.lastDeltaY = 0;
                this.lastDeltaZ = 0;
                this.horizontalSpeed = 0;
                this.verticalSpeed = 0;
                this.acceleration = new Vector(0, 0, 0);
                this.lastOnGround = this.onGround;
                this.onGround = onGround;
                this.serverAirTicks = 0;
                this.serverFallDistance = 0;
                positionHistory.addFirst(new MovementSnapshot(to, onGround, System.nanoTime()));
                return;
            }
        }

        if (positionHistory.size() >= MAX_HISTORY) {
            positionHistory.pollLast();
        }
        MovementSnapshot snapshot = new MovementSnapshot(to, onGround, System.nanoTime());
        positionHistory.addFirst(snapshot);

        if (last != null) {
            double prevDeltaX = this.deltaX;
            double prevDeltaY = this.deltaY;
            double prevDeltaZ = this.deltaZ;

            this.deltaX = to.getX() - last.location().getX();
            this.deltaY = to.getY() - last.location().getY();
            this.deltaZ = to.getZ() - last.location().getZ();

            this.lastDeltaX = prevDeltaX;
            this.lastDeltaY = prevDeltaY;
            this.lastDeltaZ = prevDeltaZ;

            this.acceleration = new Vector(
                deltaX - prevDeltaX,
                deltaY - prevDeltaY,
                deltaZ - prevDeltaZ
            );
        }
        this.horizontalSpeed = Math.hypot(deltaX, deltaZ);
        this.verticalSpeed = Math.abs(deltaY);
        this.lastOnGround = this.onGround;
        this.onGround = onGround;

        if (onGround) {
            this.serverAirTicks = 0;
        } else {
            this.serverAirTicks++;
        }
    }

    public void addRotationSnapshot(float yaw, float pitch) {
        if (rotationHistory.size() >= MAX_HISTORY) {
            rotationHistory.pollLast();
        }
        RotationSnapshot last = rotationHistory.peekFirst();
        rotationHistory.addFirst(new RotationSnapshot(yaw, pitch, System.nanoTime()));

        if (last != null) {
            this.lastDeltaYaw = Math.abs(yaw - last.yaw());
            this.lastDeltaPitch = Math.abs(pitch - last.pitch());
        }
    }

    public void recordTransaction(long id, long timestamp) {
        pendingTransactions.put((int) id, false);
        this.lastTransactionId = id;
        this.lastTransactionTimestamp = timestamp;
    }

    public void confirmTransaction(long id) {
        pendingTransactions.put((int) id, true);
        if (transactionTimestamps.size() >= 20) {
            transactionTimestamps.pollLast();
        }
        transactionTimestamps.addFirst(System.currentTimeMillis());
    }

    public long getTicksSinceJoin() {
        return (System.currentTimeMillis() - joinTime) / 50L;
    }

    public long getPingAdjustedTimestamp() {
        return System.currentTimeMillis() - (ping / 2L);
    }

    public void recordAttack() {
        long now = System.currentTimeMillis();
        this.lastAttackTime = now;
        attackTimes.addLast(now);
        while (!attackTimes.isEmpty() && attackTimes.peekFirst() < now - 1000) {
            attackTimes.pollFirst();
        }
        this.attackCps = attackTimes.size();
    }

    public void tickViolations() {
        Nyx plugin = Nyx.get();
        violations.replaceAll((check, vl) -> {
            var cc = plugin.getNyxConfig().getCheckConfig(check);
            int decay = cc != null ? cc.decay() : 1;
            int newVl = vl - decay;
            return Math.max(newVl, 0);
        });
    }

    public void addViolation(String check) {
        violations.merge(check, 1, Integer::sum);
    }

    public void setViolationFromStorage(String check, int vl) {
        if (vl > 0) {
            violations.put(check, vl);
        }
    }

    public int getViolations(String check) {
        return violations.getOrDefault(check, 0);
    }

    public boolean hasViolation(String check) {
        Nyx plugin = Nyx.get();
        var cc = plugin.getNyxConfig().getCheckConfig(check);
        int max = cc != null ? cc.maxViolations() : 30;
        return getViolations(check) >= max;
    }

    public int getServerAirTicks() { return serverAirTicks; }
    public double getServerFallDistance() { return serverFallDistance; }
    public void setServerFallDistance(double d) { this.serverFallDistance = Math.max(0, d); }
    public void resetFallDistance() { this.serverFallDistance = 0; }

    public double getPacketRawY() { return packetRawY; }
    public boolean isPacketRawOnGround() { return packetRawOnGround; }
    public boolean isWasPositionPacket() { return wasPositionPacket; }
    public void setWasPositionPacket(boolean v) { this.wasPositionPacket = v; }
    public void setRawPacket(double y, boolean onGround) { this.packetRawY = y; this.packetRawOnGround = onGround; }
    public void setRawGround(boolean onGround) { this.packetRawOnGround = onGround; }

    public void updatePositionFromPacket(double y, boolean onGround) {
        if (Double.isNaN(lastPosPacketY)) {
            this.lastPosPacketY = y;
            this.lastPosPacketOnGround = onGround;
            this.prevPosPacketOnGround = onGround;
            this.accumulatedPacketFall = 0;
            return;
        }

        this.prevPosPacketOnGround = this.lastPosPacketOnGround;
        double deltaY = y - this.lastPosPacketY;

        // Teleport/respawn: Y change > 5 blocks is impossible for normal movement
        if (Math.abs(deltaY) > 5.0) {
            this.lastPosPacketY = y;
            this.lastPosPacketOnGround = onGround;
            this.accumulatedPacketFall = 0;
            return;
        }

        if (deltaY < 0 && !onGround) {
            this.accumulatedPacketFall += Math.abs(deltaY);
        }

        this.lastPosPacketY = y;
        this.lastPosPacketOnGround = onGround;
    }

    public double getAccumulatedPacketFall() { return accumulatedPacketFall; }
    public boolean isWurstPatternDetected() { return wurstPatternDetected; }
    public void setWurstPatternDetected(boolean v) { this.wurstPatternDetected = v; }
    public Location getLastSafeLocation() { return lastSafeLocation; }
    public void setLastSafeLocation(Location loc) { this.lastSafeLocation = loc; }
    public int getNofallCorrections() { return nofallCorrections; }
    public void incrementNofallCorrections() { this.nofallCorrections++; }
    public void resetNofallCorrections() { this.nofallCorrections = 0; }
    public boolean getPrevPosPacketOnGround() { return prevPosPacketOnGround; }
    public void resetAccumulatedPacketFall() { this.accumulatedPacketFall = 0; }
    public double getLastPosPacketY() { return lastPosPacketY; }
    public boolean isLastPosPacketOnGround() { return lastPosPacketOnGround; }

    public boolean isExempt() { return exempt; }
    public void setExempt(boolean exempt) { this.exempt = exempt; }

    public boolean isAlerted() { return alerted; }
    public void setAlerted(boolean alerted) { this.alerted = alerted; }

    public Player getPlayer() { return player; }
    public UUID getUuid() { return uuid; }
    public Vector getVelocity() { return velocity; }
    public void setVelocity(Vector velocity) { this.velocity = velocity; }

    public void recordServerVelocity(Vector vec, long timestamp) {
        synchronized (serverVelocityBuffer) {
            serverVelocityBuffer.addLast(new ServerVelocity(vec.clone(), timestamp));
            if (serverVelocityBuffer.size() > 3) {
                serverVelocityBuffer.pollFirst();
            }
        }
    }

    public ServerVelocity peekServerVelocity() {
        synchronized (serverVelocityBuffer) {
            long now = System.currentTimeMillis();
            ServerVelocity latest = null;
            for (ServerVelocity sv : serverVelocityBuffer) {
                if (now - sv.timestamp() > SERVER_VELOCITY_TIMEOUT_MS) continue;
                if (latest == null || sv.timestamp() > latest.timestamp()) {
                    latest = sv;
                }
            }
            return latest;
        }
    }

    public boolean hasServerVelocity() {
        return peekServerVelocity() != null;
    }

    public void clearServerVelocity() {
        synchronized (serverVelocityBuffer) {
            serverVelocityBuffer.clear();
        }
        this.serverVelocityBufferTicks = 0;
    }

    public double getVelocityBuffer() { return velocityBuffer; }
    public void addVelocityBuffer(double delta) {
        this.velocityBuffer = Math.max(0, this.velocityBuffer + delta);
    }
    public void resetVelocityBuffer(double start) { this.velocityBuffer = Math.max(0, start); }
    public int getServerVelocityBufferTicks() { return serverVelocityBufferTicks; }
    public void incrementServerVelocityBufferTicks() { this.serverVelocityBufferTicks++; }
    public void resetServerVelocityBufferTicks() { this.serverVelocityBufferTicks = 0; }

    public Vector getAcceleration() { return acceleration; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public double getDeltaZ() { return deltaZ; }
    public double getLastDeltaX() { return lastDeltaX; }
    public double getLastDeltaY() { return lastDeltaY; }
    public double getLastDeltaZ() { return lastDeltaZ; }
    public double getHorizontalSpeed() { return horizontalSpeed; }
    public double getVerticalSpeed() { return verticalSpeed; }
    public boolean isOnGround() { return onGround; }
    public boolean isLastOnGround() { return lastOnGround; }
    public boolean isInWater() { return inWater; }
    public void setInWater(boolean inWater) { this.inWater = inWater; }
    public boolean isInLava() { return inLava; }
    public void setInLava(boolean inLava) { this.inLava = inLava; }
    public boolean isInWeb() { return inWeb; }
    public void setInWeb(boolean inWeb) { this.inWeb = inWeb; }
    public boolean isOnIce() { return onIce; }
    public void setOnIce(boolean onIce) { this.onIce = onIce; }
    public IceType getIceType() { return iceType; }
    public void setIceType(IceType iceType) { this.iceType = iceType; }
    public boolean isOnSlime() { return onSlime; }
    public void setOnSlime(boolean onSlime) { this.onSlime = onSlime; }
    public boolean isOnSoulSand() { return onSoulSand; }
    public void setOnSoulSand(boolean onSoulSand) { this.onSoulSand = onSoulSand; }
    public boolean isOnHoney() { return onHoney; }
    public void setOnHoney(boolean onHoney) { this.onHoney = onHoney; }
    public boolean isClimbing() { return climbing; }
    public void setClimbing(boolean climbing) { this.climbing = climbing; }
    public boolean isGliding() { return gliding; }
    public void setGliding(boolean gliding) {
        this.wasGliding = this.gliding;
        boolean wasGliding = this.gliding;
        this.gliding = gliding;
        if (gliding) {
            this.lastGlideTime = System.currentTimeMillis();
            if (!wasGliding) {
                this.glideStartTime = System.currentTimeMillis();
            }
        }
    }
    public boolean isWasGliding() { return wasGliding; }
    public long getLastGlideTime() { return lastGlideTime; }
    public long getGlideStartTime() { return glideStartTime; }
    public boolean isHandRaised() { return handRaised; }
    public void setHandRaised(boolean v) {
        if (!this.handRaised && v) {
            this.lastHandRaisedTime = System.currentTimeMillis();
        }
        this.handRaised = v;
    }
    public long getLastHandRaisedTime() { return lastHandRaisedTime; }
    public boolean isSwimming() { return swimming; }
    public void setSwimming(boolean swimming) { this.swimming = swimming; }
    public boolean isSneaking() { return sneaking; }
    public void setSneaking(boolean sneaking) { this.sneaking = sneaking; }
    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean sprinting) { this.sprinting = sprinting; }
    public boolean isInVehicle() { return inVehicle; }
    public void setInVehicle(boolean inVehicle) { this.inVehicle = inVehicle; }
    public long getLastVehicleEnterTime() { return lastVehicleEnterTime; }
    public void setLastVehicleEnterTime(long v) { this.lastVehicleEnterTime = v; }
    public long getLastAttackTime() { return lastAttackTime; }
    public int getAttackCps() { return attackCps; }
    public Deque<Long> getAttackTimes() { return attackTimes; }
    public int getCps() { return cps; }
    public long getLastTransactionId() { return lastTransactionId; }
    public long getLastTransactionTimestamp() { return lastTransactionTimestamp; }
    public int getPing() { return ping; }
    public void setPing(int ping) { this.ping = ping; }
    public long getLastVelocityTime() { return lastVelocityTime; }
    public void setLastVelocityTime(long lastVelocityTime) { this.lastVelocityTime = lastVelocityTime; }
    public ConcurrentLinkedDeque<MovementSnapshot> getPositionHistory() { return positionHistory; }
    public ConcurrentLinkedDeque<RotationSnapshot> getRotationHistory() { return rotationHistory; }
    public ConcurrentHashMap<String, Integer> getViolationMap() { return violations; }

    public int getLastAttackedEntityId() { return lastAttackedEntityId; }
    public void setLastAttackedEntityId(int id) { this.lastAttackedEntityId = id; }
    public boolean isHasInteractedThisTick() { return hasInteractedThisTick; }
    public void setHasInteractedThisTick(boolean v) { this.hasInteractedThisTick = v; }
    public int getInteractedEntitiesThisTick() { return interactedEntitiesThisTick; }
    public void setInteractedEntitiesThisTick(int v) { this.interactedEntitiesThisTick = v; }
    public int getLastInteractEntityId() { return lastInteractEntityId; }
    public void setLastInteractEntityId(int id) { this.lastInteractEntityId = id; }
    public boolean isSentAnimationSinceLastAttack() { return sentAnimationSinceLastAttack; }
    public void setSentAnimationSinceLastAttack(boolean v) { this.sentAnimationSinceLastAttack = v; }
    public boolean isClientSprinting() { return clientSprinting; }
    public void setClientSprinting(boolean v) { this.clientSprinting = v; }

    public boolean isStartGlidingThisTick() { return startGlidingThisTick; }
    public void setStartGlidingThisTick(boolean v) { this.startGlidingThisTick = v; }
    public boolean isStartGlidingLastTick() { return startGlidingLastTick; }
    public void setStartGlidingLastTick(boolean v) { this.startGlidingLastTick = v; }
    public int getElytraStartPacketCount() { return elytraStartPacketCount; }
    public void incrementElytraStartPacketCount() { this.elytraStartPacketCount++; }
    public void resetElytraStartPacketCount() { this.elytraStartPacketCount = 0; }
    public boolean isTryingToRiptide() { return tryingToRiptide; }
    public void setTryingToRiptide(boolean v) { this.tryingToRiptide = v; }
    public long getLastRiptideTime() { return lastRiptideTime; }
    public void setLastRiptideTime(long v) { this.lastRiptideTime = v; }
    public long getLastFireworkTime() { return lastFireworkTime; }
    public void setLastFireworkTime(long v) { this.lastFireworkTime = v; }
    public boolean isGlideWithoutJump() { return glideWithoutJump; }
    public void setGlideWithoutJump(boolean v) { this.glideWithoutJump = v; }

    public float getVehicleForward() { return vehicleForward; }
    public void setVehicleForward(float v) { this.vehicleForward = v; }
    public float getVehicleHorizontal() { return vehicleHorizontal; }
    public void setVehicleHorizontal(float v) { this.vehicleHorizontal = v; }

    public boolean isSentAttack() { return sentAttack; }
    public void setSentAttack(boolean v) { this.sentAttack = v; }
    public boolean isSentAttackThisTick() { return sentAttackThisTick; }
    public void setSentAttackThisTick(boolean v) { this.sentAttackThisTick = v; }
    public boolean isSentAnimationThisTick() { return sentAnimationThisTick; }
    public void setSentAnimationThisTick(boolean v) { this.sentAnimationThisTick = v; }
    public long getLastAnimationTime() { return lastAnimationTime; }
    public void setLastAnimationTime(long t) { this.lastAnimationTime = t; }
    public float getLastDeltaYaw() { return lastDeltaYaw; }
    public void setLastDeltaYaw(float v) { this.lastDeltaYaw = v; }
    public float getLastDeltaPitch() { return lastDeltaPitch; }

    public long getLastRightClickTime() { return lastRightClickTime; }
    public int getRightClickCps() { return rightClickCps; }
    public Deque<Long> getRightClickTimes() { return rightClickTimes; }
    public void recordRightClick() {
        long now = System.currentTimeMillis();
        this.lastRightClickTime = now;
        rightClickTimes.addLast(now);
        while (!rightClickTimes.isEmpty() && rightClickTimes.peekFirst() < now - 1000) {
            rightClickTimes.pollFirst();
        }
        this.rightClickCps = rightClickTimes.size();
    }

    public long getDigStopCount() { return digStopCount; }
    public void incrementDigStopCount() { this.digStopCount++; }
    public void resetDigStopCount() { this.digStopCount = 0; }
    public long getLastDigStartTime() { return lastDigStartTime; }
    public void setLastDigStartTime(long t) { this.lastDigStartTime = t; }
    public long getLastDigCompleteTime() { return lastDigCompleteTime; }
    public void setLastDigCompleteTime(long t) { this.lastDigCompleteTime = t; }
    public int getConsecutiveBreaks() { return consecutiveBreaks; }
    public void setConsecutiveBreaks(int v) { this.consecutiveBreaks = v; }
    public void incrementConsecutiveBreaks() { this.consecutiveBreaks++; }
    public void resetConsecutiveBreaks() { this.consecutiveBreaks = 0; }
    public void setLastDeltaPitch(float v) { this.lastDeltaPitch = v; }

    public void resetTickState() {
        this.startGlidingLastTick = this.startGlidingThisTick;
        this.startGlidingThisTick = false;
        this.hasInteractedThisTick = false;
        this.interactedEntitiesThisTick = 0;
        this.sentAttackThisTick = false;
        this.sentAnimationThisTick = false;
        this.elytraStartPacketCount = 0;
        this.placeCountThisTick = 0;
        this.lastPlaceBlockX = this.placeBlockX;
        this.lastPlaceBlockY = this.placeBlockY;
        this.lastPlaceBlockZ = this.placeBlockZ;
        this.lastPlaceFace = this.placeFace;
    }

    public int getPlaceCountThisTick() { return placeCountThisTick; }
    public void incrementPlaceCountThisTick() { this.placeCountThisTick++; }
    public long getLastPlaceTime() { return lastPlaceTime; }
    public void setLastPlaceTime(long t) { this.lastPlaceTime = t; }
    public int getPlaceBlockX() { return placeBlockX; }
    public int getPlaceBlockY() { return placeBlockY; }
    public int getPlaceBlockZ() { return placeBlockZ; }
    public void setPlaceBlock(int x, int y, int z) { this.placeBlockX = x; this.placeBlockY = y; this.placeBlockZ = z; }
    public int getLastPlaceBlockX() { return lastPlaceBlockX; }
    public int getLastPlaceBlockY() { return lastPlaceBlockY; }
    public int getLastPlaceBlockZ() { return lastPlaceBlockZ; }
    public int getPlaceFace() { return placeFace; }
    public int getLastPlaceFace() { return lastPlaceFace; }
    public void setPlaceFace(int face) { this.placeFace = face; }
    public boolean isPlacedScaffoldThisTick() { return placedScaffoldThisTick; }
    public void setPlacedScaffoldThisTick(boolean v) { this.placedScaffoldThisTick = v; }

    public record MovementSnapshot(Location location, boolean onGround, long timestamp) {}
    public record RotationSnapshot(float yaw, float pitch, long timestamp) {}
    public record ServerVelocity(Vector vector, long timestamp) {}

    public enum IceType {
        NONE(0.28),
        ICE(0.80),
        PACKED_ICE(0.95),
        BLUE_ICE(1.30),
        FROSTED_ICE(0.80);

        private final double maxSpeed;

        IceType(double maxSpeed) {
            this.maxSpeed = maxSpeed;
        }

        public double getMaxSpeed() {
            return maxSpeed;
        }

        public static IceType fromMaterial(Material material) {
            return switch (material) {
                case ICE -> ICE;
                case PACKED_ICE -> PACKED_ICE;
                case BLUE_ICE -> BLUE_ICE;
                case FROSTED_ICE -> FROSTED_ICE;
                default -> NONE;
            };
        }
    }
}
