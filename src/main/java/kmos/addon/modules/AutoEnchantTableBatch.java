package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.util.AddonLog;
import kmos.addon.util.InteractionGate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class AutoEnchantTableBatch extends Module {
    private static final int INTERACT_TIMEOUT_TICKS = 200;
    private static final int TABLE_OPEN_TIMEOUT_TICKS = 41;
    private static final int OPTION_WAIT_TICKS = 30;
    private static final int RESULT_WAIT_TICKS = 30;
    private static final int RETURN_SYNC_TICKS = 20;
    private static final double BLOCK_INTERACT_RANGE = 4.75;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMaterials = settings.createGroup("Materials");
    private final SettingGroup sgItems = settings.createGroup("Items");

    private final Setting<Double> tableRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("enchant-table-range")
        .description("Maximum range used to find an enchanting table.")
        .defaultValue(10.0)
        .min(2.0)
        .max(32.0)
        .sliderRange(2.0, 16.0)
        .build()
    );

    private final Setting<Boolean> requireThirtyLevelOption = sgGeneral.add(new BoolSetting.Builder()
        .name("require-30-level-option")
        .description("Only enchants when the third enchant-table option is available at level 30.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pauses Baritone while batch enchanting is running.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Shows batch enchanting status messages in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes detailed batch enchanting logs to kmos-addon.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> diamondItems = sgMaterials.add(new BoolSetting.Builder()
        .name("diamond-items")
        .description("Includes selected diamond equipment.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> netheriteItems = sgMaterials.add(new BoolSetting.Builder()
        .name("netherite-items")
        .description("Includes selected netherite equipment.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> armor = sgItems.add(new BoolSetting.Builder()
        .name("armor")
        .description("Includes helmets, chestplates, leggings and boots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swords = sgItems.add(new BoolSetting.Builder()
        .name("swords")
        .description("Includes swords.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pickaxes = sgItems.add(new BoolSetting.Builder()
        .name("pickaxes")
        .description("Includes pickaxes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> axes = sgItems.add(new BoolSetting.Builder()
        .name("axes")
        .description("Includes axes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> shovels = sgItems.add(new BoolSetting.Builder()
        .name("shovels")
        .description("Includes shovels.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hoes = sgItems.add(new BoolSetting.Builder()
        .name("hoes")
        .description("Includes hoes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> bows = sgItems.add(new BoolSetting.Builder()
        .name("bows")
        .description("Includes bows.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> crossbows = sgItems.add(new BoolSetting.Builder()
        .name("crossbows")
        .description("Includes crossbows.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> tridents = sgItems.add(new BoolSetting.Builder()
        .name("tridents")
        .description("Includes tridents.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fishingRods = sgItems.add(new BoolSetting.Builder()
        .name("fishing-rods")
        .description("Includes fishing rods.")
        .defaultValue(false)
        .build()
    );

    private Stage stage = Stage.Idle;
    private BlockPos tablePos;
    private boolean pathingStarted;
    private boolean pausedBaritone;
    private int stageTicks;
    private int targetInventorySlot = -1;
    private String targetItemId;
    private String targetItemName;
    private String resultSignature;
    private int enchantedCount;
    private boolean preflightChecked;
    private int plannedItemCount;
    private int plannedLapisCost;
    private int plannedMinimumLevel;
    private final Set<BlockPos> failedTables = new HashSet<>();

    public AutoEnchantTableBatch() {
        super(KmosAddon.CATEGORY, "auto-enchant-table-batch", "Enchants selected clean inventory items at the nearest enchanting table.");
    }

    @Override
    public void onActivate() {
        resetState();
        notifyInfo("Batch enchant-table run started.");
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        if (pausedBaritone) executeBaritoneCommand("resume");
        resetState();
        InteractionGate.release(InteractionGate.Owner.AutomationFlow);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);

        if (!InteractionGate.acquire(InteractionGate.Owner.AutomationFlow, now)) return;
        runStage();
    }

    private void runStage() {
        switch (stage) {
            case Idle -> prepareNextItem();
            case MoveToTable -> moveToTable();
            case OpenTable -> openTable();
            case WaitForTable -> waitForTable();
            case LoadInputs -> loadInputs();
            case WaitForOptions -> waitForOptions();
            case ClickOption -> clickOption();
            case WaitForResult -> waitForResult();
            case ReturnItem -> returnItem();
            case CloseTable -> closeTable();
            case Done -> finishAndToggle();
        }
    }

    private void prepareNextItem() {
        if (mc.currentScreen != null) return;

        if (!ensurePreflight()) return;

        targetInventorySlot = findNextTargetSlot();
        if (targetInventorySlot < 0) {
            notifyInfo("Batch enchant-table run finished. Enchanted " + enchantedCount + " item(s).");
            stage = Stage.Done;
            return;
        }

        if (!mc.player.isCreative()) {
            if (mc.player.experienceLevel < minimumRequiredLevels()) {
                notifyWarn("Not enough levels for the next enchant-table item. Enchanted " + enchantedCount + " item(s).");
                stage = Stage.Done;
                return;
            }
            if (countInventoryItem(Items.LAPIS_LAZULI) < minimumRequiredLapis()) {
                notifyWarn("Not enough lapis for the next enchant-table item. Enchanted " + enchantedCount + " item(s).");
                stage = Stage.Done;
                return;
            }
        }

        ItemStack target = mc.player.getInventory().getStack(targetInventorySlot);
        targetItemId = getItemId(target);
        targetItemName = target.getName().getString();
        resultSignature = null;

        tablePos = findNearestEnchantTable();
        if (tablePos == null) {
            notifyWarn("No enchanting table found within range " + (int) tableRange.get().doubleValue() + ".");
            stage = Stage.Done;
            return;
        }

        if (pauseBaritone.get() && !pausedBaritone && executeBaritoneCommand("pause")) pausedBaritone = true;

        pathingStarted = false;
        stageTicks = INTERACT_TIMEOUT_TICKS;
        stage = Stage.MoveToTable;
        debug("Selected clean item '" + targetItemName + "' in inventory slot " + targetInventorySlot + ", table=" + tablePos + ".");
    }

    private boolean ensurePreflight() {
        if (preflightChecked) return true;

        plannedItemCount = countSelectedCleanTargets();
        if (plannedItemCount <= 0) {
            notifyInfo("Batch enchant-table run finished. No selected clean item(s) found.");
            stage = Stage.Done;
            return false;
        }

        plannedLapisCost = plannedItemCount * plannedLapisPerItem();
        plannedMinimumLevel = plannedMinimumStartingLevel(plannedItemCount);
        preflightChecked = true;

        if (mc.player.isCreative()) {
            notifyInfo("Batch enchant-table preflight: " + plannedItemCount + " item(s), creative mode skips lapis/level checks.");
            return true;
        }

        int availableLapis = countInventoryItem(Items.LAPIS_LAZULI);
        int availableLevel = mc.player.experienceLevel;

        if (availableLapis < plannedLapisCost) {
            notifyWarn("Missing lapis for batch enchant-table: need " + plannedLapisCost + ", have " + availableLapis + " for " + plannedItemCount + " item(s).");
            stage = Stage.Done;
            return false;
        }

        if (availableLevel < plannedMinimumLevel) {
            notifyWarn("Missing levels for batch enchant-table: need level " + plannedMinimumLevel + ", have " + availableLevel + " for " + plannedItemCount + " item(s).");
            stage = Stage.Done;
            return false;
        }

        notifyInfo("Batch enchant-table preflight: " + plannedItemCount + " item(s), needs " + plannedLapisCost + " lapis and starting level " + plannedMinimumLevel + ".");
        return true;
    }

    private void moveToTable() {
        if (canInteractWithBlock(tablePos)) {
            executeBaritoneCommand("stop");
            stage = Stage.OpenTable;
            return;
        }

        if (!pathingStarted || stageTicks % 20 == 0) {
            BlockPos approachPos = findInteractionApproachPos(tablePos);
            BlockPos pathTarget = approachPos != null ? approachPos : tablePos;
            if (!executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ())) {
                failedTables.add(tablePos.toImmutable());
                stage = Stage.Idle;
                return;
            }
            pathingStarted = true;
        }

        if (--stageTicks <= 0) {
            failedTables.add(tablePos.toImmutable());
            stage = Stage.Idle;
        }
    }

    private void openTable() {
        if (mc.world.getBlockState(tablePos).getBlock() != Blocks.ENCHANTING_TABLE) {
            failedTables.add(tablePos.toImmutable());
            stage = Stage.Idle;
            return;
        }

        interactBlock(tablePos);
        stageTicks = TABLE_OPEN_TIMEOUT_TICKS;
        stage = Stage.WaitForTable;
    }

    private void waitForTable() {
        if (mc.currentScreen instanceof EnchantmentScreen) {
            stageTicks = 4;
            stage = Stage.LoadInputs;
            return;
        }

        if (--stageTicks <= 0) {
            failedTables.add(tablePos.toImmutable());
            stage = Stage.Idle;
        }
    }

    private void loadInputs() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            stage = Stage.Idle;
            return;
        }

        int currentTargetSlot = findMatchingTargetSlot();
        if (currentTargetSlot < 0) {
            if (stageTicks-- > 0) return;
            notifyWarn("Selected target item disappeared before enchanting.");
            stage = Stage.CloseTable;
            return;
        }
        targetInventorySlot = currentTargetSlot;

        int itemHandlerSlot = findHandlerSlotForInventoryIndex(handler, targetInventorySlot);
        if (itemHandlerSlot < 0) {
            notifyWarn("Could not map target item into enchant-table screen.");
            stage = Stage.CloseTable;
            return;
        }

        if (!handler.getSlot(0).hasStack()) {
            mc.interactionManager.clickSlot(handler.syncId, itemHandlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
        }

        if (!mc.player.isCreative() && handler.getLapisCount() < minimumRequiredLapis()) {
            int lapisSlot = findInventorySlot(Items.LAPIS_LAZULI);
            if (lapisSlot < 0) {
                notifyWarn("Lapis disappeared before enchanting.");
                stage = Stage.CloseTable;
                return;
            }

            int lapisHandlerSlot = findHandlerSlotForInventoryIndex(handler, lapisSlot);
            if (lapisHandlerSlot < 0) {
                notifyWarn("Could not map lapis into enchant-table screen.");
                stage = Stage.CloseTable;
                return;
            }

            mc.interactionManager.clickSlot(handler.syncId, lapisHandlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
        }

        stageTicks = OPTION_WAIT_TICKS;
        stage = Stage.WaitForOptions;
    }

    private void waitForOptions() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            stage = Stage.Idle;
            return;
        }

        if (handler.getSlot(0).getStack().isEmpty()) {
            if (--stageTicks <= 0) {
                notifyWarn("Enchant-table input did not sync for " + targetItemName + ".");
                stage = Stage.CloseTable;
            }
            return;
        }

        int option = chooseOption(handler);
        if (option < 0) {
            if (--stageTicks <= 0) {
                notifyWarn("No usable enchant-table option for " + targetItemName + ".");
                stage = Stage.CloseTable;
            }
            return;
        }

        if (!mc.player.isCreative()) {
            int lapisCost = option + 1;
            int levelCost = handler.enchantmentPower[option];
            if (handler.getLapisCount() < lapisCost || mc.player.experienceLevel < levelCost) {
                notifyWarn("Not enough lapis or levels for " + targetItemName + ".");
                stage = Stage.CloseTable;
                return;
            }
        }

        stage = Stage.ClickOption;
    }

    private void clickOption() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            stage = Stage.Idle;
            return;
        }

        int option = chooseOption(handler);
        if (option < 0) {
            notifyWarn("Enchant-table option became unavailable for " + targetItemName + ".");
            stage = Stage.CloseTable;
            return;
        }

        mc.interactionManager.clickButton(handler.syncId, option);
        stageTicks = RESULT_WAIT_TICKS;
        stage = Stage.WaitForResult;
    }

    private void waitForResult() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            stage = Stage.Idle;
            return;
        }

        ItemStack input = handler.getSlot(0).getStack();
        if (!input.isEmpty() && targetItemId.equals(getItemId(input)) && !getEnchantIdMap(input).isEmpty()) {
            resultSignature = buildEnchantSignature(getEnchantIdMap(input));
            mc.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
            stageTicks = RETURN_SYNC_TICKS;
            stage = Stage.ReturnItem;
            return;
        }

        if (--stageTicks <= 0) {
            notifyWarn("Enchant-table result did not apply for " + targetItemName + ".");
            stage = Stage.CloseTable;
        }
    }

    private void returnItem() {
        if (findReturnedResultSlot() >= 0) {
            enchantedCount++;
            notifyInfo("Enchanted " + targetItemName + ".");
            stage = Stage.CloseTable;
            return;
        }

        if (--stageTicks <= 0) {
            notifyWarn("Enchanted item did not return to inventory: " + targetItemName + ".");
            stage = Stage.CloseTable;
        }
    }

    private void closeTable() {
        if (mc.player.currentScreenHandler instanceof EnchantmentScreenHandler) mc.player.closeHandledScreen();
        targetInventorySlot = -1;
        targetItemId = null;
        targetItemName = null;
        resultSignature = null;
        stage = Stage.Idle;
    }

    private void finishAndToggle() {
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        if (pausedBaritone) {
            executeBaritoneCommand("resume");
            pausedBaritone = false;
        }
        InteractionGate.release(InteractionGate.Owner.AutomationFlow);
        if (isActive()) toggle();
    }

    private int chooseOption(EnchantmentScreenHandler handler) {
        if (requireThirtyLevelOption.get()) return handler.enchantmentPower[2] >= 30 ? 2 : -1;

        for (int i = 2; i >= 0; i--) {
            if (handler.enchantmentPower[i] > 0) return i;
        }
        return -1;
    }

    private int findNextTargetSlot() {
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (isSelectedCleanTarget(stack)) return i;
        }
        return -1;
    }

    private int countSelectedCleanTargets() {
        int count = 0;
        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (isSelectedCleanTarget(inventory.getStack(i))) count++;
        }
        return count;
    }

    private int findMatchingTargetSlot() {
        PlayerInventory inventory = mc.player.getInventory();
        if (targetInventorySlot >= 0 && targetInventorySlot < 36 && matchesCurrentTarget(inventory.getStack(targetInventorySlot))) {
            return targetInventorySlot;
        }

        for (int i = 0; i < 36; i++) {
            if (matchesCurrentTarget(inventory.getStack(i))) return i;
        }
        return -1;
    }

    private boolean matchesCurrentTarget(ItemStack stack) {
        return !stack.isEmpty()
            && targetItemId != null
            && targetItemId.equals(getItemId(stack))
            && getEnchantIdMap(stack).isEmpty();
    }

    private int findReturnedResultSlot() {
        if (resultSignature == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()
                && targetItemId.equals(getItemId(stack))
                && resultSignature.equals(buildEnchantSignature(getEnchantIdMap(stack)))) {
                return i;
            }
        }

        return -1;
    }

    private boolean isSelectedCleanTarget(ItemStack stack) {
        if (stack.isEmpty() || !getEnchantIdMap(stack).isEmpty()) return false;
        Item item = stack.getItem();

        if (armor.get() && isSelectedMaterialArmor(item)) return true;
        if (swords.get() && isSelectedMaterialTool(item, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD)) return true;
        if (pickaxes.get() && isSelectedMaterialTool(item, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) return true;
        if (axes.get() && isSelectedMaterialTool(item, Items.DIAMOND_AXE, Items.NETHERITE_AXE)) return true;
        if (shovels.get() && isSelectedMaterialTool(item, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL)) return true;
        if (hoes.get() && isSelectedMaterialTool(item, Items.DIAMOND_HOE, Items.NETHERITE_HOE)) return true;

        return (bows.get() && item == Items.BOW)
            || (crossbows.get() && item == Items.CROSSBOW)
            || (tridents.get() && item == Items.TRIDENT)
            || (fishingRods.get() && item == Items.FISHING_ROD);
    }

    private boolean isSelectedMaterialArmor(Item item) {
        if (diamondItems.get()
            && (item == Items.DIAMOND_HELMET
            || item == Items.DIAMOND_CHESTPLATE
            || item == Items.DIAMOND_LEGGINGS
            || item == Items.DIAMOND_BOOTS)) {
            return true;
        }

        return netheriteItems.get()
            && (item == Items.NETHERITE_HELMET
            || item == Items.NETHERITE_CHESTPLATE
            || item == Items.NETHERITE_LEGGINGS
            || item == Items.NETHERITE_BOOTS);
    }

    private boolean isSelectedMaterialTool(Item item, Item diamond, Item netherite) {
        return (diamondItems.get() && item == diamond) || (netheriteItems.get() && item == netherite);
    }

    private int minimumRequiredLevels() {
        return requireThirtyLevelOption.get() ? 30 : 1;
    }

    private int minimumRequiredLapis() {
        return requireThirtyLevelOption.get() ? 3 : 1;
    }

    private int plannedLapisPerItem() {
        // Non-30 mode still prefers the highest available option, so reserve the max table cost.
        return 3;
    }

    private int plannedMinimumStartingLevel(int itemCount) {
        if (itemCount <= 0) return 0;
        int firstEnchantRequiredLevel = minimumRequiredLevels();
        int levelCostPerEnchant = plannedLapisPerItem();
        return firstEnchantRequiredLevel + levelCostPerEnchant * (itemCount - 1);
    }

    private int countInventoryItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int findInventorySlot(Item item) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) return i;
        }
        return -1;
    }

    private int findHandlerSlotForInventoryIndex(ScreenHandler handler, int inventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) return i;
        }
        return -1;
    }

    private BlockPos findNearestEnchantTable() {
        BlockPos center = mc.player.getBlockPos();
        int radius = Math.max(2, (int) Math.ceil(tableRange.get()));
        return BlockPos.streamOutwards(center, radius, radius, radius)
            .filter(pos -> !failedTables.contains(pos))
            .filter(pos -> mc.world.getBlockState(pos).getBlock() == Blocks.ENCHANTING_TABLE)
            .map(BlockPos::toImmutable)
            .filter(pos -> findInteractionApproachPos(pos) != null)
            .min(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - center.getY()))
                .thenComparingDouble(this::horizontalDistanceSq)
                .thenComparingDouble(this::distanceToPos))
            .orElse(null);
    }

    private boolean canInteractWithBlock(BlockPos pos) {
        return distanceToPos(pos) <= BLOCK_INTERACT_RANGE * BLOCK_INTERACT_RANGE;
    }

    private BlockPos findInteractionApproachPos(BlockPos target) {
        if (canInteractWithBlock(target)) return mc.player.getBlockPos().toImmutable();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    BlockPos candidate = target.add(dx, dy, dz);
                    if (!canStandAt(candidate)) continue;
                    if (getEyePosForStand(candidate).squaredDistanceTo(Vec3d.ofCenter(target)) > (BLOCK_INTERACT_RANGE + 0.75) * (BLOCK_INTERACT_RANGE + 0.75)) continue;

                    double score = horizontalDistanceSq(candidate)
                        + Math.abs(candidate.getY() - mc.player.getBlockY()) * 4.0
                        + Math.abs(dx) + Math.abs(dz) * 0.05;

                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean canStandAt(BlockPos pos) {
        BlockState feetState = mc.world.getBlockState(pos);
        BlockState headState = mc.world.getBlockState(pos.up());
        BlockState belowState = mc.world.getBlockState(pos.down());

        if (!feetState.isAir() && !feetState.isReplaceable()) return false;
        if (!headState.isAir() && !headState.isReplaceable()) return false;
        return !belowState.isAir() && !belowState.isReplaceable();
    }

    private double horizontalDistanceSq(BlockPos pos) {
        double dx = mc.player.getX() - (pos.getX() + 0.5);
        double dz = mc.player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private double distanceToPos(BlockPos pos) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
    }

    private Vec3d getEyePosForStand(BlockPos pos) {
        double eyeHeight = mc.player != null ? mc.player.getEyeHeight(mc.player.getPose()) : 1.62;
        return new Vec3d(pos.getX() + 0.5, pos.getY() + eyeHeight, pos.getZ() + 0.5);
    }

    private void interactBlock(BlockPos pos) {
        aimAt(pos);
        BlockHitResult hit = buildHitResult(pos);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private BlockHitResult buildHitResult(BlockPos pos) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = eyePos.x - center.x;
        double dy = eyePos.y - center.y;
        double dz = eyePos.z - center.z;

        Direction side;
        if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
            side = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (Math.abs(dz) >= Math.abs(dy)) {
            side = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            side = dy > 0 ? Direction.UP : Direction.DOWN;
        }

        Vec3d hitPos = center.add(Vec3d.of(side.getVector()).multiply(0.5));
        return new BlockHitResult(hitPos, side, pos, false);
    }

    private void aimAt(BlockPos pos) {
        Vec3d from = mc.player.getEyePos();
        Vec3d to = Vec3d.ofCenter(pos);
        Vec3d diff = to.subtract(from);
        double dist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        mc.player.setYaw((float) (Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(diff.y, dist)));
    }

    private String getItemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private Map<String, Integer> getEnchantIdMap(ItemStack stack) {
        ItemEnchantmentsComponent component = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        Map<String, Integer> out = new TreeMap<>();
        for (var entry : component.getEnchantmentEntries()) {
            RegistryEntry<Enchantment> enchantment = entry.getKey();
            Optional<RegistryKey<Enchantment>> key = enchantment.getKey();
            if (key.isEmpty()) continue;
            Identifier id = key.get().getValue();
            out.put(id.getPath(), entry.getIntValue());
        }
        return out;
    }

    private String buildEnchantSignature(Map<String, Integer> enchants) {
        return enchants.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(";"));
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
            Object result = execute.invoke(manager, command.startsWith("#") ? command.substring(1) : command);
            debug("Executed Baritone command '" + command + "' -> " + result);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            debug("Failed to execute Baritone command '" + command + "': " + t.getClass().getSimpleName());
            return false;
        }
    }

    private void resetState() {
        stage = Stage.Idle;
        tablePos = null;
        pathingStarted = false;
        pausedBaritone = false;
        stageTicks = 0;
        targetInventorySlot = -1;
        targetItemId = null;
        targetItemName = null;
        resultSignature = null;
        enchantedCount = 0;
        preflightChecked = false;
        plannedItemCount = 0;
        plannedLapisCost = 0;
        plannedMinimumLevel = 0;
        failedTables.clear();
    }

    private void notifyInfo(String message) {
        if (chatNotify.get()) ChatUtils.infoPrefix("Auto Enchant Table", message);
        AddonLog.info(message);
    }

    private void notifyWarn(String message) {
        if (chatNotify.get()) ChatUtils.warningPrefix("Auto Enchant Table", message);
        AddonLog.info(message);
    }

    private void debug(String message) {
        if (debugLogging.get()) AddonLog.info("[AutoEnchantTableBatchDebug] " + message);
    }

    private enum Stage {
        Idle,
        MoveToTable,
        OpenTable,
        WaitForTable,
        LoadInputs,
        WaitForOptions,
        ClickOption,
        WaitForResult,
        ReturnItem,
        CloseTable,
        Done
    }
}
