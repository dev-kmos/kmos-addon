package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.util.AddonLog;
import kmos.addon.util.InteractionGate;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ShieldItem;
import net.minecraft.item.TridentItem;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AutoShulkerPack extends Module {
    private static final int SHULKER_SIZE = 27;
    private static final List<Item> DEFAULT_FILTER_ITEMS = List.of(
        Items.TOTEM_OF_UNDYING,
        Items.ENDER_CHEST,
        Items.ENCHANTED_GOLDEN_APPLE,
        Items.GOLDEN_APPLE,
        Items.FIREWORK_ROCKET
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    private final Setting<Integer> triggerEmptySlots = sgGeneral.add(new IntSetting.Builder()
        .name("trigger-empty-slots")
        .description("Starts packing when the main inventory has this many or fewer empty slots.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-baritone")
        .description("Stops Baritone before placing and filling a shulker, then restarts the remembered command after pickup.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> resumeBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("resume-baritone")
        .description("Resumes Baritone after the shulker has been packed and picked up.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stepDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("step-delay-ticks")
        .description("Uniform delay between shulker-packing steps.")
        .defaultValue(20)
        .min(0)
        .max(100)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Shows packing status messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("log-notify")
        .description("Writes packing status messages to kmos-addon.log.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes detailed Auto Shulker Pack debug logs to kmos-addon.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<FilterMode> filterMode = sgFilter.add(new EnumSetting.Builder<FilterMode>()
        .name("filter-mode")
        .description("Controls whether listed items are blocked or explicitly allowed for packing.")
        .defaultValue(FilterMode.Blacklist)
        .build()
    );

    private final Setting<List<Item>> filterItems = sgFilter.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items used by the current filter mode. Blacklist mode also protects tools, armor, food, totems, and shulkers by default.")
        .defaultValue(new ArrayList<>(DEFAULT_FILTER_ITEMS))
        .build()
    );

    private Stage stage = Stage.Idle;
    private int stageTicks = 0;
    private int failureCooldown = 0;
    private int successCooldown = 0;
    private PackingTask task;
    private String lastBaritoneWorkCommand;

    public AutoShulkerPack() {
        super(KmosAddon.CATEGORY, "auto-shulker-pack", "Stops Baritone, fills a shulker with filtered inventory items, picks it back up, and restarts the remembered command.");
    }

    @Override
    public void onActivate() {
        resetTask();
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            mc.player.closeHandledScreen();
        }
        resetTask();
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (event.message == null) return;
        String message = event.message.trim();

        String command;
        if (message.startsWith("#")) {
            command = message.substring(1).trim();
        } else if (looksLikeBaritoneWorkCommand(message)) {
            command = message;
        } else {
            return;
        }

        if (command.isBlank() || isBaritoneControlCommand(command)) return;

        lastBaritoneWorkCommand = command;
        debug("Remembered Baritone work command: " + command);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);

        if (failureCooldown > 0) failureCooldown--;
        if (successCooldown > 0) successCooldown--;

        if (stage != Stage.Idle) {
            if (!InteractionGate.acquire(InteractionGate.Owner.ShulkerFlow, now)) return;
            runStage();
            return;
        }

        if (failureCooldown > 0 || successCooldown > 0) return;
        if (mc.currentScreen != null) return;
        if (!InteractionGate.acquire(InteractionGate.Owner.ShulkerFlow, now)) return;

        if (!shouldStartPacking()) {
            InteractionGate.release(InteractionGate.Owner.ShulkerFlow);
            return;
        }

        if (!startPacking()) {
            InteractionGate.release(InteractionGate.Owner.ShulkerFlow);
        }
    }

    private void runStage() {
        switch (stage) {
            case PrePauseDelay -> prePauseDelay();
            case PostPauseDelay -> postPauseDelay();
            case MoveShulkerToHotbar -> moveShulkerToHotbar();
            case PostHotbarDelay -> postHotbarDelay();
            case PlaceShulker -> placeShulker();
            case PostPlacementDelay -> postPlacementDelay();
            case WaitForPlacement -> waitForPlacement();
            case OpenShulker -> openShulker();
            case WaitForScreen -> waitForScreen();
            case TransferItems -> transferItems();
            case PostTransferDelay -> postTransferDelay();
            case CloseShulker -> closeShulker();
            case WaitBeforeBreak -> waitBeforeBreak();
            case BreakShulker -> breakShulker();
            case PostBreakDelay -> postBreakDelay();
            case WaitForPickup -> waitForPickup();
            case PostPickupDelay -> postPickupDelay();
            case ResumeBaritone -> resumeBaritone();
            case Idle -> InteractionGate.release(InteractionGate.Owner.ShulkerFlow);
        }
    }

    private boolean shouldStartPacking() {
        int empty = countEmptyMainInventorySlots();
        if (empty > triggerEmptySlots.get()) return false;
        return !collectPackableInventory().isEmpty();
    }

    private boolean startPacking() {
        Map<Item, Integer> packable = collectPackableInventory();
        if (packable.isEmpty()) return false;

        int shulkerSlot = findBestShulker(packable);
        if (shulkerSlot < 0) {
            notify("Inventory is full, but no usable shulker was found.");
            debug("No shulker candidate for packable items: " + packable);
            failureCooldown = 40;
            return false;
        }

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        int targetHotbarSlot = findTargetHotbarSlot(selectedSlot);
        if (!isHotbarSlot(targetHotbarSlot) && !isHotbarSlot(shulkerSlot)) {
            notify("Inventory is full, but there is no safe hotbar slot for packing.");
            debug("Packing aborted because no empty or packable hotbar slot was available.");
            failureCooldown = 20;
            return false;
        }

        int shulkerCountBefore = countInventoryShulkers();
        task = new PackingTask(shulkerSlot, selectedSlot, targetHotbarSlot, shulkerCountBefore, false, lastBaritoneWorkCommand);
        stage = Stage.PrePauseDelay;
        stageTicks = stepDelay();
        debug("Packing started. shulkerSlot=" + shulkerSlot + ", selected=" + selectedSlot + ", targetHotbar=" + targetHotbarSlot + ", packable=" + packable);
        notify("Inventory is full. Packing items into a shulker.");
        return true;
    }

    private void prePauseDelay() {
        if (--stageTicks > 0) return;

        if (task != null && pauseBaritone.get()) {
            // Stop unconditionally. Baritone can report "not pathing" between builder ticks while still owning a task.
            if (executeBaritoneCommand("stop")) {
                task.pausedBaritone = true;
                notify("Stopped Baritone to pack items into a shulker.");
            } else {
                debug("Baritone stop command returned false before packing.");
            }
        }

        stage = Stage.PostPauseDelay;
        stageTicks = stepDelay();
        debug("Pause phase done. Waiting before shulker hotbar setup.");
    }

    private void postPauseDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.MoveShulkerToHotbar;
        stageTicks = 0;
    }

    private void moveShulkerToHotbar() {
        if (task == null) {
            failPacking("Packing task disappeared before shulker swap.");
            return;
        }

        if (stageTicks > 0) {
            stageTicks--;
            return;
        }

        int hotbarSlot = task.shulkerSlot;
        if (!isHotbarSlot(hotbarSlot)) {
            InvUtils.quickSwap().from(task.shulkerSlot).toHotbar(task.targetHotbarSlot);
            hotbarSlot = task.targetHotbarSlot;
            if (!isShulkerItem(mc.player.getInventory().getStack(hotbarSlot))) {
                hotbarSlot = findHotbarShulker();
            }
        }

        if (!isHotbarSlot(hotbarSlot) || !isShulkerItem(mc.player.getInventory().getStack(hotbarSlot))) {
            failPacking("Shulker could not be moved into the hotbar.");
            return;
        }

        task.shulkerSlot = hotbarSlot;
        mc.player.getInventory().setSelectedSlot(hotbarSlot);
        stage = Stage.PostHotbarDelay;
        stageTicks = stepDelay();
        debug("Shulker moved to hotbar slot " + hotbarSlot + ".");
    }

    private void postHotbarDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.PlaceShulker;
        stageTicks = 0;
    }

    private void placeShulker() {
        if (task == null) {
            failPacking("Packing task disappeared before placement.");
            return;
        }

        if (stageTicks > 0) {
            stageTicks--;
            return;
        }

        BlockPos pos = findPlacementPos(mc.world);
        if (pos == null) {
            failPacking("No safe placement position for the shulker was found.");
            return;
        }

        ItemStack selected = mc.player.getInventory().getSelectedStack();
        if (!isShulkerItem(selected)) {
            int replacement = findHotbarShulker();
            if (replacement >= 0) {
                task.shulkerSlot = replacement;
                mc.player.getInventory().setSelectedSlot(replacement);
                selected = mc.player.getInventory().getSelectedStack();
                debug("Recovered shulker selection from hotbar slot " + replacement + ".");
            }
        }
        if (!isShulkerItem(selected)) {
            failPacking("Selected slot no longer contains a shulker.");
            return;
        }

        BlockPos support = pos.down();
        aimAt(pos);
        BlockHitResult hit = new BlockHitResult(
            new Vec3d(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5),
            Direction.UP,
            support,
            false
        );

        mc.player.setSneaking(true);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.setSneaking(false);
        mc.player.swingHand(Hand.MAIN_HAND);

        task.placedPos = pos;
        stage = Stage.PostPlacementDelay;
        stageTicks = stepDelay();
        debug("Placed shulker attempt at " + pos + ".");
    }

    private void postPlacementDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.WaitForPlacement;
        stageTicks = 40;
    }

    private void waitForPlacement() {
        if (task == null || task.placedPos == null) {
            failPacking("Placed shulker position was lost.");
            return;
        }

        if (mc.world.getBlockState(task.placedPos).getBlock() instanceof ShulkerBoxBlock) {
            stage = Stage.OpenShulker;
            stageTicks = 0;
            return;
        }

        if (--stageTicks <= 0) {
            failPacking("Shulker was not placed.");
        }
    }

    private void openShulker() {
        if (task == null || task.placedPos == null) {
            failPacking("Shulker position was lost before opening.");
            return;
        }

        aimAt(task.placedPos);
        BlockHitResult hit = new BlockHitResult(
            new Vec3d(task.placedPos.getX() + 0.5, task.placedPos.getY() + 0.5, task.placedPos.getZ() + 0.5),
            Direction.UP,
            task.placedPos,
            false
        );
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        stage = Stage.WaitForScreen;
        stageTicks = 14;
        debug("Opening placed shulker.");
    }

    private void waitForScreen() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            stage = Stage.TransferItems;
            stageTicks = 0;
            return;
        }

        if (--stageTicks <= 0) {
            failPacking("Shulker screen did not open.");
        }
    }

    private void transferItems() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) {
            failPacking("Shulker screen closed before transfer.");
            return;
        }

        int moved = 0;
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            if (slot.inventory != mc.player.getInventory()) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !shouldPack(stack)) continue;

            String movedStack = debugLogging.get() ? describeStack(stack) : null;
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            moved++;
            if (movedStack != null) debug("Moved " + movedStack + " into shulker.");
        }

        task.movedStacks += moved;
        if (moved == 0) {
            if (task.transferRetryTicks-- > 0) {
                debug("No matching items moved yet; waiting for inventory sync before retry.");
                return;
            }
            failPacking("No matching inventory items could be moved into the shulker.");
            return;
        }

        stage = Stage.PostTransferDelay;
        stageTicks = stepDelay();
        notify("Packed " + moved + " stack(s) into the shulker.");
    }

    private void postTransferDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.CloseShulker;
        stageTicks = 0;
    }

    private void closeShulker() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            mc.player.closeHandledScreen();
        }

        stage = Stage.WaitBeforeBreak;
        stageTicks = stepDelay();
        task.breakTimeoutTicks = 180;
        task.breakingStarted = false;
    }

    private void waitBeforeBreak() {
        if (mc.currentScreen != null) {
            if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) mc.player.closeHandledScreen();
            if (--stageTicks <= 0) stageTicks = 2;
            return;
        }

        if (stageTicks-- > 0) return;
        stage = Stage.BreakShulker;
        debug("Starting packed shulker break after close delay.");
    }

    private void breakShulker() {
        if (task == null || task.placedPos == null) {
            stage = Stage.ResumeBaritone;
            return;
        }

        if (!(mc.world.getBlockState(task.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
            restoreSelectedSlot();
            task.breakingStarted = false;
            stage = Stage.PostBreakDelay;
            stageTicks = stepDelay();
            task.pickupPathingStarted = false;
            return;
        }

        selectBestMiningTool(task.placedPos);
        aimAt(task.placedPos);

        if (!task.breakingStarted) {
            mc.interactionManager.attackBlock(task.placedPos, Direction.UP);
            task.breakingStarted = true;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(task.placedPos, Direction.UP);
        }

        mc.player.swingHand(Hand.MAIN_HAND);

        if (--task.breakTimeoutTicks <= 0) {
            failPacking("Shulker could not be broken after packing.");
        }
    }

    private void postBreakDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.WaitForPickup;
        stageTicks = 160;
    }

    private void waitForPickup() {
        if (task == null) {
            stage = Stage.ResumeBaritone;
            return;
        }

        if (countInventoryShulkers() >= task.shulkerCountBefore) {
            stopPickupMovement();
            stage = Stage.PostPickupDelay;
            stageTicks = stepDelay();
            return;
        }

        ItemEntity dropped = findDroppedShulker();
        if (dropped != null) {
            double distanceSq = mc.player.squaredDistanceTo(dropped);
            if (distanceSq > 2.25 && (!task.pickupPathingStarted || stageTicks % 20 == 0)) {
                BlockPos target = dropped.getBlockPos();
                executeBaritoneCommand("goto " + target.getX() + " " + target.getY() + " " + target.getZ());
                task.pickupPathingStarted = true;
                debug("Using Baritone to pick up dropped shulker at " + target + ".");
            }
        } else if (task.placedPos != null) {
            if (!task.pickupPathingStarted || stageTicks % 20 == 0) {
                executeBaritoneCommand("goto " + task.placedPos.getX() + " " + task.placedPos.getY() + " " + task.placedPos.getZ());
                task.pickupPathingStarted = true;
                debug("Dropped shulker entity not found yet; using Baritone to return to " + task.placedPos + ".");
            }
        }

        if (--stageTicks <= 0) {
            stopPickupMovement();
            debug("Timed out waiting for the shulker item to be picked up.");
            stage = Stage.PostPickupDelay;
            stageTicks = stepDelay();
        }
    }

    private void postPickupDelay() {
        if (--stageTicks > 0) return;
        stage = Stage.ResumeBaritone;
    }

    private void resumeBaritone() {
        stopPickupMovement();
        restoreSelectedSlot();

        if (task != null && task.pausedBaritone && resumeBaritone.get()) {
            if (task.resumeCommand != null && !task.resumeCommand.isBlank()) {
                executeBaritoneCommand("stop");
                if (executeBaritoneCommand(task.resumeCommand)) {
                    notify("Restarted Baritone command after packing: #" + task.resumeCommand);
                } else {
                    notify("Packed the shulker, but could not restart Baritone command: #" + task.resumeCommand);
                }
            } else {
                notify("Packed the shulker, but no previous Baritone command was remembered.");
            }
        }

        resetTask();
    }

    private void failPacking(String reason) {
        notify("Auto Shulker Pack failed: " + reason);
        debug("Failure reason: " + reason);

        if (mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            mc.player.closeHandledScreen();
        }

        stopPickupMovement();
        if (task != null && task.pausedBaritone && resumeBaritone.get() && task.resumeCommand != null && !task.resumeCommand.isBlank()) {
            executeBaritoneCommand(task.resumeCommand);
        }
        restoreSelectedSlot();
        resetTask();
        failureCooldown = 40;
    }

    private void resetTask() {
        task = null;
        stage = Stage.Idle;
        stageTicks = 0;
        InteractionGate.release(InteractionGate.Owner.ShulkerFlow);
    }

    private Map<Item, Integer> collectPackableInventory() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !shouldPack(stack)) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private boolean shouldPack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (isProtectedByDefault(stack)) return false;

        List<Item> configured = filterItems.get();
        boolean listed = configured.contains(stack.getItem());
        if (filterMode.get() == FilterMode.Blacklist) return !listed;
        return listed;
    }

    private boolean isProtectedByDefault(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.TOTEM_OF_UNDYING) return true;
        if (isShulkerItem(stack)) return true;
        if (stack.isIn(ItemTags.HEAD_ARMOR) || stack.isIn(ItemTags.CHEST_ARMOR) || stack.isIn(ItemTags.LEG_ARMOR) || stack.isIn(ItemTags.FOOT_ARMOR)) return true;
        if (stack.isIn(ItemTags.PICKAXES) || stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.SHOVELS) || stack.isIn(ItemTags.HOES) || stack.isIn(ItemTags.SWORDS)) return true;
        if (item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem) return true;
        if (item instanceof ShieldItem || item instanceof FishingRodItem || item instanceof FlintAndSteelItem || item instanceof ShearsItem) return true;
        return stack.contains(DataComponentTypes.FOOD);
    }

    private int findBestShulker(Map<Item, Integer> packable) {
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !isShulkerItem(stack)) continue;

            int score = scoreShulker(stack, packable);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = score > 0 ? slot : bestSlot;
            }
        }

        return bestSlot;
    }

    private int scoreShulker(ItemStack shulker, Map<Item, Integer> packable) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return SHULKER_SIZE * 64;

        int nonEmpty = 0;
        int room = 0;
        Set<Item> seen = new HashSet<>();

        for (ItemStack contained : container.iterateNonEmpty()) {
            nonEmpty++;
            if (contained.isEmpty()) continue;

            Item item = contained.getItem();
            if (!packable.containsKey(item)) continue;
            room += contained.getMaxCount() - contained.getCount();
            seen.add(item);
        }

        if (nonEmpty < SHULKER_SIZE) {
            for (Map.Entry<Item, Integer> entry : packable.entrySet()) {
                if (seen.contains(entry.getKey())) continue;
                room += Math.min(entry.getValue(), (SHULKER_SIZE - nonEmpty) * entry.getKey().getMaxCount());
                break;
            }
        }

        return room;
    }

    private int findHotbarShulker() {
        for (int slot = 0; slot < 9; slot++) {
            if (isShulkerItem(mc.player.getInventory().getStack(slot))) return slot;
        }
        return -1;
    }

    private int findTargetHotbarSlot(int selectedSlot) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot == selectedSlot) continue;
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && shouldPack(stack)) return slot;
        }
        return selectedSlot;
    }

    private ItemEntity findDroppedShulker() {
        if (task == null || task.placedPos == null) return null;
        Vec3d center = Vec3d.ofCenter(task.placedPos);
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (ItemEntity entity : mc.world.getEntitiesByClass(ItemEntity.class, mc.player.getBoundingBox().expand(12), entity -> isShulkerItem(entity.getStack()))) {
            double distance = new Vec3d(entity.getX(), entity.getY(), entity.getZ()).squaredDistanceTo(center);
            if (distance > 64 || distance >= bestDistance) continue;
            best = entity;
            bestDistance = distance;
        }

        return best;
    }

    private int countEmptyMainInventorySlots() {
        int empty = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) empty++;
        }
        return empty;
    }

    private int countInventoryShulkers() {
        int count = 0;
        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (isShulkerItem(stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean isShulkerItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    private BlockPos findPlacementPos(World world) {
        BlockPos base = mc.player.getBlockPos();
        Direction primaryX = mc.player.getX() - base.getX() >= 0.5 ? Direction.EAST : Direction.WEST;
        Direction primaryZ = mc.player.getZ() - base.getZ() >= 0.5 ? Direction.SOUTH : Direction.NORTH;

        BlockPos[] candidates = new BlockPos[] {
            base.offset(primaryX, 2),
            base.offset(primaryZ, 2),
            base.offset(primaryX, 2).offset(primaryZ),
            base.offset(primaryZ, 2).offset(primaryX),
            base.offset(primaryX.getOpposite(), 2),
            base.offset(primaryZ.getOpposite(), 2),
            base.offset(primaryX.getOpposite(), 2).offset(primaryZ.getOpposite()),
            base.offset(primaryZ.getOpposite(), 2).offset(primaryX.getOpposite()),
            base.north().east(),
            base.north().west(),
            base.south().east(),
            base.south().west(),
            base.north(),
            base.south(),
            base.east(),
            base.west()
        };

        for (BlockPos pos : candidates) {
            if (isSafePlacementPos(world, pos)) return pos;
        }

        for (int dy = -1; dy <= 1; dy++) {
            for (int radius = 1; radius <= 4; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                        BlockPos pos = base.add(dx, dy, dz);
                        if (isSafePlacementPos(world, pos)) {
                            debug("Fallback placement candidate selected at " + pos + ".");
                            return pos;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isSafePlacementPos(World world, BlockPos pos) {
        BlockState placeState = world.getBlockState(pos);
        BlockState belowState = world.getBlockState(pos.down());
        if (!placeState.isReplaceable()) return false;
        if (belowState.isAir() || belowState.isReplaceable()) return false;
        if (mc.player.getBlockPos().equals(pos)) return false;
        return true;
    }

    private void walkToward(double x, double y, double z) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = new Vec3d(x, y + 0.25, z);
        Vec3d diff = to.subtract(from);
        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
    }

    private void stopPickupMovement() {
        if (mc == null || mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private int stepDelay() {
        return Math.max(0, stepDelayTicks.get());
    }

    private void selectBestMiningTool(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        int bestSlot = mc.player.getInventory().getSelectedSlot();
        float bestSpeed = mc.player.getInventory().getSelectedStack().getMiningSpeedMultiplier(state);

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }

        mc.player.getInventory().setSelectedSlot(bestSlot);
    }

    private void restoreSelectedSlot() {
        if (mc.player == null || task == null) return;
        mc.player.getInventory().setSelectedSlot(task.originalHotbarSlot);
    }

    private void aimAt(BlockPos pos) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d diff = to.subtract(from);
        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
    }

    private boolean isBaritonePaused() {
        if (invokeBuilderBoolean("isPaused")) return true;
        return invokePathingBehaviorBoolean("isPathing") == Boolean.FALSE;
    }

    private boolean invokeBuilderMethod(String methodName) {
        try {
            Object builderProcess = getBuilderProcess();
            if (builderProcess == null) return false;

            Method method = builderProcess.getClass().getMethod(methodName);
            method.invoke(builderProcess);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeBuilderBoolean(String methodName) {
        try {
            Object builderProcess = getBuilderProcess();
            if (builderProcess == null) return false;

            Method method = builderProcess.getClass().getMethod(methodName);
            Object result = method.invoke(builderProcess);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Boolean invokePathingBehaviorBoolean(String methodName) {
        try {
            Object pathingBehavior = getPathingBehavior();
            if (pathingBehavior == null) return null;

            Method method = pathingBehavior.getClass().getMethod(methodName);
            Object result = method.invoke(pathingBehavior);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean executeBaritoneCommand(String command) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);
            if (provider == null) return false;

            Method getPrimaryBaritone = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimaryBaritone.invoke(provider);
            if (baritone == null) return false;

            Method getCommandManager = baritone.getClass().getMethod("getCommandManager");
            Object manager = getCommandManager.invoke(baritone);
            if (manager == null) return false;

            Method execute = manager.getClass().getMethod("execute", String.class);
            Object result = execute.invoke(manager, command);
            debug("Executed Baritone command '" + command + "' -> " + result);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            debug("Failed to execute Baritone command '" + command + "': " + t.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isBaritoneControlCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("pause")
            || normalized.equals("resume")
            || normalized.equals("stop")
            || normalized.equals("cancel")
            || normalized.startsWith("set ");
    }

    private boolean looksLikeBaritoneWorkCommand(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.startsWith(".")) return false;

        String first = normalized.split("\\s+", 2)[0];
        return first.equals("mine")
            || first.equals("get")
            || first.equals("goto")
            || first.equals("goal")
            || first.equals("follow")
            || first.equals("build")
            || first.equals("schematica")
            || first.equals("litematica")
            || first.equals("cleararea")
            || first.equals("tunnel")
            || first.equals("farm")
            || first.equals("explore")
            || first.equals("surface")
            || first.equals("ascend")
            || first.equals("descend")
            || first.equals("thisway")
            || first.equals("axis");
    }

    private Object getBuilderProcess() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Method getProvider = apiClass.getMethod("getProvider");
        Object provider = getProvider.invoke(null);
        if (provider == null) return null;

        Method getPrimaryBaritone = provider.getClass().getMethod("getPrimaryBaritone");
        Object baritone = getPrimaryBaritone.invoke(provider);
        if (baritone == null) return null;

        Method getBuilderProcess = baritone.getClass().getMethod("getBuilderProcess");
        return getBuilderProcess.invoke(baritone);
    }

    private Object getPathingBehavior() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Method getProvider = apiClass.getMethod("getProvider");
        Object provider = getProvider.invoke(null);
        if (provider == null) return null;

        Method getPrimaryBaritone = provider.getClass().getMethod("getPrimaryBaritone");
        Object baritone = getPrimaryBaritone.invoke(provider);
        if (baritone == null) return null;

        Method getPathingBehavior = baritone.getClass().getMethod("getPathingBehavior");
        return getPathingBehavior.invoke(baritone);
    }

    private void notify(String message) {
        if (chatNotify.get()) info(message);
        if (logNotify.get()) AddonLog.info(message);
    }

    private void debug(String message) {
        if (debugLogging.get()) AddonLog.info("[AutoShulkerPackDebug] " + message);
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getItem().toString().toLowerCase(Locale.ROOT) + " x" + stack.getCount();
    }

    private enum FilterMode {
        Blacklist,
        Whitelist
    }

    private enum Stage {
        Idle,
        PrePauseDelay,
        PostPauseDelay,
        MoveShulkerToHotbar,
        PostHotbarDelay,
        PlaceShulker,
        PostPlacementDelay,
        WaitForPlacement,
        OpenShulker,
        WaitForScreen,
        TransferItems,
        PostTransferDelay,
        CloseShulker,
        WaitBeforeBreak,
        BreakShulker,
        PostBreakDelay,
        WaitForPickup,
        PostPickupDelay,
        ResumeBaritone
    }

    private static class PackingTask {
        private int shulkerSlot;
        private final int originalHotbarSlot;
        private final int targetHotbarSlot;
        private final int shulkerCountBefore;
        private boolean pausedBaritone;
        private final String resumeCommand;
        private BlockPos placedPos;
        private boolean breakingStarted;
        private boolean pickupPathingStarted;
        private int breakTimeoutTicks;
        private int transferRetryTicks = 5;
        private int movedStacks;

        private PackingTask(int shulkerSlot, int originalHotbarSlot, int targetHotbarSlot, int shulkerCountBefore, boolean pausedBaritone, String resumeCommand) {
            this.shulkerSlot = shulkerSlot;
            this.originalHotbarSlot = originalHotbarSlot;
            this.targetHotbarSlot = targetHotbarSlot;
            this.shulkerCountBefore = shulkerCountBefore;
            this.pausedBaritone = pausedBaritone;
            this.resumeCommand = resumeCommand;
        }
    }
}
