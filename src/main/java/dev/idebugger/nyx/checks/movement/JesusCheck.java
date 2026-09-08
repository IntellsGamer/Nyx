package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Water-walking ("jesus") detection based on claimed on-ground state over
 * liquid, not raw speed thresholds.
 *
 * Two defects in the old version:
 *  1. Lily pads are walkable in vanilla but isSolid() is false for them, so
 *     walking across lily pads tripped the >0.15 speed cap — a false positive
 *     on 100% vanilla behaviour.
 *  2. The speed threshold meant a stationary or slowly-walking water-walker
 *     was completely invisible: the exploit only "counted" if the cheater
 *     moved fast.
 *
 * The physics truth: a player may only be onGround above a liquid when
 * standing on a walkable surface (lily pad, or ice/frosted ice over water —
 * which isSolid covers) or inside a boat. Everything else is the exploit
 * state, detectable even at zero speed, because vanilla buoyancy always
 * applies: you either sink (in liquid) or fall (above it).
 */
@CheckData(name = "Jesus", description = "Detects water walking exploits")
public class JesusCheck extends Check {

    // Riptide in/above water is a legit high-speed launch, never water-walking.
    private static final long RIPTIDE_GRACE_MS = 4000;

    // Consecutive impossible on-ground-over-liquid ticks before flagging. A
    // single tick can be a lag desync; 4+ ticks (~0.2s) is a sustained claim.
    private static final int WALK_TICKS_TO_FLAG = 4;

    private final Map<UUID, Integer> groundOverLiquidTicks = new ConcurrentHashMap<>();

    public JesusCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public void onPlayerQuit(UUID uuid) {
        groundOverLiquidTicks.remove(uuid);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;

        if (data.isGliding()) return;
        if (data.isInVehicle()) return;
        if (data.getPlayer().isFlying()) return;
        // Riptide in/above water is a legit high-speed launch, never water-walking.
        if (System.currentTimeMillis() - data.getLastRiptideTime() < RIPTIDE_GRACE_MS) return;

        var current = data.getPositionHistory().peekFirst();
        if (current == null) return;

        Location loc = current.location();
        if (!isAboveLiquid(loc)) {
            groundOverLiquidTicks.remove(data.getUuid());
            return;
        }

        UUID uuid = data.getUuid();

        if (data.isInWater() || data.isInLava()) {
            // Submerged and surfing: water surface movement above vanilla
            // swim speed is still a valid signal (water-walking while wet).
            double speed = data.getHorizontalSpeed();
            if (speed > 0.25) {
                flag(data, String.format("Surf S:%.3f", speed));
            }
            groundOverLiquidTicks.remove(uuid);
            return;
        }

        // Not inside the liquid. If the player claims to be onGround while
        // only liquid is beneath them, that is impossible in vanilla —
        // unless they stand on a walkable surface like a lily pad.
        if (data.isOnGround()) {
            Block feet = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Block below = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            if (isWalkableSurface(feet) || isWalkableSurface(below)) {
                groundOverLiquidTicks.remove(uuid);
                return;
            }
            int ticks = groundOverLiquidTicks.merge(uuid, 1, Integer::sum);
            if (ticks >= WALK_TICKS_TO_FLAG) {
                groundOverLiquidTicks.put(uuid, 0);
                flag(data, String.format("Walk S:%.3f T:%d", data.getHorizontalSpeed(), ticks));
            }
        } else {
            // Airborne above liquid: normal jump/fall arc, no claim being made.
            groundOverLiquidTicks.merge(uuid, -1, Integer::sum);
            groundOverLiquidTicks.remove(uuid, 0);
        }
    }

    /** Lily pads are walkable despite isSolid() being false; other "thin" surfaces are solid already. */
    private boolean isWalkableSurface(Block block) {
        Material type = block.getType();
        if (type == Material.LILY_PAD) return true;
        if (type == Material.ICE || type == Material.FROSTED_ICE) return true;
        return type.isSolid();
    }

    private boolean isAboveLiquid(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        Block feet = world.getBlockAt(x, y, z);
        if (feet.getType().isSolid() && feet.getType() != Material.LILY_PAD) return false;

        Block below = world.getBlockAt(x, y - 1, z);
        if (below.getType().isSolid() && below.getType() != Material.LILY_PAD) return false;

        for (int dy = 1; dy <= 3; dy++) {
            if (isLiquid(world.getBlockAt(x, y - dy, z))) return true;
        }
        return false;
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER
            || type == Material.LAVA
            || type == Material.BUBBLE_COLUMN
            || (block.getBlockData() instanceof Waterlogged);
    }
}
