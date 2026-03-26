package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.util.AddonLog;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BaritoneMaterialDebug extends Module {
    private static final String BARITONE_PREFIX = "[Baritone]";
    private static final String MISSING_HEADER = "Missing materials for at least:";
    private static final String PAUSE_TEXT = "Unable to do it. Pausing";
    private static final Pattern MATERIAL_LINE = Pattern.compile("^\\[Baritone\\]\\s+(\\d+)x\\s+(.+)$");
    private static final Pattern ID_PATTERN = Pattern.compile("(minecraft:[a-z0-9_./-]+)");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Prints KMOS messages in chat when Baritone pauses due to missing materials.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("log-notify")
        .description("Writes captured material and refill information to kmos-addon.log.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRefill = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill-from-shulker")
        .description("After a missing-material pause, tries to place a shulker, take items, and resume Baritone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stacksToTake = sgGeneral.add(new IntSetting.Builder()
        .name("stacks-to-take")
        .description("Number of stacks of the missing material to take from the shulker.")
        .defaultValue(1)
        .min(1)
        .max(9)
        .build()
    );

    private final Setting<Integer> captureTimeoutTicks = sgGeneral.add(new IntSetting.Builder()
        .name("capture-timeout-ticks")
        .description("How long captured missing-material lines are kept before being discarded.")
        .defaultValue(100)
        .min(20)
        .max(400)
        .build()
    );

    private final List<MissingMaterial> missingMaterials = new ArrayList<>();
    private boolean collecting = false;
    private int captureTicksLeft = 0;
    private int dedupeTicks = 0;

    private RefillTask refillTask;
    private Stage stage = Stage.Idle;
    private int stageTicks = 0;
    private BlockPos placedPos;
    private boolean breakingStarted = false;

    public BaritoneMaterialDebug() {
        super(KmosAddon.CATEGORY, "baritone-material-debug-beta", "Beta: detects Baritone missing-material pauses and can try refilling from a shulker.");
    }

    @Override
    public void onActivate() {
        resetCapture();
        resetRefill();
    }

    @Override
    public void onDeactivate() {
        resetCapture();
        resetRefill();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (dedupeTicks > 0) dedupeTicks--;

        if (collecting && --captureTicksLeft <= 0) resetCapture();

        if (refillTask == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        switch (stage) {
            case MoveShulkerToHotbar -> moveShulkerToHotbar();
            case PlaceShulker -> placeShulker();
            case WaitForPlacement -> waitForPlacement();
            case OpenShulker -> openShulker();
            case WaitForScreen -> waitForScreen();
            case TransferItems -> transferItems();
            case BreakShulker -> breakShulker();
            case WaitForPickup -> waitForPickup();
            case ResumeBaritone -> resumeBaritone();
            default -> {
            }
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        if (text == null || !text.startsWith(BARITONE_PREFIX)) return;

        if (text.contains(MISSING_HEADER)) {
            missingMaterials.clear();
            collecting = true;
            captureTicksLeft = captureTimeoutTicks.get();
            return;
        }

        if (!collecting) return;
        captureTicksLeft = captureTimeoutTicks.get();

        Matcher materialMatcher = MATERIAL_LINE.matcher(text);
        if (materialMatcher.matches()) {
            String amount = materialMatcher.group(1);
            String rawMaterial = materialMatcher.group(2).trim();
            MissingMaterial parsed = parseMissingMaterial(amount, rawMaterial);
            if (parsed != null) missingMaterials.add(parsed);
            return;
        }

        if (text.contains(PAUSE_TEXT)) {
            if (dedupeTicks > 0) {
                resetCapture();
                return;
            }

            boolean builderPaused = isBuilderPaused();
            String summary = missingMaterials.isEmpty()
                ? "Baritone paused after missing-material warning, but no material lines were captured."
                : "Baritone paused due to missing materials: " + formatMissingMaterials();

            if (builderPaused) summary += " [builder-paused]";
            else summary += " [builder-state-unconfirmed]";

            notify(summary);

            if (autoRefill.get() && !missingMaterials.isEmpty()) {
                startRefill(missingMaterials.getFirst());
            }

            dedupeTicks = 20;
            resetCapture();
            return;
        }

        resetCapture();
    }

    private void moveShulkerToHotbar() {
        if (refillTask.shulkerSlot < 0) {
            failRefill("No shulker with " + refillTask.itemName + " found in inventory.");
            return;
        }

        debug("MoveShulkerToHotbar start: source=" + refillTask.shulkerSlot + ", selected=" + mc.player.getInventory().getSelectedSlot()
            + ", target-hotbar=" + refillTask.targetHotbarSlot + ", source-stack=" + describeStack(mc.player.getInventory().getStack(refillTask.shulkerSlot)));

        int hotbarSlot = refillTask.shulkerSlot;
        if (!isHotbarSlot(hotbarSlot)) {
            InvUtils.quickSwap().from(refillTask.shulkerSlot).toHotbar(refillTask.targetHotbarSlot);
            debug("After quickSwap: target-stack=" + describeStack(mc.player.getInventory().getStack(refillTask.targetHotbarSlot))
                + ", source-stack=" + describeStack(mc.player.getInventory().getStack(refillTask.shulkerSlot)));
            hotbarSlot = refillTask.targetHotbarSlot;
            if (!isRefillShulker(mc.player.getInventory().getStack(hotbarSlot))) {
                debug("Target slot is not a valid refill shulker after swap, scanning hotbar.");
                hotbarSlot = findBestHotbarShulkerWithItem(refillTask.item);
            }
        }

        if (!isHotbarSlot(hotbarSlot)) {
            failRefill("Shulker could not be moved into the hotbar.");
            return;
        }

        refillTask.shulkerSlot = hotbarSlot;
        mc.player.getInventory().setSelectedSlot(hotbarSlot);
        debug("Selected hotbar slot " + hotbarSlot + ": " + describeStack(mc.player.getInventory().getSelectedStack()));
        stage = Stage.PlaceShulker;
        stageTicks = 2;
    }

    private void placeShulker() {
        if (stageTicks > 0) {
            stageTicks--;
            return;
        }

        BlockPos pos = findPlacementPos();
        if (pos == null) {
            failRefill("No valid spot near the player to place the shulker.");
            return;
        }

        BlockPos support = pos.down();
        aimAt(pos);
        BlockHitResult hit = new BlockHitResult(
            new Vec3d(support.getX() + 0.5, support.getY() + 1, support.getZ() + 0.5),
            Direction.UP,
            support,
            false
        );

        ItemStack selected = mc.player.getInventory().getSelectedStack();
        if (!isShulkerItem(selected)) {
            int retrySlot = findBestHotbarShulkerWithItem(refillTask.item);
            debug("Selected stack is not a shulker. retry-slot=" + retrySlot + ", selected-stack=" + describeStack(selected));
            if (isHotbarSlot(retrySlot)) {
                mc.player.getInventory().setSelectedSlot(retrySlot);
                selected = mc.player.getInventory().getSelectedStack();
                debug("Retried with hotbar slot " + retrySlot + ": " + describeStack(selected));
            }
        }

        if (!isRefillShulker(selected)) {
            debug("Selected stack failed refill validation: " + describeStack(selected));
            failRefill("Selected slot no longer contains the refill shulker.");
            return;
        }

        mc.player.setSneaking(true);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.setSneaking(false);
        mc.player.swingHand(Hand.MAIN_HAND);
        placedPos = pos;
        stage = Stage.WaitForPlacement;
        stageTicks = 16;
    }

    private void waitForPlacement() {
        if (placedPos != null && mc.world.getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock) {
            stage = Stage.OpenShulker;
            return;
        }

        if (--stageTicks <= 0) failRefill("Shulker was not placed.");
    }

    private void openShulker() {
        if (placedPos == null) {
            failRefill("Placed shulker position is missing.");
            return;
        }

        aimAt(placedPos);
        BlockHitResult hit = new BlockHitResult(
            new Vec3d(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5),
            Direction.UP,
            placedPos,
            false
        );
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        stage = Stage.WaitForScreen;
        stageTicks = 14;
    }

    private void waitForScreen() {
        if (mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            stage = Stage.TransferItems;
            return;
        }

        if (--stageTicks <= 0) failRefill("Shulker screen did not open.");
    }

    private void transferItems() {
        if (!(mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler handler)) {
            failRefill("Shulker screen closed before transfer.");
            return;
        }

        int targetCount = refillTask.item.getMaxCount() * stacksToTake.get();
        int before = countPlayerItem(refillTask.item);
        int movedSomething = 0;

        for (int i = 0; i < handler.slots.size(); i++) {
            if (countPlayerItem(refillTask.item) - before >= targetCount) break;

            Slot slot = handler.slots.get(i);
            if (slot.inventory == mc.player.getInventory()) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || stack.getItem() != refillTask.item) continue;

            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            movedSomething++;
        }

        int taken = countPlayerItem(refillTask.item) - before;
        mc.player.closeHandledScreen();

        if (taken <= 0 || movedSomething == 0) {
            failRefill("Opened shulker, but no " + refillTask.itemName + " was moved.");
            return;
        }

        notify("Refill took " + taken + "x " + refillTask.itemName + " from shulker.");
        stage = Stage.BreakShulker;
        stageTicks = 160;
    }

    private void resumeBaritone() {
        if (invokeBuilderMethod("resume")) {
            notify("Sent Baritone resume after refill.");
        } else {
            failRefill("Refill completed, but resume() could not be called on Baritone.");
            return;
        }

        resetRefill();
    }

    private void breakShulker() {
        if (placedPos == null) {
            stage = Stage.ResumeBaritone;
            return;
        }

        if (!(mc.world.getBlockState(placedPos).getBlock() instanceof ShulkerBoxBlock)) {
            restoreSelectedSlot();
            breakingStarted = false;
            stage = Stage.WaitForPickup;
            stageTicks = 10;
            return;
        }

        selectBestMiningTool(placedPos);
        aimAt(placedPos);

        if (!breakingStarted) {
            mc.interactionManager.attackBlock(placedPos, Direction.UP);
            breakingStarted = true;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(placedPos, Direction.UP);
        }

        mc.player.swingHand(Hand.MAIN_HAND);

        if (--stageTicks <= 0) {
            failRefill("Shulker could not be broken after refill.");
        }
    }

    private void waitForPickup() {
        if (--stageTicks <= 0) {
            stage = Stage.ResumeBaritone;
        }
    }

    private void startRefill(MissingMaterial material) {
        if (refillTask != null) return;
        if (material.item == null) {
            notify("Captured missing material but could not map it to an item: " + material.rawMaterial);
            return;
        }

        int shulkerSlot = findBestShulkerWithItem(material.item);
        if (shulkerSlot < 0) {
            notify("Missing " + material.itemName + ", but no matching shulker was found in inventory.");
            return;
        }

        int hotbarSlot = mc.player.getInventory().getSelectedSlot();
        refillTask = new RefillTask(material.item, material.itemName, shulkerSlot, hotbarSlot, findRefillHotbarSlot(hotbarSlot));
        debug("StartRefill: item=" + material.itemName + ", count=" + material.count + ", chosen-slot=" + shulkerSlot
            + ", chosen-stack=" + describeStack(mc.player.getInventory().getStack(shulkerSlot)));
        stage = Stage.MoveShulkerToHotbar;
        notify("Attempting shulker refill for " + material.itemName + ".");
    }

    private void failRefill(String message) {
        notify("Refill failed: " + message);
        if (mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            mc.player.closeHandledScreen();
        }
        restoreSelectedSlot();
        resetRefill();
    }

    private void resetRefill() {
        restoreSelectedSlot();
        refillTask = null;
        stage = Stage.Idle;
        stageTicks = 0;
        placedPos = null;
        breakingStarted = false;
    }

    private void resetCapture() {
        collecting = false;
        captureTicksLeft = 0;
        missingMaterials.clear();
    }

    private MissingMaterial parseMissingMaterial(String amount, String rawMaterial) {
        try {
            int count = Integer.parseInt(amount);
            Item item = itemFromBaritoneMaterial(rawMaterial);
            String itemName = item != null ? item.getName().getString() : rawMaterial;
            return new MissingMaterial(count, rawMaterial, item, itemName);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Item itemFromBaritoneMaterial(String rawMaterial) {
        Matcher matcher = ID_PATTERN.matcher(rawMaterial.toLowerCase(Locale.ROOT));
        if (!matcher.find()) return null;

        Identifier id = Identifier.tryParse(matcher.group(1));
        if (id == null || !Registries.ITEM.containsId(id)) return null;
        return Registries.ITEM.get(id);
    }

    private int findBestShulkerWithItem(Item item) {
        int bestSlot = -1;
        int bestCount = 0;

        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !isShulkerItem(stack)) continue;

            int count = countItemInShulker(stack, item);
            if (count > 0) debug("Candidate shulker slot " + slot + ": " + describeStack(stack) + ", item-count=" + count);
            if (count > bestCount) {
                bestCount = count;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private int findBestHotbarShulkerWithItem(Item item) {
        int bestSlot = -1;
        int bestCount = 0;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !isShulkerItem(stack)) continue;

            int count = countItemInShulker(stack, item);
            if (count > bestCount) {
                bestCount = count;
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private int countItemInShulker(ItemStack shulker, Item item) {
        ContainerComponent container = shulker.get(DataComponentTypes.CONTAINER);
        if (container == null) return 0;

        int count = 0;
        for (ItemStack contained : container.iterateNonEmpty()) {
            if (!contained.isEmpty() && contained.getItem() == item) count += contained.getCount();
        }
        return count;
    }

    private boolean isShulkerItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private boolean isRefillShulker(ItemStack stack) {
        return isShulkerItem(stack) && countItemInShulker(stack, refillTask.item) > 0;
    }

    private boolean isHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    private int findRefillHotbarSlot(int selectedSlot) {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }

        return selectedSlot;
    }

    private BlockPos findPlacementPos() {
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
            BlockState placeState = mc.world.getBlockState(pos);
            BlockState belowState = mc.world.getBlockState(pos.down());
            if (!placeState.isReplaceable()) continue;
            if (belowState.isAir()) continue;
            return pos;
        }

        return null;
    }

    private int countPlayerItem(Item item) {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
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
        if (mc.player == null || refillTask == null) return;
        mc.player.getInventory().setSelectedSlot(refillTask.hotbarSlot);
    }

    private String formatMissingMaterials() {
        List<String> parts = new ArrayList<>();
        for (MissingMaterial material : missingMaterials) {
            parts.add(material.count + "x " + material.itemName);
        }
        return String.join(", ", parts);
    }

    private void notify(String message) {
        if (chatNotify.get()) info(message);
        if (logNotify.get()) AddonLog.info(message);
    }

    private void debug(String message) {
        if (logNotify.get()) AddonLog.info("[RefillDebug] " + message);
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";

        StringBuilder out = new StringBuilder()
            .append(stack.getItem().getName().getString())
            .append(" x").append(stack.getCount());

        if (isShulkerItem(stack) && refillTask != null) {
            out.append(" [contains ").append(countItemInShulker(stack, refillTask.item)).append("x ").append(refillTask.itemName).append(']');
        }

        if (stack.contains(DataComponentTypes.CUSTOM_NAME)) out.append(" [named]");
        return out.toString();
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

    private boolean isBuilderPaused() {
        return invokeBuilderBoolean("isPaused");
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

    private record MissingMaterial(int count, String rawMaterial, Item item, String itemName) {
    }

    private static class RefillTask {
        private final Item item;
        private final String itemName;
        private int shulkerSlot;
        private final int hotbarSlot;
        private final int targetHotbarSlot;

        private RefillTask(Item item, String itemName, int shulkerSlot, int hotbarSlot, int targetHotbarSlot) {
            this.item = item;
            this.itemName = itemName;
            this.shulkerSlot = shulkerSlot;
            this.hotbarSlot = hotbarSlot;
            this.targetHotbarSlot = targetHotbarSlot;
        }
    }

    private enum Stage {
        Idle,
        MoveShulkerToHotbar,
        PlaceShulker,
        WaitForPlacement,
        OpenShulker,
        WaitForScreen,
        TransferItems,
        BreakShulker,
        WaitForPickup,
        ResumeBaritone
    }
}



