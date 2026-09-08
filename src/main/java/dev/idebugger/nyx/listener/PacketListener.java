package dev.idebugger.nyx.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckManager;
import dev.idebugger.nyx.checks.combat.*;
import dev.idebugger.nyx.checks.other.*;
import dev.idebugger.nyx.checks.movement.*;
import dev.idebugger.nyx.checks.vehicle.*;
import dev.idebugger.nyx.checks.elytra.ElytraACheck;
import dev.idebugger.nyx.checks.elytra.ElytraBCheck;
import dev.idebugger.nyx.checks.elytra.ElytraCCheck;
import dev.idebugger.nyx.checks.elytra.ExtraElytraCheck;
import dev.idebugger.nyx.checks.combat.AimAssistCheck;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Strider;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketListener extends PacketListenerAbstract {

    private final Nyx plugin;
    private final Map<UUID, Long> transactionMap;
    private final List<Check> movementChecks;
    private final List<Check> combatChecks;
    private final List<Check> otherChecks;

    public PacketListener(Nyx plugin) {
        this.plugin = plugin;
        this.transactionMap = new ConcurrentHashMap<>();
        this.movementChecks = new ArrayList<>();
        this.combatChecks = new ArrayList<>();
        this.otherChecks = new ArrayList<>();
        registerChecks();
    }

    private void registerChecks() {
        CheckManager cm = plugin.getCheckManager();

        movementChecks.addAll(List.of(
            new SpeedCheck(plugin),
            new FlyCheck(plugin),
            new NoFallCheck(plugin),
            new TimerCheck(plugin),
            new PhaseCheck(plugin),
            new JesusCheck(plugin),
            new BoatFlyCheck(plugin),
            new SnowShoeCheck(plugin),
            new EntitySpeedCheck(plugin),
            new EntityControlCheck(plugin),
            new BoatCheck(plugin),
            new ElytraACheck(plugin),
            new ElytraBCheck(plugin),
            new ElytraCCheck(plugin),
            new ExtraElytraCheck(plugin)
        ));

        combatChecks.addAll(List.of(
            new ReachCheck(plugin),
            new HitBoxCheck(plugin),
            new SelfInteractCheck(plugin),
            new MultiInteractCheck(plugin),
            new NoSwingCheck(plugin),
            new AttackWhileUsingCheck(plugin),

            new AimModulo360Check(plugin),
            new AutoClickerCheck(plugin),
            new VelocityCheck(plugin),
            new TridentACheck(plugin),
            new TridentBCheck(plugin)
        ));

        otherChecks.addAll(List.of(
            new BadPacketsCheck(plugin),
            new InventoryMoveCheck(plugin),
            new FastUseCheck(plugin),
            new FastBreakCheck(plugin),
            new FastPlaceCheck(plugin),
            new WebCheck(plugin)
        ));

        combatChecks.add(new AimAssistCheck(plugin));
        combatChecks.add(new KillAuraCheck(plugin));

        // Scaffold runs only from its dedicated block-place packet handler, so
        // it is registered (for config/VL lookup) but excluded from the generic
        // per-movement-tick run loop to avoid inspecting stale placement state.
        cm.register(new ScaffoldCheck(plugin));

        for (Check check : movementChecks) cm.register(check);
        for (Check check : combatChecks) cm.register(check);
        for (Check check : otherChecks) cm.register(check);
    }

    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUUID();
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        NyxPlayerData data = plugin.getPlayerDataManager().getData(player);

        PacketTypeCommon packetType = event.getPacketType();

        if (isMovementPacket(packetType)) {
            data.resetTickState();
            handleMovement(event, data, player);

        } else if (packetType == PacketType.Play.Client.STEER_VEHICLE) {
            WrapperPlayClientSteerVehicle steer = new WrapperPlayClientSteerVehicle(event);

            data.setVehicleForward(steer.getForward());
            data.setVehicleHorizontal(steer.getSideways());

            // Impossible input values need no Bukkit state: cancel synchronously.
            if (Math.abs(steer.getForward()) > 0.98f || Math.abs(steer.getSideways()) > 0.98f) {
                EntitySpeedCheck speedCheck = plugin.getCheckManager().getCheck(EntitySpeedCheck.class);
                if (speedCheck != null && speedCheck.canRun(data)) {
                    speedCheck.flag(data, String.format("F:%.2f S:%.2f", steer.getForward(), steer.getSideways()));
                }
                event.setCancelled(true);
                return;
            }

            // Vehicle presence is entity state: defer to the owning region
            // thread instead of reading it on the netty thread.
            player.getScheduler().run(plugin, task -> {
                if (!player.isOnline() || player.isInsideVehicle()) return;
                EntitySpeedCheck spoofCheck = plugin.getCheckManager().getCheck(EntitySpeedCheck.class);
                if (spoofCheck != null && spoofCheck.canRun(data)) {
                    spoofCheck.flag(data, "Vehicle spoof (no vehicle)");
                }
            }, null);

        } else if (packetType == PacketType.Play.Client.STEER_BOAT) {
            // Boat spoof validation reads vehicle state; run it on the region
            // thread (flags still work, only the packet itself cannot be
            // retroactively cancelled from there, which is fine for a flag).
            player.getScheduler().run(plugin, task -> {
                if (!player.isOnline()) return;
                BoatCheck boatCheck = plugin.getCheckManager().getCheck(BoatCheck.class);
                if (boatCheck == null || !boatCheck.canRun(data)) return;

                if (!player.isInsideVehicle()) {
                    boatCheck.flag(data, "Spoofed boat (not in vehicle)");
                    return;
                }

                Entity vehicle = player.getVehicle();
                if (vehicle != null && !(vehicle instanceof Boat)) {
                    boatCheck.flag(data, "Spoofed boat (vehicle=" + vehicle.getType().name() + ")");
                }
            }, null);

        } else if (packetType == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);

            if (action.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                data.setClientSprinting(true);
            } else if (action.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                data.setClientSprinting(false);

            } else if (action.getAction() == WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA) {

                data.incrementElytraStartPacketCount();
                data.setStartGlidingThisTick(true);

                // isGliding()/isInWaterOrBubbleColumn() are entity state: defer.
                player.getScheduler().run(plugin, task -> {
                    if (!player.isOnline()) return;

                    ElytraACheck elytraA = plugin.getCheckManager().getCheck(ElytraACheck.class);
                    if (elytraA != null && elytraA.canRun(data) && player.isGliding()) {
                        elytraA.flag(data, "Already gliding");
                        return;
                    }

                    ElytraBCheck elytraB = plugin.getCheckManager().getCheck(ElytraBCheck.class);
                    if (elytraB == null || !elytraB.canRun(data)) return;
                    if (player.isInWaterOrBubbleColumn()) {
                        // Water disables elytra jumping; this is a legitimate surface glide
                        data.setGlideWithoutJump(false);
                    } else if (data.isOnGround() || data.isLastOnGround()) {
                        elytraB.flag(data, "On ground");
                    } else {
                        data.setGlideWithoutJump(true);
                    }
                }, null);
            }

        } else if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging dig =
                new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging(event);
            var digAction = dig.getAction();

            if (digAction == com.github.retrooper.packetevents.protocol.player.DiggingAction.START_DIGGING) {
                data.setLastDigStartTime(System.currentTimeMillis());
                data.resetDigStopCount();
            } else if (digAction == com.github.retrooper.packetevents.protocol.player.DiggingAction.FINISHED_DIGGING) {
                data.incrementDigStopCount();
                data.setLastDigCompleteTime(System.currentTimeMillis());
                data.incrementConsecutiveBreaks();
            } else if (digAction == com.github.retrooper.packetevents.protocol.player.DiggingAction.CANCELLED_DIGGING) {
                data.resetDigStopCount();
                data.resetConsecutiveBreaks();
            }

            if (digAction == com.github.retrooper.packetevents.protocol.player.DiggingAction.RELEASE_USE_ITEM) {
                // Inventory + water state are Bukkit reads: defer to the
                // player's region thread (unsafe on netty, illegal on Folia).
                player.getScheduler().run(plugin, task -> {
                    if (!player.isOnline()) return;
                    ItemStack mainHand = player.getInventory().getItemInMainHand();
                    ItemStack offHand = player.getInventory().getItemInOffHand();

                    if (mainHand.getType() == Material.TRIDENT && mainHand.containsEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE)
                            || offHand.getType() == Material.TRIDENT && offHand.containsEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE)) {

                        data.setTryingToRiptide(true);

                        boolean inWater = player.isInWater();

                        TridentACheck tridentA = plugin.getCheckManager().getCheck(TridentACheck.class);
                        if (tridentA != null && tridentA.canRun(data) && !inWater) {
                            tridentA.flag(data, "Not in water");
                        }

                        long now = System.currentTimeMillis();
                        if (data.getLastRiptideTime() > 0 && now - data.getLastRiptideTime() < 450) {
                            TridentBCheck tridentB = plugin.getCheckManager().getCheck(TridentBCheck.class);
                            if (tridentB != null && tridentB.canRun(data)) {
                                tridentB.flag(data, "Freq:" + (now - data.getLastRiptideTime()) + "ms");
                            }
                        }
                        data.setLastRiptideTime(now);
                    }
                }, null);
            }

        } else if (packetType == PacketType.Play.Client.ANIMATION) {
            data.setSentAnimationThisTick(true);
            data.setSentAnimationSinceLastAttack(true);
            data.setLastAnimationTime(System.currentTimeMillis());

        } else if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            int targetId = interact.getEntityId();

            if (targetId == player.getEntityId()) {
                SelfInteractCheck selfCheck = plugin.getCheckManager().getCheck(SelfInteractCheck.class);
                if (selfCheck != null && selfCheck.canRun(data)) {
                    selfCheck.flag(data, "Self T:" + targetId);
                }
                event.setCancelled(true);
                return;
            }

            data.setHasInteractedThisTick(true);
            data.setInteractedEntitiesThisTick(data.getInteractedEntitiesThisTick() + 1);
            data.setLastInteractEntityId(targetId);

            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                data.recordAttack();
                data.setLastAttackedEntityId(targetId);
                data.setSentAttack(true);
                data.setSentAttackThisTick(true);
                data.setSentAnimationSinceLastAttack(false);

                // KillAura signals need entity + world state: evaluate on the
                // player's region thread (netty reads are illegal on Folia).
                player.getScheduler().run(plugin, task -> {
                    if (!player.isOnline()) return;
                    KillAuraCheck killAura = plugin.getCheckManager().getCheck(KillAuraCheck.class);
                    if (killAura != null && killAura.canRun(data)) {
                        killAura.handleAttack(data, player, targetId);
                    }
                }, null);
            }

        } else if (packetType == PacketType.Play.Client.USE_ITEM) {
            data.setAlerted(false);
            data.recordRightClick();
            // Inventory read deferred off the netty thread.
            player.getScheduler().run(plugin, task -> {
                if (!player.isOnline()) return;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == org.bukkit.Material.FIREWORK_ROCKET) {
                    data.setLastFireworkTime(System.currentTimeMillis());
                }
            }, null);

        } else if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            data.setAlerted(false);
            data.recordRightClick();

            WrapperPlayClientPlayerBlockPlacement place = new WrapperPlayClientPlayerBlockPlacement(event);
            var blockPos = place.getBlockPosition();
            if (blockPos != null) {
                data.incrementPlaceCountThisTick();
                data.setLastPlaceTime(System.currentTimeMillis());
                data.setPlaceBlock(blockPos.x, blockPos.y, blockPos.z);
                data.setPlaceFace(place.getFaceId());

                var cursor = place.getCursorPosition();
                if (cursor != null && (!Float.isFinite(cursor.x) || !Float.isFinite(cursor.y) || !Float.isFinite(cursor.z))) {
                    ScaffoldCheck scaffold = plugin.getCheckManager().getCheck(ScaffoldCheck.class);
                    if (scaffold != null && scaffold.canRun(data)) {
                        scaffold.flag(data, "InvalidCursor");
                    }
                }

                ScaffoldCheck scaffold = plugin.getCheckManager().getCheck(ScaffoldCheck.class);
                if (scaffold != null && scaffold.canRun(data)) {
                    scaffold.handle(data);
                }
            }

        } else if (packetType == PacketType.Play.Client.CLICK_WINDOW
            || packetType == PacketType.Play.Client.CLOSE_WINDOW) {
            data.setAlerted(false);

        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        UUID uuid = user.getUUID();
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            WrapperPlayServerPlayerPositionAndLook posPacket = new WrapperPlayServerPlayerPositionAndLook(event);
            handleSetback(player, posPacket);
        }

        if (packetType == PacketType.Play.Server.WINDOW_CONFIRMATION) {
            WrapperPlayServerWindowConfirmation transaction = new WrapperPlayServerWindowConfirmation(event);
            NyxPlayerData data = plugin.getPlayerDataManager().getData(player);
            if (data != null) {
                data.confirmTransaction(transaction.getActionId());
            }
        }

        if (packetType == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity velocityPacket = new WrapperPlayServerEntityVelocity(event);
            if (velocityPacket.getEntityId() == player.getEntityId()) {
                NyxPlayerData data = plugin.getPlayerDataManager().getData(player);
                if (data != null) {
                    long now = System.currentTimeMillis();
                    // A second knockback while one is still pending means a mob
                    // volley: several hits that legitimately cancel each other
                    // into almost no net motion. Remember it so the velocity
                    // check forgives the whole window instead of flagging.
                    boolean volley = data.hasServerVelocity();
                    var vec = velocityPacket.getVelocity();
                    org.bukkit.util.Vector applied = new org.bukkit.util.Vector(vec.x, vec.y, vec.z);
                    data.recordServerVelocity(applied, now);
                    data.setLastVelocityTime(now);
                    if (volley) {
                        data.setVelocityMultiHit(true);
                        data.setLastVelocityMultiHitTime(now);
                    }

                    // Wall carve-out (deferred: block reads are not allowed on
                    // the netty thread). If the expected knockback path runs
                    // into solid blocks, the collision legitimately eats the
                    // velocity and the velocity check must not demand it.
                    data.setVelocityBlockedByWall(false);
                    double horiz = Math.hypot(applied.getX(), applied.getZ());
                    if (horiz > 0.05 || Math.abs(applied.getY()) > 0.05) {
                        player.getScheduler().run(plugin, task -> {
                            if (!player.isOnline()) return;
                            data.setVelocityBlockedByWall(
                                isKnockbackPathBlocked(player, applied));
                        }, null);
                    }
                }
            }
        }

        if (packetType == PacketType.Play.Server.HURT_ANIMATION) {
            WrapperPlayServerHurtAnimation hurt = new WrapperPlayServerHurtAnimation(event);
            if (hurt.getEntityId() == player.getEntityId()) {
                NyxPlayerData data = plugin.getPlayerDataManager().getData(player);
                if (data != null) {
                    data.setLastDamageTime(System.currentTimeMillis());
                }
            }
        }
    }

    private boolean isMovementPacket(PacketTypeCommon type) {
        return type == PacketType.Play.Client.PLAYER_POSITION
            || type == PacketType.Play.Client.PLAYER_ROTATION
            || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
            || type == PacketType.Play.Client.PLAYER_FLYING;
    }

    /**
     * True when the expected knockback path from the player's current
     * position hits a solid block within the distance the velocity would
     * carry them. Runs on the owning region thread (caller defers).
     */
    private boolean isKnockbackPathBlocked(Player player, org.bukkit.util.Vector applied) {
        org.bukkit.Location loc = player.getLocation();
        double dx = applied.getX();
        double dy = applied.getY();
        double dz = applied.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.05) return false;

        // 3 full ticks of the decaying velocity is the practical span the
        // velocity check judges; a wall inside that span eats the motion.
        double spanX = dx * 2.5, spanY = dy * 2.5, spanZ = dz * 2.5;
        double span = Math.sqrt(spanX * spanX + spanY * spanY + spanZ * spanZ);
        if (span < 0.05) return false;

        // DDA voxel walk from the player's chest height along the span.
        double px = loc.getX(), py = loc.getY() + 1.0, pz = loc.getZ();
        double stepX = spanX / span * 0.5, stepY = spanY / span * 0.5, stepZ = spanZ / span * 0.5;
        int steps = (int) Math.ceil(span / 0.5);
        World world = player.getWorld();
        for (int i = 0; i < steps; i++) {
            px += stepX;
            py += stepY;
            pz += stepZ;
            Material type = world.getBlockAt(
                (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz)).getType();
            if (type.isCollidable() && !type.name().contains("SLAB") || isFullSolid(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFullSolid(Material type) {
        if (!type.isCollidable()) return false;
        String n = type.name();
        // Slabs/stairs/fences/panes partially block but leave movement paths;
        // only treat unambiguously full cubes as wall for this heuristic.
        return !n.endsWith("SLAB") && !n.endsWith("STAIRS") && !n.endsWith("FENCE")
            && !n.endsWith("WALL") && !n.endsWith("PANE") && !n.endsWith("CARPET")
            && !n.endsWith("TRAPDOOR") && !n.endsWith("DOOR");
    }

    private NyxPlayerData.IceType detectIce(World world, Location loc) {
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        int by = (int) Math.floor(loc.getY() - 0.01);
        for (int dy = 0; dy >= -1; dy--) {
            NyxPlayerData.IceType ice = NyxPlayerData.IceType.fromMaterial(world.getBlockAt(bx, by + dy, bz).getType());
            if (ice != NyxPlayerData.IceType.NONE) return ice;
        }
        return NyxPlayerData.IceType.NONE;
    }

    private void handleMovement(PacketReceiveEvent event, NyxPlayerData data, Player player) {
        PacketTypeCommon type = event.getPacketType();
        WrapperPlayClientPlayerFlying flyingPacket;
        if (type == PacketType.Play.Client.PLAYER_POSITION) {
            flyingPacket = new WrapperPlayClientPlayerPosition(event);
        } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            flyingPacket = new WrapperPlayClientPlayerRotation(event);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            flyingPacket = new WrapperPlayClientPlayerPositionAndRotation(event);
        } else {
            flyingPacket = new WrapperPlayClientPlayerFlying(event);
        }

        var pktLoc = flyingPacket.getLocation();
        boolean onGround = flyingPacket.isOnGround();
        boolean isPositionPacket = type == PacketType.Play.Client.PLAYER_POSITION
                                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;

        // All player / world access must happen on the owning region thread
        // (Folia / Moonrise).  We extract only the raw packet data above and
        // schedule the rest.
        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;

            double x, y, z;
            float yaw, pitch;
            if (pktLoc != null) {
                x = pktLoc.getX();
                y = pktLoc.getY();
                z = pktLoc.getZ();
                yaw = pktLoc.getYaw();
                pitch = pktLoc.getPitch();
            } else {
                Location loc = player.getLocation();
                x = loc.getX();
                y = loc.getY();
                z = loc.getZ();
                yaw = loc.getYaw();
                pitch = loc.getPitch();
            }

            data.setWasPositionPacket(isPositionPacket);

            if (isPositionPacket) {
                data.setRawPacket(y, onGround);
                data.updatePositionFromPacket(y, onGround);
            } else {
                data.setRawGround(onGround);
            }

            data.setPing(player.getPing());

            Location toLocation;
            if (isPositionPacket) {
                toLocation = new Location(player.getWorld(), x, y, z, yaw, pitch);

                // Teleport acknowledgment: the first position packet after a
                // server teleport (setbacks, pearls, plugin /tp) is a re-sync,
                // not a movement. Its delta crosses the teleport distance and
                // would otherwise contaminate every movement check — including
                // flagging players Nyx itself just setback (self-flag loop).
                org.bukkit.Location pendingTeleport = data.consumePendingTeleport();
                if (pendingTeleport != null) {
                    double tpDx = x - pendingTeleport.getX();
                    double tpDy = y - pendingTeleport.getY();
                    double tpDz = z - pendingTeleport.getZ();
                    boolean acknowledgesTeleport = tpDx * tpDx + tpDy * tpDy + tpDz * tpDz < 9.0; // 3-block tolerance
                    data.clearPendingTeleport();
                    if (acknowledgesTeleport) {
                        // Re-baseline: wipe pre-teleport history so the ack
                        // snapshot enters with zero deltas.
                        data.clearPositionHistory();
                        data.addMovementSnapshot(toLocation, onGround);
                        data.addRotationSnapshot(yaw, pitch);
                        data.setLastSafeLocation(toLocation.clone());
                        data.resetAccumulatedPacketFall();
                        data.updatePositionFromPacket(y, onGround);
                        data.setRawPacket(y, onGround);
                        runChecks(data);
                        return;
                    }
                    // Not acknowledging: fall through as normal movement (a
                    // cheater ignoring the teleport is handled by setback
                    // escalation, not by phantom deltas).
                }

                data.addMovementSnapshot(toLocation, onGround);
            } else {
                toLocation = player.getLocation();
            }
            data.addRotationSnapshot(yaw, pitch);

            data.recordTransaction(data.getLastTransactionId() + 1, System.currentTimeMillis());

            data.setInWater(player.isInWater());
            data.setInLava(player.isInLava());
            {
                NyxPlayerData.IceType ice = detectIce(player.getWorld(), toLocation);
                data.setIceType(ice);
                data.setOnIce(ice != NyxPlayerData.IceType.NONE);
                data.recordIce(ice);
                data.tickIceMomentum();
            }
            {
                boolean inWeb = false;
                boolean inPowderedSnow = false;
                int minX = (int) Math.floor(toLocation.getX() - 0.3);
                int maxX = (int) Math.floor(toLocation.getX() + 0.3);
                int minY = (int) Math.floor(toLocation.getY());
                int maxY = (int) Math.floor(toLocation.getY() + 1.8);
                int minZ = (int) Math.floor(toLocation.getZ() - 0.3);
                int maxZ = (int) Math.floor(toLocation.getZ() + 0.3);
                for (int bx = minX; bx <= maxX; bx++) {
                    for (int by = minY; by <= maxY; by++) {
                        for (int bz = minZ; bz <= maxZ; bz++) {
                            Material blockType = player.getWorld().getBlockAt(bx, by, bz).getType();
                            if (blockType == org.bukkit.Material.COBWEB) {
                                inWeb = true;
                            } else if (blockType == org.bukkit.Material.POWDER_SNOW) {
                                inPowderedSnow = true;
                            }
                        }
                    }
                }
                data.setInWeb(inWeb);
                data.setInPowderedSnow(inPowderedSnow);
            }
            data.setGliding(player.isGliding());
            data.setHandRaised(player.isHandRaised());
            data.setSwimming(player.isSwimming());
            data.setSneaking(player.isSneaking());
            data.setSprinting(player.isSprinting());
            boolean inVehicle = player.isInsideVehicle();
            if (inVehicle && !data.isInVehicle()) {
                data.setLastVehicleEnterTime(System.currentTimeMillis());
            }
            data.setInVehicle(inVehicle);

            if (player.isInsideVehicle()) {
                Entity vehicle = player.getVehicle();
                if (vehicle instanceof Pig || vehicle instanceof Strider) {
                    Material requiredItem = vehicle instanceof Pig ? Material.CARROT_ON_A_STICK : Material.WARPED_FUNGUS_ON_A_STICK;
                    ItemStack main = player.getInventory().getItemInMainHand();
                    ItemStack off = player.getInventory().getItemInOffHand();
                    if (main.getType() != requiredItem && off.getType() != requiredItem) {
                        EntityControlCheck ctrlCheck = plugin.getCheckManager().getCheck(EntityControlCheck.class);
                        if (ctrlCheck != null && ctrlCheck.canRun(data)) {
                            ctrlCheck.flag(data, "Missing " + requiredItem.name().toLowerCase());
                        }
                    }
                }
            }

            if (data.isGlideWithoutJump()) {
                data.setGlideWithoutJump(false);
                if (data.getDeltaY() <= 0 && !player.isInWaterOrBubbleColumn()) {
                    ElytraBCheck elytraB = plugin.getCheckManager().getCheck(ElytraBCheck.class);
                    if (elytraB != null && elytraB.canRun(data)) {
                        elytraB.flag(data, "No jump");
                    }
                }
            }

            if (data.isStartGlidingThisTick() && data.isStartGlidingLastTick()) {
                ElytraCCheck elytraC = plugin.getCheckManager().getCheck(ElytraCCheck.class);
                if (elytraC != null && elytraC.canRun(data)) {
                    elytraC.flag(data, "Too frequent");
                }
            }

            data.setTryingToRiptide(false);

            runChecks(data);
        }, null);
    }

    private void runChecks(NyxPlayerData data) {
        for (Check check : movementChecks) {
            check.runAsync(data);
        }
        for (Check check : combatChecks) {
            check.runAsync(data);
        }
        for (Check check : otherChecks) {
            check.runAsync(data);
        }
    }

    private void handleSetback(Player player, WrapperPlayServerPlayerPositionAndLook packet) {
        NyxPlayerData data = plugin.getPlayerDataManager().getData(player);
        if (data != null) {
            data.setAlerted(false);
        }
    }
}
