package dev.nyx.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckManager;
import dev.nyx.checks.combat.*;
import dev.nyx.checks.other.*;
import dev.nyx.checks.movement.*;
import dev.nyx.checks.vehicle.*;
import dev.nyx.checks.elytra.ElytraACheck;
import dev.nyx.checks.elytra.ElytraBCheck;
import dev.nyx.checks.elytra.ElytraCCheck;
import dev.nyx.checks.elytra.ExtraElytraCheck;
import dev.nyx.checks.combat.AimAssistCheck;
import dev.nyx.data.NyxPlayerData;
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
import org.bukkit.util.Vector;

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
            new PhaseCheck(plugin),
            new JesusCheck(plugin),
            new BoatFlyCheck(plugin),
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
            new WebCheck(plugin)
        ));

        combatChecks.add(new AimAssistCheck(plugin));

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

            if (Math.abs(steer.getForward()) > 0.98f || Math.abs(steer.getSideways()) > 0.98f) {
                EntitySpeedCheck speedCheck = plugin.getCheckManager().getCheck(EntitySpeedCheck.class);
                if (speedCheck != null && speedCheck.canRun(data)) {
                    speedCheck.flag(data, String.format("F:%.2f S:%.2f", steer.getForward(), steer.getSideways()));
                }
                event.setCancelled(true);
                return;
            }

            if (!player.isInsideVehicle()) {
                EntitySpeedCheck spoofCheck = plugin.getCheckManager().getCheck(EntitySpeedCheck.class);
                if (spoofCheck != null && spoofCheck.canRun(data)) {
                    spoofCheck.flag(data, "Vehicle spoof (no vehicle)");
                }
                event.setCancelled(true);
                return;
            }

        } else if (packetType == PacketType.Play.Client.STEER_BOAT) {
            WrapperPlayClientSteerBoat boatPacket = new WrapperPlayClientSteerBoat(event);
            BoatCheck boatCheck = plugin.getCheckManager().getCheck(BoatCheck.class);
            if (boatCheck == null || !boatCheck.canRun(data)) return;

            if (!player.isInsideVehicle()) {
                boatCheck.flag(data, "Spoofed boat (not in vehicle)");
                event.setCancelled(true);
                return;
            }

            Entity vehicle = player.getVehicle();
            if (vehicle != null && !(vehicle instanceof Boat)) {
                boatCheck.flag(data, "Spoofed boat (vehicle=" + vehicle.getType().name() + ")");
                event.setCancelled(true);
                return;
            }

        } else if (packetType == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);

            if (action.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
                data.setClientSprinting(true);
            } else if (action.getAction() == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                data.setClientSprinting(false);

            } else if (action.getAction() == WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA) {

                data.incrementElytraStartPacketCount();

                ElytraACheck elytraA = plugin.getCheckManager().getCheck(ElytraACheck.class);
                if (elytraA != null && elytraA.canRun(data) && player.isGliding()) {
                    elytraA.flag(data, "Already gliding");
                    event.setCancelled(true);
                    return;
                }

                data.setStartGlidingThisTick(true);

                ElytraBCheck elytraB = plugin.getCheckManager().getCheck(ElytraBCheck.class);
                if (elytraB != null && elytraB.canRun(data)) {
                    if (player.isInWaterOrBubbleColumn()) {
                        // Water disables elytra jumping; this is a legitimate surface glide
                        data.setGlideWithoutJump(false);
                    } else if (data.isOnGround() || data.isLastOnGround()) {
                        elytraB.flag(data, "On ground");
                    } else {
                        data.setGlideWithoutJump(true);
                    }
                }

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
            }

        } else if (packetType == PacketType.Play.Client.USE_ITEM) {
            data.setAlerted(false);
            data.recordRightClick();
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == org.bukkit.Material.FIREWORK_ROCKET) {
                data.setLastFireworkTime(System.currentTimeMillis());
            }

        } else if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            data.setAlerted(false);
            data.recordRightClick();

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
                    var vec = velocityPacket.getVelocity();
                    data.setVelocity(new org.bukkit.util.Vector(vec.x, vec.y, vec.z));
                    data.setLastVelocityTime(System.currentTimeMillis());
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

        double x, y, z;
        if (pktLoc != null) {
            x = pktLoc.getX();
            y = pktLoc.getY();
            z = pktLoc.getZ();
        } else {
            org.bukkit.Location loc = player.getLocation();
            x = loc.getX();
            y = loc.getY();
            z = loc.getZ();
        }

        float yaw = pktLoc != null ? pktLoc.getYaw() : player.getLocation().getYaw();
        float pitch = pktLoc != null ? pktLoc.getPitch() : player.getLocation().getPitch();

        boolean onGround = flyingPacket.isOnGround();
        boolean isPositionPacket = type == PacketType.Play.Client.PLAYER_POSITION
                                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;

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
            data.addMovementSnapshot(toLocation, onGround);
        } else {
            toLocation = player.getLocation();
        }
        data.addRotationSnapshot(yaw, pitch);

        data.setVelocity(new Vector(data.getDeltaX(), data.getDeltaY(), data.getDeltaZ()));

        long transactionId = data.getLastTransactionId() + 1;
        data.recordTransaction(transactionId, System.currentTimeMillis());

        data.setInWater(player.isInWater());
        data.setInLava(player.isInLava());
        {
            NyxPlayerData.IceType ice = detectIce(player.getWorld(), toLocation);
            data.setIceType(ice);
            data.setOnIce(ice != NyxPlayerData.IceType.NONE);
        }
        {
            boolean inWeb = false;
            int minX = (int) Math.floor(toLocation.getX() - 0.3);
            int maxX = (int) Math.floor(toLocation.getX() + 0.3);
            int minY = (int) Math.floor(toLocation.getY());
            int maxY = (int) Math.floor(toLocation.getY() + 1.8);
            int minZ = (int) Math.floor(toLocation.getZ() - 0.3);
            int maxZ = (int) Math.floor(toLocation.getZ() + 0.3);
            for (int bx = minX; bx <= maxX && !inWeb; bx++) {
                for (int by = minY; by <= maxY && !inWeb; by++) {
                    for (int bz = minZ; bz <= maxZ && !inWeb; bz++) {
                        if (player.getWorld().getBlockAt(bx, by, bz).getType() == org.bukkit.Material.COBWEB) {
                            inWeb = true;
                        }
                    }
                }
            }
            data.setInWeb(inWeb);
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

        // EntityControl check: riding a rideable without control item
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

        // ElytraB: started gliding without a jump
        if (data.isGlideWithoutJump()) {
            data.setGlideWithoutJump(false);
            if (data.getDeltaY() <= 0 && !player.isInWaterOrBubbleColumn()) {
                ElytraBCheck elytraB = plugin.getCheckManager().getCheck(ElytraBCheck.class);
                if (elytraB != null && elytraB.canRun(data)) {
                    elytraB.flag(data, "No jump");
                }
            }
        }

        // ElytraC: started gliding too frequently
        if (data.isStartGlidingThisTick() && data.isStartGlidingLastTick()) {
            ElytraCCheck elytraC = plugin.getCheckManager().getCheck(ElytraCCheck.class);
            if (elytraC != null && elytraC.canRun(data)) {
                elytraC.flag(data, "Too frequent");
            }
        }

        // Reset tryingToRiptide at end of each tick
        data.setTryingToRiptide(false);

        runChecks(data);
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
