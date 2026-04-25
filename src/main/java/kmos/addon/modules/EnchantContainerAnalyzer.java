package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.enchanting.EnchantStorageEntry;
import kmos.addon.enchanting.EnchantingPlanSolver;
import kmos.addon.enchanting.StoredEnchantBook;
import kmos.addon.settings.ActionButtonSetting;
import kmos.addon.settings.EnchantStorageEntryListSetting;
import kmos.addon.util.AddonLog;
import kmos.addon.util.InteractionGate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShovelItem;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class EnchantContainerAnalyzer extends Module {
    private static final int STORAGE_SYNC_DELAY_TICKS = 1;
    private static final int INTERACT_TIMEOUT_TICKS = 200;
    private static final int STORAGE_OPEN_TIMEOUT_TICKS = 41;
    private static final int ANVIL_OPEN_TIMEOUT_TICKS = 41;
    private static final int ENCHANT_TABLE_OPEN_TIMEOUT_TICKS = 41;
    private static final int ENCHANT_OPTIONS_TIMEOUT_TICKS = 20;
    private static final int ENCHANT_RESULT_TIMEOUT_TICKS = 20;
    private static final int ENCHANT_CLOSE_SYNC_TICKS = 13;
    private static final int RESULT_WAIT_TICKS = 11;
    private static final int TAKE_RESULT_SYNC_TICKS = 13;
    private static final int FINAL_RESULT_SYNC_TICKS = 13;
    private static final int MAX_ANVIL_STEP_RETRIES = 3;
    private static final double BLOCK_INTERACT_RANGE = 4.75;
    private static final long PLANNING_TIMEOUT_MS = 3000;
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgEnchanting = settings.createGroup("Enchanting");
    private final SettingGroup sgSwordTarget = settings.createGroup("Sword Target");
    private final SettingGroup sgPickaxeTarget = settings.createGroup("Pickaxe Target");
    private final SettingGroup sgAxeTarget = settings.createGroup("Axe Target");
    private final SettingGroup sgShovelTarget = settings.createGroup("Shovel Target");
    private final SettingGroup sgHoeTarget = settings.createGroup("Hoe Target");
    private final SettingGroup sgFishingRodTarget = settings.createGroup("Fishing Rod Target");
    private final SettingGroup sgHelmetTarget = settings.createGroup("Helmet Target");
    private final SettingGroup sgChestplateTarget = settings.createGroup("Chestplate Target");
    private final SettingGroup sgLeggingsTarget = settings.createGroup("Leggings Target");
    private final SettingGroup sgBootsTarget = settings.createGroup("Boots Target");

    private final Setting<Boolean> scanOpenedContainers = sgGeneral.add(new BoolSetting.Builder()
        .name("scan-opened-containers")
        .description("Learns newly opened storage blocks automatically and updates their cached book inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes detailed auto-enchanting logs to kmos-addon.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> detectRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("detect-range")
        .description("Maximum range used to resolve which nearby storage block is currently open.")
        .defaultValue(5.0)
        .min(2.0)
        .max(8.0)
        .sliderRange(2.0, 8.0)
        .build()
    );

    private final Setting<List<EnchantStorageEntry>> storages = sgStorage.add(new EnchantStorageEntryListSetting.Builder()
        .name("storages")
        .description("Known storage positions and their last scanned enchant inventory.")
        .build()
    );

    private final Setting<Keybind> maxHeldItemBind = sgEnchanting.add(new KeybindSetting.Builder()
        .name("max-held-item-bind")
        .description("Starts max-enchanting for the item currently held in your main hand.")
        .defaultValue(Keybind.none())
        .action(this::queueManualEnchant)
        .build()
    );

    private final Setting<Keybind> previewMaxHeldItemBind = sgEnchanting.add(new KeybindSetting.Builder()
        .name("preview-max-held-item-bind")
        .description("Calculates the cheapest max-enchant plan for the held item without executing it.")
        .defaultValue(Keybind.none())
        .action(this::queueManualPreview)
        .build()
    );

    private final Setting<Boolean> triggerNow = sgEnchanting.add(new ActionButtonSetting.Builder()
        .name("max-held-item-now")
        .description("Runs the max-held-item flow immediately.")
        .buttonText("Max Held Item Now")
        .action(this::queueManualEnchant)
        .build()
    );

    private final Setting<Boolean> previewNow = sgEnchanting.add(new ActionButtonSetting.Builder()
        .name("preview-max-held-item-now")
        .description("Calculates the held-item max-enchant plan immediately without enchanting.")
        .buttonText("Preview Max Cost")
        .action(this::queueManualPreview)
        .build()
    );

    private final Setting<Boolean> chatNotify = sgEnchanting.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Shows auto-enchanting status messages in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> anvilRange = sgEnchanting.add(new DoubleSetting.Builder()
        .name("anvil-range")
        .description("Maximum range used to look for an anvil while enchanting.")
        .defaultValue(10.0)
        .min(2.0)
        .max(32.0)
        .sliderRange(2.0, 16.0)
        .build()
    );

    private final Setting<Boolean> autoEnchantCleanItems = sgEnchanting.add(new BoolSetting.Builder()
        .name("auto-enchant-clean-items")
        .description("For completely clean supported items, finds a nearby enchant table, applies the 30-level option, then previews the max-enchant route and waits for confirmation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> enchantTableRange = sgEnchanting.add(new DoubleSetting.Builder()
        .name("enchant-table-range")
        .description("Maximum range used to look for an enchanting table during the clean-item bootstrap.")
        .defaultValue(10.0)
        .min(2.0)
        .max(32.0)
        .sliderRange(2.0, 16.0)
        .visible(autoEnchantCleanItems::get)
        .build()
    );

    private final Setting<Boolean> continueAfterTableEnchant = sgEnchanting.add(new ActionButtonSetting.Builder()
        .name("continue-after-table-enchant")
        .description("Starts max-enchanting after the enchant-table bootstrap preview.")
        .buttonText("Continue After Table Enchant")
        .visible(this::hasPendingPostTableConfirmation)
        .action(this::confirmPendingPostTableEnchant)
        .build()
    );

    private final Setting<Boolean> pauseBaritoneDuringEnchanting = sgEnchanting.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pauses Baritone while books are fetched and anvil steps are executed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> localAllowBreakFalse = sgEnchanting.add(new BoolSetting.Builder()
        .name("local-allow-break-false")
        .description("Temporarily forces Baritone allowBreak=false only while Auto Enchanting is running.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> renameFinalItem = sgEnchanting.add(new BoolSetting.Builder()
        .name("rename-final-item")
        .description("Renames the finished item during the last anvil step.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> finalItemNameTemplate = sgEnchanting.add(new StringSetting.Builder()
        .name("final-item-name")
        .description("Template used when renaming the finished item. Use <item> or <shortitem> as placeholders.")
        .defaultValue("<item>")
        .visible(renameFinalItem::get)
        .build()
    );

    private final Setting<Boolean> preferFortuneOnTools = sgEnchanting.add(new BoolSetting.Builder()
        .name("prefer-fortune-on-tools")
        .description("Uses Fortune over Silk Touch on tools where both conflict.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferDepthStrider = sgEnchanting.add(new BoolSetting.Builder()
        .name("prefer-depth-strider")
        .description("Uses Depth Strider over Frost Walker on boots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeThorns = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-thorns")
        .description("Adds Thorns to armor targets when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeKnockback = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-knockback")
        .description("Adds Knockback to swords when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeFireAspect = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-fire-aspect")
        .description("Adds Fire Aspect to swords when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeSwiftSneak = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-swift-sneak")
        .description("Adds Swift Sneak to leggings when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> includeSoulSpeed = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-soul-speed")
        .description("Adds Soul Speed to boots when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> preferBlastProtectionOnLeggings = sgEnchanting.add(new BoolSetting.Builder()
        .name("prefer-blast-protection-on-leggings")
        .description("Uses Blast Protection over regular Protection on leggings unless the held leggings already have a conflicting protection enchant.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> includeSharpnessOnAxes = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-sharpness-on-axes")
        .description("Adds Sharpness to axes when planning max enchants.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> includeEfficiencyOnAxes = sgEnchanting.add(new BoolSetting.Builder()
        .name("include-efficiency-on-axes")
        .description("Adds Efficiency to axes when planning max enchants.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customSwordTarget = toggleTargetGroup(sgSwordTarget, "custom-sword-target", "Uses the sword target settings below instead of the default sword preset.");
    private final Setting<Integer> swordSharpness = targetLevel(sgSwordTarget, customSwordTarget, "sharpness", 5);
    private final Setting<Integer> swordUnbreaking = targetLevel(sgSwordTarget, customSwordTarget, "unbreaking", 3);
    private final Setting<Integer> swordMending = targetLevel(sgSwordTarget, customSwordTarget, "mending", 1);
    private final Setting<Integer> swordLooting = targetLevel(sgSwordTarget, customSwordTarget, "looting", 3);
    private final Setting<Integer> swordSweepingEdge = targetLevel(sgSwordTarget, customSwordTarget, "sweeping-edge", 3);
    private final Setting<Integer> swordFireAspect = targetLevel(sgSwordTarget, customSwordTarget, "fire-aspect", 2);
    private final Setting<Integer> swordKnockback = targetLevel(sgSwordTarget, customSwordTarget, "knockback", 2);

    private final Setting<Boolean> customPickaxeTarget = toggleTargetGroup(sgPickaxeTarget, "custom-pickaxe-target", "Uses the pickaxe target settings below instead of the default pickaxe preset.");
    private final Setting<Integer> pickaxeEfficiency = targetLevel(sgPickaxeTarget, customPickaxeTarget, "efficiency", 5);
    private final Setting<Integer> pickaxeFortune = targetLevel(sgPickaxeTarget, customPickaxeTarget, "fortune", 3);
    private final Setting<Integer> pickaxeSilkTouch = targetLevel(sgPickaxeTarget, customPickaxeTarget, "silk-touch", 1);
    private final Setting<Integer> pickaxeUnbreaking = targetLevel(sgPickaxeTarget, customPickaxeTarget, "unbreaking", 3);
    private final Setting<Integer> pickaxeMending = targetLevel(sgPickaxeTarget, customPickaxeTarget, "mending", 1);

    private final Setting<Boolean> customAxeTarget = toggleTargetGroup(sgAxeTarget, "custom-axe-target", "Uses the axe target settings below instead of the default axe preset.");
    private final Setting<Integer> axeEfficiency = targetLevel(sgAxeTarget, customAxeTarget, "efficiency", 5);
    private final Setting<Integer> axeFortune = targetLevel(sgAxeTarget, customAxeTarget, "fortune", 3);
    private final Setting<Integer> axeSilkTouch = targetLevel(sgAxeTarget, customAxeTarget, "silk-touch", 1);
    private final Setting<Integer> axeUnbreaking = targetLevel(sgAxeTarget, customAxeTarget, "unbreaking", 3);
    private final Setting<Integer> axeMending = targetLevel(sgAxeTarget, customAxeTarget, "mending", 1);
    private final Setting<Integer> axeSharpness = targetLevel(sgAxeTarget, customAxeTarget, "sharpness", 5);

    private final Setting<Boolean> customShovelTarget = toggleTargetGroup(sgShovelTarget, "custom-shovel-target", "Uses the shovel target settings below instead of the default shovel preset.");
    private final Setting<Integer> shovelEfficiency = targetLevel(sgShovelTarget, customShovelTarget, "efficiency", 5);
    private final Setting<Integer> shovelFortune = targetLevel(sgShovelTarget, customShovelTarget, "fortune", 3);
    private final Setting<Integer> shovelSilkTouch = targetLevel(sgShovelTarget, customShovelTarget, "silk-touch", 1);
    private final Setting<Integer> shovelUnbreaking = targetLevel(sgShovelTarget, customShovelTarget, "unbreaking", 3);
    private final Setting<Integer> shovelMending = targetLevel(sgShovelTarget, customShovelTarget, "mending", 1);

    private final Setting<Boolean> customHoeTarget = toggleTargetGroup(sgHoeTarget, "custom-hoe-target", "Uses the hoe target settings below instead of the default hoe preset.");
    private final Setting<Integer> hoeEfficiency = targetLevel(sgHoeTarget, customHoeTarget, "efficiency", 5);
    private final Setting<Integer> hoeFortune = targetLevel(sgHoeTarget, customHoeTarget, "fortune", 3);
    private final Setting<Integer> hoeSilkTouch = targetLevel(sgHoeTarget, customHoeTarget, "silk-touch", 1);
    private final Setting<Integer> hoeUnbreaking = targetLevel(sgHoeTarget, customHoeTarget, "unbreaking", 3);
    private final Setting<Integer> hoeMending = targetLevel(sgHoeTarget, customHoeTarget, "mending", 1);

    private final Setting<Boolean> customFishingRodTarget = toggleTargetGroup(sgFishingRodTarget, "custom-fishing-rod-target", "Uses the fishing rod target settings below instead of the default fishing rod preset.");
    private final Setting<Integer> fishingRodLuckOfTheSea = targetLevel(sgFishingRodTarget, customFishingRodTarget, "luck-of-the-sea", 3);
    private final Setting<Integer> fishingRodLure = targetLevel(sgFishingRodTarget, customFishingRodTarget, "lure", 3);
    private final Setting<Integer> fishingRodUnbreaking = targetLevel(sgFishingRodTarget, customFishingRodTarget, "unbreaking", 3);
    private final Setting<Integer> fishingRodMending = targetLevel(sgFishingRodTarget, customFishingRodTarget, "mending", 1);

    private final Setting<Boolean> customHelmetTarget = toggleTargetGroup(sgHelmetTarget, "custom-helmet-target", "Uses the helmet target settings below instead of the default helmet preset.");
    private final Setting<Integer> helmetProtection = targetLevel(sgHelmetTarget, customHelmetTarget, "protection", 4);
    private final Setting<Integer> helmetFireProtection = targetLevel(sgHelmetTarget, customHelmetTarget, "fire-protection", 4);
    private final Setting<Integer> helmetBlastProtection = targetLevel(sgHelmetTarget, customHelmetTarget, "blast-protection", 4);
    private final Setting<Integer> helmetProjectileProtection = targetLevel(sgHelmetTarget, customHelmetTarget, "projectile-protection", 4);
    private final Setting<Integer> helmetRespiration = targetLevel(sgHelmetTarget, customHelmetTarget, "respiration", 3);
    private final Setting<Integer> helmetAquaAffinity = targetLevel(sgHelmetTarget, customHelmetTarget, "aqua-affinity", 1);
    private final Setting<Integer> helmetUnbreaking = targetLevel(sgHelmetTarget, customHelmetTarget, "unbreaking", 3);
    private final Setting<Integer> helmetMending = targetLevel(sgHelmetTarget, customHelmetTarget, "mending", 1);
    private final Setting<Integer> helmetThorns = targetLevel(sgHelmetTarget, customHelmetTarget, "thorns", 3);

    private final Setting<Boolean> customChestplateTarget = toggleTargetGroup(sgChestplateTarget, "custom-chestplate-target", "Uses the chestplate target settings below instead of the default chestplate preset.");
    private final Setting<Integer> chestplateProtection = targetLevel(sgChestplateTarget, customChestplateTarget, "protection", 4);
    private final Setting<Integer> chestplateFireProtection = targetLevel(sgChestplateTarget, customChestplateTarget, "fire-protection", 4);
    private final Setting<Integer> chestplateBlastProtection = targetLevel(sgChestplateTarget, customChestplateTarget, "blast-protection", 4);
    private final Setting<Integer> chestplateProjectileProtection = targetLevel(sgChestplateTarget, customChestplateTarget, "projectile-protection", 4);
    private final Setting<Integer> chestplateUnbreaking = targetLevel(sgChestplateTarget, customChestplateTarget, "unbreaking", 3);
    private final Setting<Integer> chestplateMending = targetLevel(sgChestplateTarget, customChestplateTarget, "mending", 1);
    private final Setting<Integer> chestplateThorns = targetLevel(sgChestplateTarget, customChestplateTarget, "thorns", 3);

    private final Setting<Boolean> customLeggingsTarget = toggleTargetGroup(sgLeggingsTarget, "custom-leggings-target", "Uses the leggings target settings below instead of the default leggings preset.");
    private final Setting<Integer> leggingsProtection = targetLevel(sgLeggingsTarget, customLeggingsTarget, "protection", 4);
    private final Setting<Integer> leggingsFireProtection = targetLevel(sgLeggingsTarget, customLeggingsTarget, "fire-protection", 4);
    private final Setting<Integer> leggingsBlastProtection = targetLevel(sgLeggingsTarget, customLeggingsTarget, "blast-protection", 4);
    private final Setting<Integer> leggingsProjectileProtection = targetLevel(sgLeggingsTarget, customLeggingsTarget, "projectile-protection", 4);
    private final Setting<Integer> leggingsUnbreaking = targetLevel(sgLeggingsTarget, customLeggingsTarget, "unbreaking", 3);
    private final Setting<Integer> leggingsMending = targetLevel(sgLeggingsTarget, customLeggingsTarget, "mending", 1);
    private final Setting<Integer> leggingsThorns = targetLevel(sgLeggingsTarget, customLeggingsTarget, "thorns", 3);
    private final Setting<Integer> leggingsSwiftSneak = targetLevel(sgLeggingsTarget, customLeggingsTarget, "swift-sneak", 3);

    private final Setting<Boolean> customBootsTarget = toggleTargetGroup(sgBootsTarget, "custom-boots-target", "Uses the boots target settings below instead of the default boots preset.");
    private final Setting<Integer> bootsProtection = targetLevel(sgBootsTarget, customBootsTarget, "protection", 4);
    private final Setting<Integer> bootsFireProtection = targetLevel(sgBootsTarget, customBootsTarget, "fire-protection", 4);
    private final Setting<Integer> bootsBlastProtection = targetLevel(sgBootsTarget, customBootsTarget, "blast-protection", 4);
    private final Setting<Integer> bootsProjectileProtection = targetLevel(sgBootsTarget, customBootsTarget, "projectile-protection", 4);
    private final Setting<Integer> bootsFeatherFalling = targetLevel(sgBootsTarget, customBootsTarget, "feather-falling", 4);
    private final Setting<Integer> bootsDepthStrider = targetLevel(sgBootsTarget, customBootsTarget, "depth-strider", 3);
    private final Setting<Integer> bootsFrostWalker = targetLevel(sgBootsTarget, customBootsTarget, "frost-walker", 2);
    private final Setting<Integer> bootsSoulSpeed = targetLevel(sgBootsTarget, customBootsTarget, "soul-speed", 3);
    private final Setting<Integer> bootsUnbreaking = targetLevel(sgBootsTarget, customBootsTarget, "unbreaking", 3);
    private final Setting<Integer> bootsMending = targetLevel(sgBootsTarget, customBootsTarget, "mending", 1);
    private final Setting<Integer> bootsThorns = targetLevel(sgBootsTarget, customBootsTarget, "thorns", 3);

    private int analyzedSyncId = -1;
    private int pendingSyncId = -1;
    private int pendingSyncTicks;
    private BlockPos lastTargetedStoragePos;
    private Direction lastTargetedStorageSide;
    private boolean queuedManualEnchant;
    private boolean queuedManualPreview;
    private AutoEnchantTask task;
    private Boolean restoreAllowBreakValue;
    private final ExecutorService planningExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "KMOS-AutoEnchanting-Planner");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<PlanningOutcome> planningFuture;
    private int planningToken;
    private PendingPostTableConfirmation pendingPostTableConfirmation;

    public EnchantContainerAnalyzer() {
        super(KmosAddon.CATEGORY, "auto-enchanting", "Tracks enchanted-book storage and can max-enchant the item in your main hand.");
    }

    @Override
    public void onActivate() {
        resetScanState();
        queuedManualEnchant = false;
        queuedManualPreview = false;
        cancelPlanning();
        task = null;
        pendingPostTableConfirmation = null;
        info("Monitoring opened storages for enchanted books.");
    }

    @Override
    public void onDeactivate() {
        resetScanState();
        queuedManualEnchant = false;
        queuedManualPreview = false;
        cancelPlanning();
        pendingPostTableConfirmation = null;
        clearTask(false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        updateTargetedStoragePos();
        handleStorageScanning();

        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);

        if (planningFuture != null) {
            handlePlanningCompletion();
            return;
        }

        if (task != null) {
            if (!InteractionGate.acquire(InteractionGate.Owner.AnvilFlow, now)) return;
            runEnchantTask();
            return;
        }

        boolean previewOnly = queuedManualPreview;
        if (!queuedManualEnchant && !queuedManualPreview) return;
        queuedManualEnchant = false;
        queuedManualPreview = false;

        if (!InteractionGate.acquire(InteractionGate.Owner.AnvilFlow, now)) {
            notifyWarn("Auto enchanting is blocked by another active KMOS flow.");
            return;
        }

        startMaxEnchantTask(previewOnly);
    }

    private void resetScanState() {
        analyzedSyncId = -1;
        pendingSyncId = -1;
        pendingSyncTicks = 0;
        lastTargetedStoragePos = null;
        lastTargetedStorageSide = null;
    }

    private void queueManualEnchant() {
        pendingPostTableConfirmation = null;
        queuedManualEnchant = true;
        debug("Queued manual max-enchant trigger.");
    }

    private void queueManualPreview() {
        queuedManualPreview = true;
        debug("Queued manual max-enchant preview trigger.");
    }

    private void handlePlanningCompletion() {
        if (planningFuture == null || !planningFuture.isDone()) return;

        PlanningOutcome outcome;
        try {
            outcome = planningFuture.join();
        } catch (Throwable t) {
            planningFuture = null;
            notifyError("Auto enchanting failed while calculating the plan.");
            Throwable cause = unwrapPlanningThrowable(t);
            debug("Planner thread failed: " + cause.getClass().getSimpleName() + (cause.getMessage() != null ? " - " + cause.getMessage() : ""));
            clearTask(false);
            return;
        }

        planningFuture = null;
        if (outcome.token() != planningToken) return;

        if (outcome.debugSummary() != null && !outcome.debugSummary().isBlank()) debug(outcome.debugSummary());

        if (!outcome.success()) {
            notifyWarn(outcome.message());
            if (outcome.postTablePreview()) pendingPostTableConfirmation = null;
            clearTask(false);
            return;
        }

        notifyInfo("Max-enchant plan cost: " + outcome.totalCost() + " levels.");
        if (outcome.postTablePreview()) {
            finishPostTablePreview(outcome);
            clearTask(false);
            return;
        }

        if (outcome.previewOnly()) {
            notifyInfo("Preview only. No enchanting was started.");
            clearTask(false);
            return;
        }

        if (mc.player.experienceLevel < outcome.totalCost()) {
            notifyError("Not enough levels. Need " + outcome.totalCost() + ", have " + mc.player.experienceLevel + ".");
            clearTask(false);
            return;
        }

        boolean pausedByUs = false;
        if (pauseBaritoneDuringEnchanting.get() && executeBaritoneCommand("pause")) pausedByUs = true;

        task = new AutoEnchantTask(
            outcome.plan(),
            outcome.requirements(),
            outcome.originalSelectedSlot(),
            outcome.totalCost(),
            pausedByUs,
            outcome.desiredFinalName(),
            outcome.itemId(),
            outcome.itemName(),
            outcome.baseEnchants(),
            outcome.baseAnvilUseCount(),
            false
        );
        applyLocalBaritoneSettings();
        task.stage = Stage.FetchBooks;
        notifyInfo("Auto enchanting started for " + outcome.itemName() + ".");
        debug("Selected plan with " + outcome.plan().steps().size() + " merge steps and " + outcome.requirements().size() + " required leaf books from " + outcome.availableTypeCount() + " unique cached book type(s).");
    }

    private void cancelPlanning() {
        planningToken++;
        if (planningFuture != null) planningFuture.cancel(true);
        planningFuture = null;
    }

    private void runEnchantTask() {
        switch (task.stage) {
            case PrepareEnchantTable -> prepareEnchantTable();
            case MoveToEnchantTable -> moveToEnchantTable();
            case OpenEnchantTable -> openEnchantTable();
            case WaitForEnchantTable -> waitForEnchantTable();
            case LoadEnchantTableInputs -> loadEnchantTableInputs();
            case WaitForEnchantOptions -> waitForEnchantOptions();
            case ClickEnchantOption -> clickEnchantOption();
            case WaitForEnchantTableResult -> waitForEnchantTableResult();
            case CloseEnchantTable -> closeEnchantTable();
            case PrepareRenameOnly -> prepareRenameOnly();
            case LoadRenameOnlyInput -> loadRenameOnlyInput();
            case FetchBooks -> fetchBooks();
            case MoveToStorage -> moveToStorage();
            case OpenStorage -> openStorage();
            case WaitForStorage -> waitForStorage();
            case WithdrawBooks -> withdrawBooks();
            case CloseStorage -> closeStorage();
            case PrepareAnvilStep -> prepareAnvilStep();
            case MoveToAnvil -> moveToAnvil();
            case OpenAnvil -> openAnvil();
            case WaitForAnvil -> waitForAnvil();
            case LoadStepInputs -> loadStepInputs();
            case WaitForResult -> waitForResult();
            case TakeResult -> takeResult();
            case CloseAnvil -> closeAnvil();
            case EquipFinalResult -> equipFinalResult();
            case Completed -> completeTask();
            case Failed -> failTask(task.failureReason == null ? "unknown failure" : task.failureReason);
            case Idle -> clearTask(true);
        }
    }

    private void handleStorageScanning() {
        if (mc.currentScreen == null) {
            resetScanState();
            return;
        }
        if (!isStorageScreen()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        int containerSlots = getContainerSlotCount(handler);
        if (containerSlots <= 0) return;
        if (analyzedSyncId == handler.syncId) return;

        if (pendingSyncId != handler.syncId) {
            pendingSyncId = handler.syncId;
            pendingSyncTicks = STORAGE_SYNC_DELAY_TICKS;
            debug("Detected new container syncId=" + handler.syncId + ", delaying scan by " + STORAGE_SYNC_DELAY_TICKS + " tick(s).");
            if (pendingSyncTicks > 0) return;
        }

        if (pendingSyncTicks > 0) {
            pendingSyncTicks--;
            return;
        }

        BlockPos storagePos = resolveOpenedStoragePos();
        if (storagePos == null) {
            debug("Could not resolve opened storage for syncId=" + handler.syncId + ".");
            return;
        }

        boolean knownStorage = isKnownStorage(storagePos);
        if (!knownStorage && !scanOpenedContainers.get()) {
            debug("Ignoring unsaved storage at " + storagePos + " because scan-opened-containers is disabled.");
            analyzedSyncId = handler.syncId;
            pendingSyncId = -1;
            pendingSyncTicks = 0;
            return;
        }

        analyzedSyncId = handler.syncId;
        pendingSyncId = -1;
        pendingSyncTicks = 0;

        String title = getStorageTitle(storagePos);
        List<StoredEnchantBook> books = scanStoredBooks(handler, containerSlots);
        updateStorageEntry(storagePos, title, books);
        notifyInfo("Scanned storage '" + title + "' at " + storagePos.getX() + " " + storagePos.getY() + " " + storagePos.getZ() + ": " + books.stream().mapToInt(StoredEnchantBook::getCount).sum() + " books.");
        debug("Resolved storage at " + storagePos + ", title='" + title + "', books=" + books.stream().mapToInt(StoredEnchantBook::getCount).sum() + ", known=" + knownStorage + ".");
    }

    private void startMaxEnchantTask(boolean previewOnly) {
        startHeldItemFlow(previewOnly, false);
    }

    private void startHeldItemFlow(boolean previewOnly, boolean postTablePreview) {
        if (planningFuture != null) {
            notifyWarn("Auto enchanting is already calculating a plan.");
            return;
        }

        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) {
            notifyWarn("Main hand is empty.");
            clearTask(false);
            return;
        }

        Map<String, Integer> rawEnchants = getEnchantIdMap(held);
        Map<String, Integer> baseEnchants = filterSupportedEnchants(rawEnchants);
        Map<String, Integer> target = buildTargetEnchants(held, baseEnchants);
        if (target.isEmpty()) {
            notifyWarn("Held item is not supported by auto enchanting yet.");
            clearTask(false);
            return;
        }

        if (!previewOnly && !postTablePreview && shouldBootstrapCleanItemAtEnchantTable(held, rawEnchants)) {
            startEnchantTableBootstrap(held);
            return;
        }

        String desiredFinalName = buildDesiredFinalName(held);
        boolean shouldRenameFinalItem = desiredFinalName != null;
        if (matchesTarget(baseEnchants, target)) {
            if (!shouldRenameFinalItem) {
                notifyInfo("Held item is already maxed for the configured target.");
                clearTask(false);
            } else {
                startRenameOnlyTask(held, baseEnchants, desiredFinalName);
            }
            return;
        }

        beginPlanningForHeldItem(held, baseEnchants, target, desiredFinalName, shouldRenameFinalItem, previewOnly, postTablePreview);
    }

    private void beginPlanningForHeldItem(
        ItemStack held,
        Map<String, Integer> baseEnchants,
        Map<String, Integer> target,
        String desiredFinalName,
        boolean shouldRenameFinalItem,
        boolean previewOnly,
        boolean postTablePreview
    ) {
        long planStarted = System.nanoTime();
        List<AggregatedBookType> availableTypes = collectRelevantBookTypes(target, baseEnchants);
        if (availableTypes.isEmpty()) {
            notifyWarn("Missing books for max enchant: " + describeMissingTargetBooks(target, baseEnchants, availableTypes));
            clearTask(false);
            return;
        }
        int token = ++planningToken;
        PlanningSnapshot snapshot = new PlanningSnapshot(
            token,
            getItemId(held),
            held.getName().getString(),
            new LinkedHashMap<>(baseEnchants),
            new LinkedHashMap<>(target),
            copyAggregatedBookTypes(availableTypes),
            getAnvilUseCount(held),
            desiredFinalName,
            shouldRenameFinalItem,
            previewOnly,
            postTablePreview,
            mc.player.getInventory().getSelectedSlot(),
            planStarted
        );
        planningFuture = CompletableFuture
            .supplyAsync(() -> computePlanningOutcome(snapshot), planningExecutor)
            .handle((outcome, throwable) -> {
                if (throwable == null) return outcome;

                Throwable cause = unwrapPlanningThrowable(throwable);
                return PlanningOutcome.failure(
                    snapshot.token(),
                    "Auto enchanting planner hit an internal error.",
                    "Planner internal error for " + snapshot.itemName() + ": " + cause.getClass().getSimpleName() + (cause.getMessage() != null ? " - " + cause.getMessage() : ""),
                    snapshot.previewOnly(),
                    snapshot.postTablePreview()
                );
            });
        notifyInfo(postTablePreview ? "Calculating post-enchant-table preview..." : "Calculating max-enchant plan...");
    }

    private void startRenameOnlyTask(ItemStack held, Map<String, Integer> baseEnchants, String desiredFinalName) {
        if (desiredFinalName.equals(held.getName().getString())) {
            notifyInfo("Held item is already maxed and already has the configured final name.");
            clearTask(false);
            return;
        }

        boolean pausedByUs = false;
        if (pauseBaritoneDuringEnchanting.get() && executeBaritoneCommand("pause")) pausedByUs = true;

        task = AutoEnchantTask.renameOnly(
            mc.player.getInventory().getSelectedSlot(),
            1,
            pausedByUs,
            desiredFinalName,
            getItemId(held),
            held.getName().getString(),
            baseEnchants,
            getAnvilUseCount(held)
        );
        applyLocalBaritoneSettings();
        task.stage = Stage.PrepareRenameOnly;
        notifyInfo("Held item is already maxed. Starting rename-only anvil flow.");
    }

    private PlanningOutcome computePlanningOutcome(PlanningSnapshot snapshot) {
        EnchantingPlanSolver.Node baseNode = EnchantingPlanSolver.Node.item(snapshot.itemName(), snapshot.baseEnchants(), snapshot.anvilUseCount());
        List<PlanningBookSource> genericSources = buildGenericPlanningSources(snapshot.target(), snapshot.baseEnchants());
        String summaryPrefix = "Planner generic-nodes=" + genericSources.size() + ", cached-types=" + snapshot.availableTypes().size() + " for " + snapshot.itemName();
        if (genericSources.isEmpty()) {
            return PlanningOutcome.failure(
                snapshot.token(),
                "No generic book requirements were generated for the held item.",
                summaryPrefix + " -> no generic planning nodes",
                snapshot.previewOnly(),
                snapshot.postTablePreview()
            );
        }

        EnchantingPlanSolver.SearchResult genericSearch;
        try {
            genericSearch = EnchantingPlanSolver.findLowestCostPlanForTarget(
                baseNode,
                genericSources.stream().map(PlanningBookSource::node).toList(),
                snapshot.target(),
                false,
                PLANNING_TIMEOUT_MS / 2
            );
        } catch (IllegalStateException ex) {
            String message = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out")
                ? "Auto enchanting plan timed out while calculating the generic route."
                : "Could not build a generic max-enchant route for this item.";
            return PlanningOutcome.failure(snapshot.token(), message, summaryPrefix + " -> generic route failed: " + ex.getMessage(), snapshot.previewOnly(), snapshot.postTablePreview());
        }

        EnchantingPlanSolver.Plan genericPlan = genericSearch.bestPlan();
        if (genericPlan == null || genericPlan.steps().isEmpty()) {
            return PlanningOutcome.failure(snapshot.token(), "No generic merge steps were generated for the held item.", summaryPrefix + " -> empty generic plan", snapshot.previewOnly(), snapshot.postTablePreview());
        }

        List<BookRequirement> genericRequirements = collectBookRequirements(genericPlan, genericSources);
        List<PlanningBookSource> actualSources = matchActualSources(genericRequirements, snapshot.availableTypes());
        if (actualSources.isEmpty()) {
            return PlanningOutcome.failure(
                snapshot.token(),
                "Missing books for max enchant: " + describeMissingTargetBooks(snapshot.target(), snapshot.baseEnchants(), snapshot.availableTypes()),
                summaryPrefix + " -> no actual sources matched generic requirements",
                snapshot.previewOnly(),
                snapshot.postTablePreview()
            );
        }

        EnchantingPlanSolver.SearchResult actualSearch;
        try {
            actualSearch = EnchantingPlanSolver.findLowestCostPlanForTarget(
                baseNode,
                actualSources.stream().map(PlanningBookSource::node).toList(),
                snapshot.target(),
                false,
                PLANNING_TIMEOUT_MS / 2
            );
        } catch (IllegalStateException ex) {
            String message = ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out")
                ? "Auto enchanting plan timed out. Too many possible combinations for this item target."
                : "Matched books could not produce a valid max-enchant route. Try rescanning the relevant storages.";
            return PlanningOutcome.failure(snapshot.token(), message, summaryPrefix + " -> actual route failed: " + ex.getMessage(), snapshot.previewOnly(), snapshot.postTablePreview());
        }

        if (actualSearch == null) {
            return PlanningOutcome.failure(
                snapshot.token(),
                "Matched books could not produce a valid max-enchant route.",
                summaryPrefix + " -> no valid plan",
                snapshot.previewOnly(),
                snapshot.postTablePreview()
            );
        }

        EnchantingPlanSolver.Plan plan = actualSearch.bestPlan();
        if (plan == null || plan.steps().isEmpty()) {
            return PlanningOutcome.failure(snapshot.token(), "No merge steps were generated for the held item.", summaryPrefix + " -> empty plan", snapshot.previewOnly(), snapshot.postTablePreview());
        }

        List<BookRequirement> requirements = collectBookRequirements(plan, actualSources);
        if (requirements.isEmpty()) {
            return PlanningOutcome.failure(snapshot.token(), "The selected plan did not require any book leaves.", summaryPrefix + " -> no leaf requirements", snapshot.previewOnly(), snapshot.postTablePreview());
        }

        int totalCost = plan.totalCost() + (snapshot.shouldRenameFinalItem() ? 1 : 0);
        String summary = summaryPrefix + " -> success in " + ((System.nanoTime() - snapshot.planStartedNanos()) / 1_000_000.0) + " ms";
        return PlanningOutcome.success(
            snapshot.token(),
            snapshot.itemId(),
            snapshot.itemName(),
            snapshot.baseEnchants(),
            snapshot.anvilUseCount(),
            plan,
            requirements,
            totalCost,
            snapshot.desiredFinalName(),
            snapshot.previewOnly(),
            snapshot.postTablePreview(),
            snapshot.originalSelectedSlot(),
            snapshot.availableTypes().size(),
            summary
        );
    }

    private List<AggregatedBookType> copyAggregatedBookTypes(List<AggregatedBookType> availableTypes) {
        return availableTypes.stream()
            .map(type -> new AggregatedBookType(type.signature(), new LinkedHashMap<>(type.enchants()), type.displayName(), type.totalCount()))
            .toList();
    }

    private List<PlanningBookSource> buildGenericPlanningSources(Map<String, Integer> target, Map<String, Integer> baseEnchants) {
        List<PlanningBookSource> sources = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            String enchant = entry.getKey();
            int targetLevel = entry.getValue();
            int currentLevel = baseEnchants.getOrDefault(enchant, 0);
            if (currentLevel >= targetLevel) continue;

            int requiredLevel = currentLevel > 0 && currentLevel == targetLevel - 1 ? currentLevel : targetLevel;
            Map<String, Integer> enchants = Map.of(enchant, requiredLevel);
            String signature = buildEnchantSignature(enchants);
            String displayName = formatEnchant(enchant, requiredLevel);
            sources.add(new PlanningBookSource(
                signature,
                displayName,
                enchants,
                EnchantingPlanSolver.Node.book(displayName + " [generic] #" + index, enchants, 0)
            ));
            index++;
        }
        return sources;
    }

    private List<PlanningBookSource> matchActualSources(List<BookRequirement> genericRequirements, List<AggregatedBookType> availableTypes) {
        Map<String, Integer> remaining = new HashMap<>();
        for (AggregatedBookType type : availableTypes) remaining.put(type.signature(), type.totalCount());

        List<PlanningBookSource> matched = new ArrayList<>();
        int index = 0;
        for (BookRequirement requirement : genericRequirements) {
            AggregatedBookType best = availableTypes.stream()
                .filter(type -> remaining.getOrDefault(type.signature(), 0) > 0)
                .filter(type -> canSubstitute(type.enchants(), requirement.enchants))
                .sorted((left, right) -> compareAggregatedBookCandidates(left, right, requirement))
                .findFirst()
                .orElse(null);

            if (best == null) return List.of();

            remaining.merge(best.signature(), -1, Integer::sum);
            matched.add(new PlanningBookSource(
                best.signature(),
                best.displayName(),
                best.enchants(),
                EnchantingPlanSolver.Node.book(best.displayName() + " [actual] #" + index, best.enchants(), 0)
            ));
            index++;
        }
        return matched;
    }

    private boolean canSubstitute(Map<String, Integer> actual, Map<String, Integer> required) {
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (actual.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }

    private int totalEnchantLevel(Map<String, Integer> enchants) {
        int total = 0;
        for (int level : enchants.values()) total += level;
        return total;
    }

    private int compareAggregatedBookCandidates(AggregatedBookType left, AggregatedBookType right, BookRequirement requirement) {
        return compareBookCandidates(left.enchants(), right.enchants(), requirement.enchants);
    }

    private void fetchBooks() {
        if (countSatisfiedRequirements(task.requirements) == task.requirements.size()) {
            task.stage = Stage.PrepareAnvilStep;
            return;
        }

        if (task.currentStoragePos == null) {
            task.currentStoragePos = findNextStorageForRequirements();
            if (task.currentStoragePos == null) {
                failTask("Missing books in tracked storage: " + describeMissingRequirements(task.requirements));
                return;
            }
            task.pathingStarted = false;
            task.stageTicks = INTERACT_TIMEOUT_TICKS;
            task.stage = Stage.MoveToStorage;
        }
    }

    private void moveToStorage() {
        ensureBaritoneReadyForModuleCommands();

        if (canInteractWithBlock(task.currentStoragePos)) {
            executeBaritoneCommand("stop");
            task.stage = Stage.OpenStorage;
            return;
        }

        if (!task.pathingStarted) {
            BlockPos approachPos = findInteractionApproachPos(task.currentStoragePos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentStoragePos;
            if (approachPos == null) debug("No explicit storage approach position found for " + task.currentStoragePos + ", falling back to direct goto.");
            if (!executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ())) {
                failTask("Could not start moving toward storage.");
                return;
            }
            task.pathingStarted = true;
        }
        else if (task.stageTicks % 20 == 0) {
            BlockPos approachPos = findInteractionApproachPos(task.currentStoragePos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentStoragePos;
            executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ());
        }

        if (--task.stageTicks <= 0) failTask("Timed out while moving toward storage.");
    }

    private void openStorage() {
        if (!isSupportedStorage(mc.world.getBlockState(task.currentStoragePos).getBlock())) {
            failTask("Tracked storage is no longer present.");
            return;
        }
        interactBlock(task.currentStoragePos);
        task.stage = Stage.WaitForStorage;
        task.stageTicks = STORAGE_OPEN_TIMEOUT_TICKS;
    }

    private void waitForStorage() {
        if (mc.currentScreen instanceof ShulkerBoxScreen || mc.currentScreen instanceof GenericContainerScreen) {
            task.stage = Stage.WithdrawBooks;
            return;
        }
        if (--task.stageTicks <= 0) failTask("Storage UI did not open.");
    }

    private void withdrawBooks() {
        if (!(mc.currentScreen instanceof ShulkerBoxScreen || mc.currentScreen instanceof GenericContainerScreen)) {
            failTask("Storage screen closed before books were withdrawn.");
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int containerSlots = getContainerSlotCount(handler);
        int moved = 0;
        Set<Integer> usedSlots = new HashSet<>();
        Map<String, Integer> movedBySignature = new HashMap<>();

        for (BookRequirement requirement : task.requirements) {
            if (requirement.satisfied) continue;
            int slot = findMatchingBookSlot(handler, containerSlots, requirement, usedSlots);
            if (slot < 0) continue;
            ItemStack matchedStack = handler.getSlot(slot).getStack();
            Map<String, Integer> matchedEnchants = getSupportedEnchantIdMap(matchedStack);
            String matchedSignature = buildEnchantSignature(matchedEnchants);
            String matchedDisplay = buildEnchantDisplay(matchedEnchants);
            mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            usedSlots.add(slot);
            requirement.satisfied = true;
            movedBySignature.merge(matchedSignature, 1, Integer::sum);
            moved++;
            if (matchedSignature.equals(requirement.signature)) debug("Pulled book '" + matchedDisplay + "' from storage " + task.currentStoragePos + ".");
            else debug("Pulled book '" + matchedDisplay + "' from storage " + task.currentStoragePos + " to satisfy '" + requirement.displayName + "'.");
        }

        if (moved == 0) {
            failTask("Required books were not found inside the opened storage: " + describeMissingRequirements(task.requirements));
            return;
        }

        decrementCachedStorageBooks(task.currentStoragePos, movedBySignature);
        task.stage = Stage.CloseStorage;
    }

    private void closeStorage() {
        mc.player.closeHandledScreen();
        task.currentStoragePos = null;
        task.pathingStarted = false;
        task.stage = Stage.FetchBooks;
    }

    private boolean shouldBootstrapCleanItemAtEnchantTable(ItemStack held, Map<String, Integer> rawEnchants) {
        return autoEnchantCleanItems.get()
            && !held.isEmpty()
            && rawEnchants.isEmpty();
    }

    private void startEnchantTableBootstrap(ItemStack held) {
        if (!mc.player.isCreative()) {
            if (mc.player.experienceLevel < 30) {
                notifyWarn("Need at least 30 levels before a clean item can be bootstrapped at an enchant table.");
                clearTask(false);
                return;
            }
            if (countInventoryItem(Items.LAPIS_LAZULI) < 3) {
                notifyWarn("Need at least 3 lapis lazuli before a clean item can be bootstrapped at an enchant table.");
                clearTask(false);
                return;
            }
        }

        boolean pausedByUs = false;
        if (pauseBaritoneDuringEnchanting.get() && executeBaritoneCommand("pause")) pausedByUs = true;

        task = AutoEnchantTask.bootstrap(
            mc.player.getInventory().getSelectedSlot(),
            pausedByUs,
            getItemId(held),
            held.getName().getString(),
            filterSupportedEnchants(getEnchantIdMap(held)),
            getAnvilUseCount(held)
        );
        applyLocalBaritoneSettings();
        task.stage = Stage.PrepareEnchantTable;
        notifyInfo("Clean item detected. Looking for a 30-level enchant table before planning.");
    }

    private void prepareEnchantTable() {
        task.currentEnchantTablePos = findNearestEnchantTable(task.failedEnchantTables);
        if (task.currentEnchantTablePos == null) {
            failTask("No enchanting table found within range " + (int) enchantTableRange.get().doubleValue() + ".");
            return;
        }

        task.pathingStarted = false;
        task.tableEnchantResultDisplay = null;
        task.tableEnchantResultSignature = null;
        task.stageTicks = INTERACT_TIMEOUT_TICKS;
        task.stage = Stage.MoveToEnchantTable;
        debug("Preparing clean-item bootstrap at enchant table " + task.currentEnchantTablePos + ".");
    }

    private void moveToEnchantTable() {
        ensureBaritoneReadyForModuleCommands();

        if (canInteractWithBlock(task.currentEnchantTablePos)) {
            executeBaritoneCommand("stop");
            task.stage = Stage.OpenEnchantTable;
            return;
        }

        if (!task.pathingStarted) {
            BlockPos approachPos = findInteractionApproachPos(task.currentEnchantTablePos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentEnchantTablePos;
            if (approachPos == null) debug("No explicit enchant-table approach position found for " + task.currentEnchantTablePos + ", falling back to direct goto.");
            if (!executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ())) {
                failTask("Could not start moving toward an enchant table.");
                return;
            }
            task.pathingStarted = true;
        }
        else if (task.stageTicks % 20 == 0) {
            BlockPos approachPos = findInteractionApproachPos(task.currentEnchantTablePos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentEnchantTablePos;
            executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ());
        }

        if (--task.stageTicks <= 0) failTask("Timed out while moving toward an enchant table.");
    }

    private void openEnchantTable() {
        if (mc.world.getBlockState(task.currentEnchantTablePos).getBlock() != Blocks.ENCHANTING_TABLE) {
            retryWithAnotherEnchantTable("Selected enchant table is no longer present.");
            return;
        }

        interactBlock(task.currentEnchantTablePos);
        task.stage = Stage.WaitForEnchantTable;
        task.stageTicks = ENCHANT_TABLE_OPEN_TIMEOUT_TICKS;
    }

    private void waitForEnchantTable() {
        if (mc.currentScreen instanceof EnchantmentScreen) {
            task.stage = Stage.LoadEnchantTableInputs;
            task.stageTicks = 4;
            return;
        }

        if (mc.world.getBlockState(task.currentEnchantTablePos).getBlock() != Blocks.ENCHANTING_TABLE) {
            retryWithAnotherEnchantTable("Enchant table broke before the UI opened.");
            return;
        }

        if (--task.stageTicks <= 0) retryWithAnotherEnchantTable("Enchant table UI did not open.");
    }

    private void loadEnchantTableInputs() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            failTask("Enchant table UI closed before the bootstrap inputs were loaded.");
            return;
        }

        int itemInventorySlot = findTaskItemSlot(task.initialItemEnchants, task.initialItemAnvilUseCount);
        if (itemInventorySlot < 0) {
            if (task.stageTicks-- > 0) return;
            failTask("The clean item was no longer found in inventory before enchanting.");
            return;
        }

        int itemHandlerSlot = findHandlerSlotForInventoryIndex(handler, itemInventorySlot);
        if (itemHandlerSlot < 0) {
            failTask("Could not map the clean item into the enchant-table screen.");
            return;
        }

        if (handler.getSlot(0).hasStack()) {
            if (task.stageTicks-- > 0) return;
            failTask("Enchant-table input slot was not empty.");
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, itemHandlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);

        if (!mc.player.isCreative()) {
            Set<Integer> usedLapisSlots = new HashSet<>();
            while (handler.getLapisCount() < 3) {
                int lapisInventorySlot = -1;
                for (int i = 0; i < 36; i++) {
                    if (usedLapisSlots.contains(i)) continue;
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (!stack.isOf(Items.LAPIS_LAZULI)) continue;
                    lapisInventorySlot = i;
                    break;
                }

                if (lapisInventorySlot < 0) {
                    failTask("Not enough lapis was found in inventory for the 30-level enchant.");
                    return;
                }

                int lapisHandlerSlot = findHandlerSlotForInventoryIndex(handler, lapisInventorySlot);
                if (lapisHandlerSlot < 0) {
                    failTask("Could not map lapis into the enchant-table screen.");
                    return;
                }

                mc.interactionManager.clickSlot(handler.syncId, lapisHandlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
                usedLapisSlots.add(lapisInventorySlot);
            }
        }

        task.stage = Stage.WaitForEnchantOptions;
        task.stageTicks = ENCHANT_OPTIONS_TIMEOUT_TICKS;
    }

    private void waitForEnchantOptions() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            failTask("Enchant table UI closed before the enchant options loaded.");
            return;
        }

        if (handler.getSlot(0).getStack().isEmpty()) {
            if (--task.stageTicks <= 0) failTask("Enchant-table item input did not sync.");
            return;
        }

        boolean optionsLoaded = false;
        for (int i = 0; i < 3; i++) {
            if (handler.enchantmentPower[i] > 0 || handler.enchantmentId[i] >= 0 || handler.enchantmentLevel[i] >= 0) {
                optionsLoaded = true;
                break;
            }
        }

        if (!optionsLoaded) {
            if (--task.stageTicks <= 0) failTask("Enchant-table options did not appear.");
            return;
        }

        if (handler.enchantmentPower[2] < 30) {
            retryWithAnotherEnchantTable("Selected enchant table does not offer a 30-level enchant.");
            return;
        }

        if (!mc.player.isCreative() && (handler.getLapisCount() < 3 || mc.player.experienceLevel < handler.enchantmentPower[2])) {
            failTask("Not enough lapis or levels for the 30-level enchant.");
            return;
        }

        task.tableEnchantResultDisplay = describeEnchantTableOption(handler, 2);
        task.stage = Stage.ClickEnchantOption;
    }

    private void clickEnchantOption() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            failTask("Enchant table UI closed before the 30-level option was clicked.");
            return;
        }

        mc.interactionManager.clickButton(handler.syncId, 2);
        task.stage = Stage.WaitForEnchantTableResult;
        task.stageTicks = ENCHANT_RESULT_TIMEOUT_TICKS;
    }

    private void waitForEnchantTableResult() {
        if (!(mc.player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
            failTask("Enchant table UI closed before the enchanted item could be confirmed.");
            return;
        }

        ItemStack input = handler.getSlot(0).getStack();
        if (!input.isEmpty() && !getEnchantIdMap(input).isEmpty()) {
            Map<String, Integer> rawEnchants = getEnchantIdMap(input);
            task.tableEnchantResultDisplay = buildEnchantDisplay(input);
            task.tableEnchantResultSignature = buildEnchantSignature(rawEnchants);
            task.stage = Stage.CloseEnchantTable;
            task.stageTicks = ENCHANT_CLOSE_SYNC_TICKS;
            return;
        }

        if (--task.stageTicks <= 0) failTask("The enchant-table result did not apply to the item.");
    }

    private void closeEnchantTable() {
        if (mc.player.currentScreenHandler instanceof EnchantmentScreenHandler) mc.player.closeHandledScreen();

        int inventorySlot = findTaskEnchantedItemSlot();
        if (inventorySlot >= 0) {
            if (inventorySlot != task.originalSelectedSlot) {
                InvUtils.quickSwap().from(inventorySlot).toHotbar(task.originalSelectedSlot);
            }
            mc.player.getInventory().setSelectedSlot(task.originalSelectedSlot);
            finishEnchantTableBootstrap();
            return;
        }

        if (--task.stageTicks <= 0) failTask("The enchanted item did not return to inventory after the enchant-table step.");
    }

    private void finishEnchantTableBootstrap() {
        ItemStack held = mc.player.getMainHandStack();
        String applied = task.tableEnchantResultDisplay;
        if ((applied == null || applied.isBlank()) && !held.isEmpty()) applied = buildEnchantDisplay(held);

        clearTask(false);
        if (applied != null && !applied.isBlank()) notifyInfo("Enchant table applied: " + applied + ".");
        startHeldItemFlow(true, true);
    }

    private void retryWithAnotherEnchantTable(String reason) {
        if (task == null || task.currentEnchantTablePos == null) {
            failTask(reason);
            return;
        }

        debug(reason + " Retrying with another enchant table.");
        task.failedEnchantTables.add(task.currentEnchantTablePos.toImmutable());
        if (mc.player != null && mc.currentScreen instanceof EnchantmentScreen) mc.player.closeHandledScreen();
        task.currentEnchantTablePos = null;
        task.pathingStarted = false;
        task.stage = Stage.PrepareEnchantTable;
    }

    private void prepareRenameOnly() {
        task.currentAnvilPos = findNearestAnvil(task.failedAnvils);
        if (task.currentAnvilPos == null) {
            failTask("No anvil found within range " + (int) anvilRange.get().doubleValue() + ".");
            return;
        }

        task.currentAnvilStepRetries = 0;
        task.resultTakeClicked = false;
        task.renameRequestedForCurrentStep = false;
        task.pathingStarted = false;
        task.stageTicks = INTERACT_TIMEOUT_TICKS;
        task.stage = Stage.MoveToAnvil;
        debug("Preparing rename-only anvil flow for " + task.heldItemName + ".");
    }

    private void prepareAnvilStep() {
        if (task.stepIndex >= task.plan.steps().size()) {
            task.stageTicks = FINAL_RESULT_SYNC_TICKS;
            task.stage = Stage.EquipFinalResult;
            return;
        }

        if (task.activeAnvilStepIndex != task.stepIndex) {
            task.activeAnvilStepIndex = task.stepIndex;
            task.currentAnvilStepRetries = 0;
        }

        task.currentStep = task.plan.steps().get(task.stepIndex);
        task.currentAnvilPos = findNearestAnvil(task.failedAnvils);
        if (task.currentAnvilPos == null) {
            failTask("No anvil found within range " + (int) anvilRange.get().doubleValue() + ".");
            return;
        }

        task.resultTakeClicked = false;
        task.renameRequestedForCurrentStep = false;
        task.pathingStarted = false;
        task.stageTicks = INTERACT_TIMEOUT_TICKS;
        task.stage = Stage.MoveToAnvil;
        debug("Preparing anvil step " + (task.stepIndex + 1) + "/" + task.plan.steps().size() + ": " + task.currentStep.summary());
    }

    private void moveToAnvil() {
        ensureBaritoneReadyForModuleCommands();

        if (canInteractWithBlock(task.currentAnvilPos)) {
            executeBaritoneCommand("stop");
            task.stage = Stage.OpenAnvil;
            return;
        }

        if (!task.pathingStarted) {
            BlockPos approachPos = findInteractionApproachPos(task.currentAnvilPos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentAnvilPos;
            if (approachPos == null) debug("No explicit anvil approach position found for " + task.currentAnvilPos + ", falling back to direct goto.");
            if (!executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ())) {
                retryCurrentAnvilStep("Could not start moving toward an anvil.", false);
                return;
            }
            task.pathingStarted = true;
        }
        else if (task.stageTicks % 20 == 0) {
            BlockPos approachPos = findInteractionApproachPos(task.currentAnvilPos);
            BlockPos pathTarget = approachPos != null ? approachPos : task.currentAnvilPos;
            executeBaritoneCommand("goto " + pathTarget.getX() + " " + pathTarget.getY() + " " + pathTarget.getZ());
        }

        if (--task.stageTicks <= 0) retryCurrentAnvilStep("Timed out while moving toward an anvil.", false);
    }

    private void openAnvil() {
        if (!(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
            retryWithAnotherAnvil("Selected anvil is no longer present.");
            return;
        }
        interactBlock(task.currentAnvilPos);
        task.stage = Stage.WaitForAnvil;
        task.stageTicks = ANVIL_OPEN_TIMEOUT_TICKS;
    }

    private void waitForAnvil() {
        if (mc.currentScreen instanceof AnvilScreen) {
            task.stage = task.renameOnly ? Stage.LoadRenameOnlyInput : Stage.LoadStepInputs;
            task.stageTicks = 4;
            return;
        }
        if (!(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
            retryWithAnotherAnvil("Anvil broke before the UI opened.");
            return;
        }
        if (--task.stageTicks <= 0) retryWithAnotherAnvil("Anvil UI did not open.");
    }

    private void loadRenameOnlyInput() {
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {
            retryCurrentAnvilStep("Anvil UI closed before rename-only input was loaded.", false);
            return;
        }

        int inputSlot = findRenameOnlyInputSlot();
        if (inputSlot < 0) {
            if (task.stageTicks-- > 0) return;
            failTask("Missing held item for rename-only flow.");
            return;
        }

        int handlerSlot = findHandlerSlotForInventoryIndex(handler, inputSlot);
        if (handlerSlot < 0) {
            failTask("Could not map rename-only item into the anvil screen.");
            return;
        }

        if (!handler.getSlot(0).getStack().isEmpty() || !handler.getSlot(1).getStack().isEmpty()) {
            if (task.stageTicks-- > 0) return;
            failTask("Anvil input slots were not empty.");
            return;
        }

        moveStackIntoAnvil(handler, handlerSlot, 0);
        applyDesiredFinalRename(handler);

        task.stage = Stage.WaitForResult;
        task.stageTicks = RESULT_WAIT_TICKS;
    }

    private void loadStepInputs() {
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {
            retryCurrentAnvilStep("Anvil UI closed before inputs were loaded.", false);
            return;
        }

        int leftInvSlot = findInventorySlotForNode(task.currentStep.left(), Set.of());
        if (leftInvSlot < 0) {
            if (task.stageTicks-- > 0) return;
            failTask("Missing left input for step: " + task.currentStep.left().label());
            return;
        }

        int rightInvSlot = findInventorySlotForNode(task.currentStep.right(), Set.of(leftInvSlot));
        if (rightInvSlot < 0) {
            if (task.stageTicks-- > 0) return;
            failTask("Missing right input for step: " + task.currentStep.right().label());
            return;
        }

        int leftHandlerSlot = findHandlerSlotForInventoryIndex(handler, leftInvSlot);
        int rightHandlerSlot = findHandlerSlotForInventoryIndex(handler, rightInvSlot);
        if (leftHandlerSlot < 0 || rightHandlerSlot < 0) {
            failTask("Could not map inventory slots into the anvil screen.");
            return;
        }

        if (!handler.getSlot(0).getStack().isEmpty() || !handler.getSlot(1).getStack().isEmpty()) {
            if (task.stageTicks-- > 0) return;
            failTask("Anvil input slots were not empty.");
            return;
        }

        moveStackIntoAnvil(handler, leftHandlerSlot, 0);
        moveStackIntoAnvil(handler, rightHandlerSlot, 1);

        applyDesiredFinalRename(handler);

        task.stage = Stage.WaitForResult;
        task.stageTicks = RESULT_WAIT_TICKS;
    }

    private void waitForResult() {
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {
            if (task.currentAnvilPos != null && !(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
                retryWithAnotherAnvil("Anvil broke before the result could be taken.");
                return;
            }
            retryCurrentAnvilStep("Anvil UI closed before the result was ready.", false);
            return;
        }

        applyDesiredFinalRename(handler);

        ItemStack result = handler.getSlot(2).getStack();
        if (!result.isEmpty()) {
            if (shouldApplyFinalRename() && !hasDesiredFinalName(result)) {
                task.renameRequestedForCurrentStep = false;
                applyDesiredFinalRename(handler);
                if (--task.stageTicks <= 0) retryCurrentAnvilStep("Final rename did not apply to the anvil result.", false);
                return;
            }

            int levelCost = handler.getLevelCost();
            if (levelCost > mc.player.experienceLevel) {
                failTask("Not enough levels for step cost " + levelCost + ".");
                return;
            }
            if (levelCost >= 40) {
                failTask("A merge step is too expensive (" + levelCost + ").");
                return;
            }

            task.resultTakeClicked = false;
            task.stageTicks = TAKE_RESULT_SYNC_TICKS;
            task.stage = Stage.TakeResult;
            return;
        }

        if (!(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
            retryWithAnotherAnvil("Anvil broke before the result appeared.");
            return;
        }

        if (--task.stageTicks <= 0) retryWithAnotherAnvil(task.renameOnly ? "Rename-only anvil result did not appear." : "Anvil result did not appear for step: " + task.currentStep.summary());
    }

    private void takeResult() {
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler handler)) {
            if (task.currentAnvilPos != null && !(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
                retryWithAnotherAnvil("Anvil broke before the result could be moved into inventory.");
                return;
            }
            retryCurrentAnvilStep("Anvil UI closed before the result could be taken.", false);
            return;
        }

        if (!task.resultTakeClicked) {
            mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
            task.resultTakeClicked = true;
            return;
        }

        int resultSlot = task.renameOnly ? findRenameOnlyResultSlot() : findInventorySlotForNode(task.currentStep.result(), Set.of());
        if (resultSlot >= 0) {
            if (task.renameOnly && resultSlot != task.originalSelectedSlot) {
                InvUtils.quickSwap().from(resultSlot).toHotbar(task.originalSelectedSlot);
                mc.player.getInventory().setSelectedSlot(task.originalSelectedSlot);
            }
            task.resultTakeClicked = false;
            task.stage = Stage.CloseAnvil;
            return;
        }

        if (--task.stageTicks <= 0) retryCurrentAnvilStep("Anvil result did not sync into inventory.", false);
    }

    private void closeAnvil() {
        if (mc.player.currentScreenHandler instanceof AnvilScreenHandler) mc.player.closeHandledScreen();

        if (task.currentAnvilPos != null && !(mc.world.getBlockState(task.currentAnvilPos).getBlock() instanceof AnvilBlock)) {
            task.failedAnvils.add(task.currentAnvilPos.toImmutable());
            debug("Anvil at " + task.currentAnvilPos + " is no longer usable; marking it as exhausted.");
        }

        if (task.renameOnly) {
            task.currentAnvilPos = null;
            task.stage = Stage.Completed;
            return;
        }

        task.stepIndex++;
        task.activeAnvilStepIndex = task.stepIndex;
        task.currentAnvilStepRetries = 0;
        task.currentStep = null;
        task.currentAnvilPos = null;
        task.stage = task.renameOnly ? Stage.PrepareRenameOnly : Stage.PrepareAnvilStep;
    }

    private void equipFinalResult() {
        EnchantingPlanSolver.Node finalNode = task.plan.result();
        int slot = findInventorySlotForNode(finalNode, Set.of());
        if (slot < 0) {
            if (task.stageTicks-- > 0) return;
            failTask("Final enchanted item was not found in inventory.");
            return;
        }

        ItemStack finalStack = mc.player.getInventory().getStack(slot);
        if (task.desiredFinalName != null && !hasDesiredFinalName(finalStack)) {
            startFinalRenameFallback(slot, finalStack);
            return;
        }

        if (slot != task.originalSelectedSlot) {
            InvUtils.quickSwap().from(slot).toHotbar(task.originalSelectedSlot);
        }
        mc.player.getInventory().setSelectedSlot(task.originalSelectedSlot);
        task.stage = Stage.Completed;
    }

    private void startFinalRenameFallback(int slot, ItemStack stack) {
        AutoEnchantTask previous = task;
        if (slot != previous.originalSelectedSlot) {
            InvUtils.quickSwap().from(slot).toHotbar(previous.originalSelectedSlot);
        }
        mc.player.getInventory().setSelectedSlot(previous.originalSelectedSlot);

        task = AutoEnchantTask.renameOnly(
            previous.originalSelectedSlot,
            previous.totalCost + 1,
            previous.pausedBaritone,
            previous.desiredFinalName,
            previous.heldItemId,
            previous.heldItemName,
            filterSupportedEnchants(getEnchantIdMap(stack)),
            getAnvilUseCount(stack)
        );
        task.baritoneResumedForModuleCommands = previous.baritoneResumedForModuleCommands;
        task.stage = Stage.PrepareRenameOnly;
        notifyWarn("Final item synced without the requested name. Running rename-only fallback.");
    }

    private void completeTask() {
        notifyInfo("Auto enchanting completed. Total plan cost: " + task.totalCost + " levels.");
        clearTask(true);
    }

    private void failTask(String reason) {
        notifyError("Auto enchanting failed: " + reason);
        debug("Failure reason: " + reason);
        if (task != null) task.failureReason = reason;
        clearTask(false);
    }

    private void retryWithAnotherAnvil(String reason) {
        retryCurrentAnvilStep(reason, true);
    }

    private void retryCurrentAnvilStep(String reason, boolean excludeCurrentAnvil) {
        if (task == null || task.currentAnvilPos == null) {
            failTask(reason);
            return;
        }

        if (task.currentAnvilStepRetries >= MAX_ANVIL_STEP_RETRIES) {
            failTask(reason + " Reached retry limit (" + MAX_ANVIL_STEP_RETRIES + ").");
            return;
        }

        task.currentAnvilStepRetries++;
        String stepLabel = task.renameOnly ? "rename-only" : (task.stepIndex + 1) + "/" + task.plan.steps().size();
        debug(reason + " Retrying anvil step " + stepLabel + " (" + task.currentAnvilStepRetries + "/" + MAX_ANVIL_STEP_RETRIES + ").");
        notifyWarn("Anvil step interrupted. Retrying (" + task.currentAnvilStepRetries + "/" + MAX_ANVIL_STEP_RETRIES + ").");
        if (excludeCurrentAnvil) task.failedAnvils.add(task.currentAnvilPos.toImmutable());
        if (mc.player != null && mc.currentScreen instanceof AnvilScreen) mc.player.closeHandledScreen();
        task.resultTakeClicked = false;
        task.renameRequestedForCurrentStep = false;
        task.currentAnvilPos = null;
        task.currentStep = null;
        task.pathingStarted = false;
        task.stage = Stage.PrepareAnvilStep;
    }

    private void clearTask(boolean success) {
        if (mc.player != null && mc.currentScreen instanceof HandledScreen<?>) mc.player.closeHandledScreen();
        if (task != null && task.pausedBaritone && !task.baritoneResumedForModuleCommands) executeBaritoneCommand("resume");
        restoreLocalBaritoneSettings();
        task = null;
        InteractionGate.release(InteractionGate.Owner.AnvilFlow);
    }

    private List<AggregatedBookType> collectRelevantBookTypes(Map<String, Integer> target, Map<String, Integer> baseEnchants) {
        Map<String, AggregatedBookBuilder> aggregated = new LinkedHashMap<>();
        for (EnchantStorageEntry storage : storages.get()) {
            if (!storage.enabled.get()) continue;
            for (StoredEnchantBook book : storage.getStoredBooks()) {
                if (book.getCount() <= 0 || book.getEnchants().isEmpty()) continue;
                Map<String, Integer> supportedEnchants = filterSupportedEnchants(book.getEnchants());
                if (supportedEnchants.isEmpty()) continue;
                if (!isRelevantBookForTarget(supportedEnchants, target, baseEnchants)) continue;
                String signature = buildEnchantSignature(supportedEnchants);
                AggregatedBookBuilder builder = aggregated.computeIfAbsent(signature, ignored -> new AggregatedBookBuilder(signature, supportedEnchants, book.getDisplayName()));
                builder.totalCount += book.getCount();
            }
        }

        PlayerInventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.ENCHANTED_BOOK)) continue;

            Map<String, Integer> supportedEnchants = getSupportedEnchantIdMap(stack);
            if (supportedEnchants.isEmpty()) continue;
            if (!isRelevantBookForTarget(supportedEnchants, target, baseEnchants)) continue;

            String signature = buildEnchantSignature(supportedEnchants);
            AggregatedBookBuilder builder = aggregated.computeIfAbsent(signature, ignored -> new AggregatedBookBuilder(signature, supportedEnchants, buildEnchantDisplay(supportedEnchants)));
            builder.totalCount += stack.getCount();
        }

        return aggregated.values().stream()
            .map(builder -> new AggregatedBookType(builder.signature, builder.enchants, builder.displayName, builder.totalCount))
            .toList();
    }

    private boolean isRelevantBookForTarget(Map<String, Integer> enchants, Map<String, Integer> target, Map<String, Integer> baseEnchants) {
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            int targetLevel = target.getOrDefault(entry.getKey(), 0);
            if (targetLevel <= 0) continue;
            int currentLevel = baseEnchants.getOrDefault(entry.getKey(), 0);
            int requiredLevel = currentLevel > 0 && currentLevel == targetLevel - 1 ? currentLevel : targetLevel;
            if (entry.getValue() >= requiredLevel) return true;
        }
        return false;
    }

    private Throwable unwrapPlanningThrowable(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void ensureBaritoneReadyForModuleCommands() {
        if (task == null || !task.pausedBaritone || task.baritoneResumedForModuleCommands) return;
        executeBaritoneCommand("resume");
        task.baritoneResumedForModuleCommands = true;
        debug("Resumed Baritone so Auto Enchanting can issue its own movement commands.");
    }

    private void applyLocalBaritoneSettings() {
        if (!localAllowBreakFalse.get()) return;

        Boolean current = getBaritoneBooleanSetting("allowBreak");
        if (current == null) {
            debug("Could not read Baritone setting 'allowBreak'.");
            return;
        }

        restoreAllowBreakValue = current;
        if (!current) return;

        if (setBaritoneBooleanSetting("allowBreak", false)) {
            debug("Temporarily set Baritone allowBreak=false for Auto Enchanting.");
        }
    }

    private void restoreLocalBaritoneSettings() {
        if (restoreAllowBreakValue == null) return;
        boolean original = restoreAllowBreakValue;
        restoreAllowBreakValue = null;
        if (setBaritoneBooleanSetting("allowBreak", original)) {
            debug("Restored Baritone allowBreak=" + original + " after Auto Enchanting.");
        }
    }

    private Boolean getBaritoneBooleanSetting(String name) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);
            if (settings == null) return null;

            Object setting = settings.getClass().getField(name).get(settings);
            if (setting == null) return null;

            Object value = setting.getClass().getField("value").get(setting);
            return value instanceof Boolean booleanValue ? booleanValue : null;
        } catch (Throwable t) {
            debug("Failed to read Baritone setting '" + name + "': " + t.getClass().getSimpleName());
            return null;
        }
    }

    private boolean setBaritoneBooleanSetting(String name, boolean value) {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);
            if (settings == null) return false;

            Object setting = settings.getClass().getField(name).get(settings);
            if (setting == null) return false;

            setting.getClass().getField("value").set(setting, value);
            return true;
        } catch (Throwable t) {
            debug("Failed to set Baritone setting '" + name + "' -> " + value + ": " + t.getClass().getSimpleName());
            return false;
        }
    }

    private List<BookRequirement> collectBookRequirements(EnchantingPlanSolver.Plan plan, List<PlanningBookSource> sources) {
        Map<String, PlanningBookSource> byLabel = sources.stream().collect(Collectors.toMap(source -> source.node().label(), source -> source));
        Set<String> produced = plan.steps().stream().map(step -> step.result().label()).collect(Collectors.toSet());
        List<BookRequirement> requirements = new ArrayList<>();

        for (EnchantingPlanSolver.MergeStep step : plan.steps()) {
            collectLeafRequirement(step.left(), produced, byLabel, requirements);
            collectLeafRequirement(step.right(), produced, byLabel, requirements);
        }

        return requirements;
    }

    private void collectLeafRequirement(EnchantingPlanSolver.Node node, Set<String> produced, Map<String, PlanningBookSource> byLabel, List<BookRequirement> requirements) {
        if (!node.isBook() || produced.contains(node.label())) return;
        PlanningBookSource source = byLabel.get(node.label());
        if (source != null) requirements.add(new BookRequirement(source.signature(), source.displayName(), source.enchants()));
    }

    private int countSatisfiedRequirements(List<BookRequirement> requirements) {
        List<BookRequirement> inventorySatisfied = matchRequirementsWithInventory(requirements);
        for (BookRequirement requirement : inventorySatisfied) requirement.satisfied = true;
        return (int) requirements.stream().filter(requirement -> requirement.satisfied).count();
    }

    private BlockPos findNextStorageForRequirements() {
        return storages.get().stream()
            .filter(entry -> entry.enabled.get())
            .filter(entry -> entry.getStoredBooks().stream().anyMatch(book -> task.requirements.stream().anyMatch(req -> !req.satisfied && canStoredBookSatisfyRequirement(book, req))))
            .map(entry -> entry.pos.get().toImmutable())
            .min(Comparator.comparingDouble(this::distanceToPos))
            .orElse(null);
    }

    private int findMatchingBookSlot(ScreenHandler handler, int containerSlots, BookRequirement requirement, Set<Integer> excludedSlots) {
        int bestSlot = -1;
        Map<String, Integer> bestEnchants = null;

        for (int i = 0; i < containerSlots; i++) {
            if (excludedSlots.contains(i)) continue;
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !stack.isOf(Items.ENCHANTED_BOOK)) continue;
            Map<String, Integer> enchants = getSupportedEnchantIdMap(stack);
            if (!canSubstitute(enchants, requirement.enchants)) continue;

            if (bestSlot < 0 || compareBookCandidates(enchants, bestEnchants, requirement.enchants) < 0) {
                bestSlot = i;
                bestEnchants = enchants;
            }
        }
        return bestSlot;
    }

    private void decrementCachedStorageBooks(BlockPos pos, Map<String, Integer> movedBySignature) {
        if (pos == null || movedBySignature.isEmpty()) return;

        List<EnchantStorageEntry> entries = new ArrayList<>(storages.get());
        EnchantStorageEntry entry = entries.stream().filter(candidate -> candidate.pos.get().equals(pos)).findFirst().orElse(null);
        if (entry == null) return;

        List<StoredEnchantBook> updated = new ArrayList<>();
        for (StoredEnchantBook book : entry.getStoredBooks()) {
            int moved = movedBySignature.getOrDefault(book.getSignature(), 0);
            int remaining = book.getCount() - moved;
            if (remaining > 0) updated.add(new StoredEnchantBook(book.getEnchants(), book.getDisplayName(), remaining));
        }

        entry.updateState(entry.getLastTitle(), updated);
        storages.set(entries);
    }

    private int findInventorySlotForNode(EnchantingPlanSolver.Node node, Collection<Integer> excluded) {
        Set<Integer> blocked = new HashSet<>(excluded);
        PlayerInventory inventory = mc.player.getInventory();

        if (!node.isBook() && task != null && isOriginalBaseNode(node) && !blocked.contains(task.originalSelectedSlot)) {
            ItemStack preferred = inventory.getStack(task.originalSelectedSlot);
            if (!preferred.isEmpty() && stackMatchesNode(preferred, node)) return task.originalSelectedSlot;
        }

        for (int i = 0; i < 36; i++) {
            if (blocked.contains(i)) continue;
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (node.isBook() != stack.isOf(Items.ENCHANTED_BOOK)) continue;
            if (!stackMatchesNode(stack, node)) continue;
            if (getAnvilUseCount(stack) == node.anvilUseCount()) return i;
        }

        for (int i = 0; i < 36; i++) {
            if (blocked.contains(i)) continue;
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (node.isBook() != stack.isOf(Items.ENCHANTED_BOOK)) continue;
            if (!stackMatchesNode(stack, node)) continue;
            return i;
        }

        return -1;
    }

    private boolean stackMatchesNode(ItemStack stack, EnchantingPlanSolver.Node node) {
        if (!node.isBook() && task != null && task.heldItemId != null && !task.heldItemId.equals(getItemId(stack))) return false;
        Map<String, Integer> actual = getSupportedEnchantIdMap(stack);
        if (!actual.keySet().equals(node.enchants().keySet())) return false;
        return canSubstitute(actual, node.enchants());
    }

    private boolean isOriginalBaseNode(EnchantingPlanSolver.Node node) {
        return task != null
            && !node.isBook()
            && node.anvilUseCount() == task.initialItemAnvilUseCount
            && node.enchants().equals(task.initialItemEnchants);
    }

    private int findHandlerSlotForInventoryIndex(ScreenHandler handler, int inventoryIndex) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) return i;
        }
        return -1;
    }

    private int findTaskItemSlot(Map<String, Integer> enchants, int anvilUseCount) {
        PlayerInventory inventory = mc.player.getInventory();

        if (task != null && task.originalSelectedSlot >= 0 && task.originalSelectedSlot < 36) {
            ItemStack preferred = inventory.getStack(task.originalSelectedSlot);
            if (matchesTaskItem(preferred, enchants) && getAnvilUseCount(preferred) == anvilUseCount) return task.originalSelectedSlot;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (matchesTaskItem(stack, enchants) && getAnvilUseCount(stack) == anvilUseCount) return i;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStack(i);
            if (matchesTaskItem(stack, enchants)) return i;
        }

        return -1;
    }

    private int findTaskEnchantedItemSlot() {
        if (task == null || task.tableEnchantResultSignature == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();
        if (task.originalSelectedSlot >= 0 && task.originalSelectedSlot < 36) {
            ItemStack preferred = inventory.getStack(task.originalSelectedSlot);
            if (matchesTaskEnchantedItem(preferred)) return task.originalSelectedSlot;
        }

        for (int i = 0; i < 36; i++) {
            if (matchesTaskEnchantedItem(inventory.getStack(i))) return i;
        }

        return -1;
    }

    private int findRenameOnlyInputSlot() {
        if (task == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();
        if (task.originalSelectedSlot >= 0 && task.originalSelectedSlot < 36) {
            ItemStack preferred = inventory.getStack(task.originalSelectedSlot);
            if (matchesRenameOnlyInput(preferred)) return task.originalSelectedSlot;
        }

        for (int i = 0; i < 36; i++) {
            if (matchesRenameOnlyInput(inventory.getStack(i))) return i;
        }

        return -1;
    }

    private int findRenameOnlyResultSlot() {
        if (task == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();
        if (task.originalSelectedSlot >= 0 && task.originalSelectedSlot < 36) {
            ItemStack preferred = inventory.getStack(task.originalSelectedSlot);
            if (matchesRenameOnlyResult(preferred)) return task.originalSelectedSlot;
        }

        for (int i = 0; i < 36; i++) {
            if (matchesRenameOnlyResult(inventory.getStack(i))) return i;
        }

        return -1;
    }

    private boolean matchesTaskItem(ItemStack stack, Map<String, Integer> enchants) {
        return task != null
            && !stack.isEmpty()
            && !stack.isOf(Items.ENCHANTED_BOOK)
            && task.heldItemId.equals(getItemId(stack))
            && getSupportedEnchantIdMap(stack).equals(enchants);
    }

    private boolean matchesTaskEnchantedItem(ItemStack stack) {
        return task != null
            && !stack.isEmpty()
            && task.heldItemId.equals(getItemId(stack))
            && task.tableEnchantResultSignature.equals(buildEnchantSignature(getEnchantIdMap(stack)));
    }

    private boolean matchesRenameOnlyInput(ItemStack stack) {
        return task != null
            && !stack.isEmpty()
            && task.heldItemId.equals(getItemId(stack))
            && getSupportedEnchantIdMap(stack).equals(task.initialItemEnchants)
            && !hasDesiredFinalName(stack);
    }

    private boolean matchesRenameOnlyResult(ItemStack stack) {
        return task != null
            && !stack.isEmpty()
            && task.heldItemId.equals(getItemId(stack))
            && getSupportedEnchantIdMap(stack).equals(task.initialItemEnchants)
            && hasDesiredFinalName(stack);
    }

    private void moveStackIntoAnvil(AnvilScreenHandler handler, int sourceSlot, int targetSlot) {
        mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, mc.player);
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        }
    }

    private boolean inventoryHasBookRequirement(BookRequirement requirement) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.ENCHANTED_BOOK)) continue;
            if (canSubstitute(getSupportedEnchantIdMap(stack), requirement.enchants)) return true;
        }
        return false;
    }

    private String describeMissingRequirements(List<BookRequirement> requirements) {
        Set<BookRequirement> inventorySatisfied = new HashSet<>(matchRequirementsWithInventory(requirements));
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (BookRequirement requirement : requirements) {
            if (requirement.satisfied || inventorySatisfied.contains(requirement)) continue;
            missing.merge(requirement.displayName, 1, Integer::sum);
        }
        if (missing.isEmpty()) return "unknown";
        return missing.entrySet().stream()
            .map(entry -> entry.getValue() + "x " + entry.getKey())
            .collect(Collectors.joining(", "));
    }

    private List<BookRequirement> matchRequirementsWithInventory(List<BookRequirement> requirements) {
        Map<String, InventoryBookCount> available = collectInventoryBookCounts();
        List<BookRequirement> matched = new ArrayList<>();

        for (BookRequirement requirement : requirements) {
            if (requirement.satisfied) {
                matched.add(requirement);
                continue;
            }

            InventoryBookCount best = available.values().stream()
                .filter(entry -> entry.count > 0)
                .filter(entry -> canSubstitute(entry.enchants, requirement.enchants))
                .sorted((left, right) -> compareBookCandidates(left.enchants, right.enchants, requirement.enchants))
                .findFirst()
                .orElse(null);

            if (best == null) continue;
            best.count--;
            matched.add(requirement);
        }

        return matched;
    }

    private Map<String, InventoryBookCount> collectInventoryBookCounts() {
        Map<String, InventoryBookCount> available = new LinkedHashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.ENCHANTED_BOOK)) continue;

            Map<String, Integer> enchants = getSupportedEnchantIdMap(stack);
            if (enchants.isEmpty()) continue;

            String signature = buildEnchantSignature(enchants);
            InventoryBookCount entry = available.computeIfAbsent(signature, ignored -> new InventoryBookCount(enchants));
            entry.count += stack.getCount();
        }
        return available;
    }

    private boolean canStoredBookSatisfyRequirement(StoredEnchantBook book, BookRequirement requirement) {
        return canSubstitute(filterSupportedEnchants(book.getEnchants()), requirement.enchants);
    }

    private int compareBookCandidates(Map<String, Integer> left, Map<String, Integer> right, Map<String, Integer> required) {
        if (right == null) return -1;

        int extraEnchantCompare = Integer.compare(right.size(), left.size());
        if (extraEnchantCompare != 0) return extraEnchantCompare;

        int wasteLeft = Math.max(totalEnchantLevel(left) - totalEnchantLevel(required), 0);
        int wasteRight = Math.max(totalEnchantLevel(right) - totalEnchantLevel(required), 0);
        int wasteCompare = Integer.compare(wasteRight, wasteLeft);
        if (wasteCompare != 0) return wasteCompare;

        int levelCompare = Integer.compare(totalEnchantLevel(left), totalEnchantLevel(right));
        if (levelCompare != 0) return levelCompare;

        return buildEnchantSignature(left).compareTo(buildEnchantSignature(right));
    }

    private String describeMissingTargetBooks(Map<String, Integer> target, Map<String, Integer> baseEnchants, List<AggregatedBookType> availableTypes) {
        Map<String, Map<Integer, Integer>> availableLevels = new HashMap<>();
        for (AggregatedBookType source : availableTypes) {
            for (Map.Entry<String, Integer> enchant : source.enchants().entrySet()) {
                availableLevels
                    .computeIfAbsent(enchant.getKey(), ignored -> new TreeMap<>())
                    .merge(enchant.getValue(), source.totalCount(), Integer::sum);
            }
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            String enchant = entry.getKey();
            int targetLevel = entry.getValue();
            int currentLevel = baseEnchants.getOrDefault(enchant, 0);
            if (currentLevel >= targetLevel) continue;

            String name = formatEnchant(enchant, targetLevel);
            Map<Integer, Integer> levels = availableLevels.get(enchant);
            if (levels == null || levels.isEmpty()) {
                missing.add(name + " (no book found)");
                continue;
            }

            int bestLevel = levels.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            int bestCount = levels.getOrDefault(bestLevel, 0);
            if (bestLevel >= targetLevel) continue;
            if (bestLevel == targetLevel - 1) {
                int needed = currentLevel >= bestLevel ? 1 : 2;
                if (bestCount < needed) {
                    missing.add(name + " (need " + needed + "x " + formatEnchant(enchant, bestLevel) + " or 1x " + name + ", have " + bestCount + "x " + formatEnchant(enchant, bestLevel) + ")");
                }
                continue;
            }

            missing.add(name + " (best cached book: " + formatEnchant(enchant, bestLevel) + " x" + bestCount + ")");
        }

        if (missing.isEmpty()) return "required book combination is not available; rescan the relevant storages or add more copies of the listed target books";
        return String.join(", ", missing);
    }

    private String formatEnchant(String enchant, int level) {
        return capitalizeWords(enchant.replace('_', ' ')) + " " + toRoman(level);
    }

    private Map<String, Integer> buildTargetEnchants(ItemStack stack, Map<String, Integer> baseEnchants) {
        Item item = stack.getItem();
        Map<String, Integer> override = buildCustomTarget(stack, baseEnchants);
        if (override != null) return override;

        Map<String, Integer> target = new LinkedHashMap<>();

        if (stack.isIn(ItemTags.SWORDS)) {
            target.put(selectWeaponDamageEnchant(baseEnchants, "sharpness"), 5);
            target.put("unbreaking", 3);
            target.put("mending", 1);
            target.put("looting", 3);
            target.put("sweeping_edge", 3);
            if (includeFireAspect.get()) target.put("fire_aspect", 2);
            if (includeKnockback.get()) target.put("knockback", 2);
            return target;
        }

        if (stack.isIn(ItemTags.PICKAXES) || item instanceof ShovelItem || item instanceof HoeItem) {
            target.put("efficiency", 5);
            String toolLootEnchant = selectToolLootEnchant(baseEnchants);
            target.put(toolLootEnchant, toolLootEnchant.equals("fortune") ? 3 : 1);
            target.put("unbreaking", 3);
            target.put("mending", 1);
            return target;
        }

        if (item instanceof AxeItem) {
            if (includeEfficiencyOnAxes.get()) target.put("efficiency", 5);
            String toolLootEnchant = selectToolLootEnchant(baseEnchants);
            target.put(toolLootEnchant, toolLootEnchant.equals("fortune") ? 3 : 1);
            target.put("unbreaking", 3);
            target.put("mending", 1);
            if (includeSharpnessOnAxes.get()) target.put(selectWeaponDamageEnchant(baseEnchants, "sharpness"), 5);
            return target;
        }

        if (stack.isOf(Items.FISHING_ROD)) {
                target.put("luck_of_the_sea", 3);
                target.put("lure", 3);
                target.put("unbreaking", 3);
                target.put("mending", 1);
                return target;
        }

        if (stack.isIn(ItemTags.HEAD_ARMOR)) {
                target.put(selectArmorProtectionEnchant(baseEnchants, "protection"), 4);
                target.put("respiration", 3);
                target.put("aqua_affinity", 1);
                target.put("unbreaking", 3);
                target.put("mending", 1);
                if (includeThorns.get()) target.put("thorns", 3);
                return target;
        }
        if (stack.isIn(ItemTags.CHEST_ARMOR)) {
                target.put(selectArmorProtectionEnchant(baseEnchants, "protection"), 4);
                target.put("unbreaking", 3);
                target.put("mending", 1);
                if (includeThorns.get()) target.put("thorns", 3);
                return target;
        }
        if (stack.isIn(ItemTags.LEG_ARMOR)) {
                target.put(selectLeggingsProtectionEnchant(baseEnchants), 4);
                target.put("unbreaking", 3);
                target.put("mending", 1);
                if (includeThorns.get()) target.put("thorns", 3);
                if (includeSwiftSneak.get()) target.put("swift_sneak", 3);
                return target;
        }
        if (stack.isIn(ItemTags.FOOT_ARMOR)) {
                target.put(selectArmorProtectionEnchant(baseEnchants, "protection"), 4);
                target.put("feather_falling", 4);
                target.put(preferDepthStrider.get() ? "depth_strider" : "frost_walker", preferDepthStrider.get() ? 3 : 2);
                target.put("unbreaking", 3);
                target.put("mending", 1);
                if (includeThorns.get()) target.put("thorns", 3);
                if (includeSoulSpeed.get()) target.put("soul_speed", 3);
                return target;
        }

        return target;
    }

    private Map<String, Integer> buildCustomTarget(ItemStack stack, Map<String, Integer> baseEnchants) {
        Item item = stack.getItem();
        if (stack.isIn(ItemTags.SWORDS) && customSwordTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "sharpness", swordSharpness.get());
            putTarget(target, "unbreaking", swordUnbreaking.get());
            putTarget(target, "mending", swordMending.get());
            putTarget(target, "looting", swordLooting.get());
            putTarget(target, "sweeping_edge", swordSweepingEdge.get());
            putTarget(target, "fire_aspect", swordFireAspect.get());
            putTarget(target, "knockback", swordKnockback.get());
            return target;
        }

        if (stack.isIn(ItemTags.PICKAXES) && customPickaxeTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "efficiency", pickaxeEfficiency.get());
            putTarget(target, "fortune", pickaxeFortune.get());
            putTarget(target, "silk_touch", pickaxeSilkTouch.get());
            putTarget(target, "unbreaking", pickaxeUnbreaking.get());
            putTarget(target, "mending", pickaxeMending.get());
            keepOnlyOneConflict(target, baseEnchants, "Pickaxe", "fortune", "silk_touch");
            return target;
        }

        if (item instanceof AxeItem && customAxeTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "efficiency", axeEfficiency.get());
            putTarget(target, "fortune", axeFortune.get());
            putTarget(target, "silk_touch", axeSilkTouch.get());
            putTarget(target, "unbreaking", axeUnbreaking.get());
            putTarget(target, "mending", axeMending.get());
            putTarget(target, "sharpness", axeSharpness.get());
            keepOnlyOneConflict(target, baseEnchants, "Axe", "fortune", "silk_touch");
            return target;
        }

        if (item instanceof ShovelItem && customShovelTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "efficiency", shovelEfficiency.get());
            putTarget(target, "fortune", shovelFortune.get());
            putTarget(target, "silk_touch", shovelSilkTouch.get());
            putTarget(target, "unbreaking", shovelUnbreaking.get());
            putTarget(target, "mending", shovelMending.get());
            keepOnlyOneConflict(target, baseEnchants, "Shovel", "fortune", "silk_touch");
            return target;
        }

        if (item instanceof HoeItem && customHoeTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "efficiency", hoeEfficiency.get());
            putTarget(target, "fortune", hoeFortune.get());
            putTarget(target, "silk_touch", hoeSilkTouch.get());
            putTarget(target, "unbreaking", hoeUnbreaking.get());
            putTarget(target, "mending", hoeMending.get());
            keepOnlyOneConflict(target, baseEnchants, "Hoe", "fortune", "silk_touch");
            return target;
        }

        if (stack.isOf(Items.FISHING_ROD) && customFishingRodTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "luck_of_the_sea", fishingRodLuckOfTheSea.get());
            putTarget(target, "lure", fishingRodLure.get());
            putTarget(target, "unbreaking", fishingRodUnbreaking.get());
            putTarget(target, "mending", fishingRodMending.get());
            return target;
        }

        if (stack.isIn(ItemTags.HEAD_ARMOR) && customHelmetTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "protection", helmetProtection.get());
            putTarget(target, "fire_protection", helmetFireProtection.get());
            putTarget(target, "blast_protection", helmetBlastProtection.get());
            putTarget(target, "projectile_protection", helmetProjectileProtection.get());
            putTarget(target, "respiration", helmetRespiration.get());
            putTarget(target, "aqua_affinity", helmetAquaAffinity.get());
            putTarget(target, "unbreaking", helmetUnbreaking.get());
            putTarget(target, "mending", helmetMending.get());
            putTarget(target, "thorns", helmetThorns.get());
            keepOnlyOneConflict(target, baseEnchants, "Helmet", "protection", "fire_protection", "blast_protection", "projectile_protection");
            return target;
        }

        if (stack.isIn(ItemTags.CHEST_ARMOR) && customChestplateTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "protection", chestplateProtection.get());
            putTarget(target, "fire_protection", chestplateFireProtection.get());
            putTarget(target, "blast_protection", chestplateBlastProtection.get());
            putTarget(target, "projectile_protection", chestplateProjectileProtection.get());
            putTarget(target, "unbreaking", chestplateUnbreaking.get());
            putTarget(target, "mending", chestplateMending.get());
            putTarget(target, "thorns", chestplateThorns.get());
            keepOnlyOneConflict(target, baseEnchants, "Chestplate", "protection", "fire_protection", "blast_protection", "projectile_protection");
            return target;
        }

        if (stack.isIn(ItemTags.LEG_ARMOR) && customLeggingsTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "protection", leggingsProtection.get());
            putTarget(target, "fire_protection", leggingsFireProtection.get());
            putTarget(target, "blast_protection", leggingsBlastProtection.get());
            putTarget(target, "projectile_protection", leggingsProjectileProtection.get());
            putTarget(target, "unbreaking", leggingsUnbreaking.get());
            putTarget(target, "mending", leggingsMending.get());
            putTarget(target, "thorns", leggingsThorns.get());
            putTarget(target, "swift_sneak", leggingsSwiftSneak.get());
            keepOnlyOneConflict(target, baseEnchants, "Leggings", "protection", "fire_protection", "blast_protection", "projectile_protection");
            return target;
        }

        if (stack.isIn(ItemTags.FOOT_ARMOR) && customBootsTarget.get()) {
            Map<String, Integer> target = new LinkedHashMap<>();
            putTarget(target, "protection", bootsProtection.get());
            putTarget(target, "fire_protection", bootsFireProtection.get());
            putTarget(target, "blast_protection", bootsBlastProtection.get());
            putTarget(target, "projectile_protection", bootsProjectileProtection.get());
            putTarget(target, "feather_falling", bootsFeatherFalling.get());
            putTarget(target, "depth_strider", bootsDepthStrider.get());
            putTarget(target, "frost_walker", bootsFrostWalker.get());
            putTarget(target, "soul_speed", bootsSoulSpeed.get());
            putTarget(target, "unbreaking", bootsUnbreaking.get());
            putTarget(target, "mending", bootsMending.get());
            putTarget(target, "thorns", bootsThorns.get());
            keepOnlyOneConflict(target, baseEnchants, "Boots", "protection", "fire_protection", "blast_protection", "projectile_protection");
            keepOnlyOneConflict(target, baseEnchants, "Boots", "depth_strider", "frost_walker");
            return target;
        }

        return null;
    }

    private void putTarget(Map<String, Integer> target, String enchant, int level) {
        if (level > 0) target.put(enchant, level);
    }

    private void keepOnlyOneConflict(Map<String, Integer> target, Map<String, Integer> baseEnchants, String context, String... enchants) {
        List<String> enabled = new ArrayList<>();
        for (String enchant : enchants) {
            if (target.getOrDefault(enchant, 0) > 0) enabled.add(enchant);
        }
        if (enabled.size() <= 1) return;

        String keep = null;
        for (String enchant : enabled) {
            if (baseEnchants.containsKey(enchant)) {
                keep = enchant;
                break;
            }
        }
        if (keep == null) keep = enabled.get(0);

        for (String enchant : enabled) {
            if (!enchant.equals(keep)) target.remove(enchant);
        }

        notifyWarn(context + " target has conflicting enchants enabled. Keeping " + formatEnchant(keep, target.getOrDefault(keep, 1)) + ".");
    }

    private Setting<Boolean> toggleTargetGroup(SettingGroup group, String name, String description) {
        return group.add(new BoolSetting.Builder()
            .name(name)
            .description(description)
            .defaultValue(false)
            .build()
        );
    }

    private Setting<Integer> targetLevel(SettingGroup group, Setting<Boolean> visibleFlag, String name, int maxLevel) {
        return group.add(new IntSetting.Builder()
            .name(name)
            .description("Target level for this enchant. 0 disables it for this item target.")
            .defaultValue(0)
            .min(0)
            .max(maxLevel)
            .sliderRange(0, maxLevel)
            .visible(visibleFlag::get)
            .build()
        );
    }

    private String selectToolLootEnchant(Map<String, Integer> baseEnchants) {
        if (baseEnchants.containsKey("silk_touch")) return "silk_touch";
        if (baseEnchants.containsKey("fortune")) return "fortune";
        return preferFortuneOnTools.get() ? "fortune" : "silk_touch";
    }

    private String selectWeaponDamageEnchant(Map<String, Integer> baseEnchants, String fallback) {
        if (baseEnchants.containsKey("sharpness")) return "sharpness";
        if (baseEnchants.containsKey("smite")) return "smite";
        if (baseEnchants.containsKey("bane_of_arthropods")) return "bane_of_arthropods";
        return fallback;
    }

    private String selectArmorProtectionEnchant(Map<String, Integer> baseEnchants, String fallback) {
        if (baseEnchants.containsKey("protection")) return "protection";
        if (baseEnchants.containsKey("fire_protection")) return "fire_protection";
        if (baseEnchants.containsKey("blast_protection")) return "blast_protection";
        if (baseEnchants.containsKey("projectile_protection")) return "projectile_protection";
        return fallback;
    }

    private String selectLeggingsProtectionEnchant(Map<String, Integer> baseEnchants) {
        return selectArmorProtectionEnchant(baseEnchants, preferBlastProtectionOnLeggings.get() ? "blast_protection" : "protection");
    }

    private boolean matchesTarget(Map<String, Integer> actual, Map<String, Integer> target) {
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            if (actual.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }

    private String buildDesiredFinalName(ItemStack held) {
        if (!renameFinalItem.get()) return null;
        String template = finalItemNameTemplate.get();
        if (template == null) return null;
        template = template.trim();
        if (template.isEmpty()) return null;

        String itemName = held.getItem().getName().getString();
        String shortItemName = buildShortItemName(itemName);
        String resolved = template
            .replace("<item>", itemName)
            .replace("<shortitem>", shortItemName)
            .trim();
        return resolved.isEmpty() ? null : resolved;
    }

    private String buildShortItemName(String itemName) {
        if (itemName == null || itemName.isBlank()) return "Item";

        String[] suffixes = {
            "Sword", "Pickaxe", "Axe", "Shovel", "Hoe",
            "Helmet", "Chestplate", "Leggings", "Boots",
            "Bow", "Crossbow", "Trident", "Shield"
        };

        for (String suffix : suffixes) {
            if (itemName.endsWith(" " + suffix)) return suffix;
            if (itemName.equalsIgnoreCase(suffix)) return suffix;
        }

        return itemName;
    }

    private String capitalizeWords(String value) {
        String[] parts = value.split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isBlank()) continue;
            out.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return String.join(" ", out);
    }

    private String toRoman(int value) {
        return switch (value) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }

    private boolean shouldApplyFinalRename() {
        return task != null
            && task.desiredFinalName != null
            && (task.renameOnly || (task.currentStep != null && task.stepIndex == task.plan.steps().size() - 1));
    }

    private void applyDesiredFinalRename(AnvilScreenHandler handler) {
        if (!shouldApplyFinalRename() || mc.getNetworkHandler() == null) return;

        String rename = task.desiredFinalName;
        handler.setNewItemName(rename);
        mc.getNetworkHandler().sendPacket(new RenameItemC2SPacket(rename));
        if (!task.renameRequestedForCurrentStep) {
            task.renameRequestedForCurrentStep = true;
            debug("Applying final rename: '" + rename + "'.");
        }
    }

    private boolean hasDesiredFinalName(ItemStack stack) {
        return task != null
            && task.desiredFinalName != null
            && task.desiredFinalName.equals(stack.getName().getString());
    }

    private int getContainerSlotCount(ScreenHandler handler) {
        return Math.max(handler.slots.size() - 36, 0);
    }

    private List<StoredEnchantBook> scanStoredBooks(ScreenHandler handler, int containerSlots) {
        Map<String, MutableBookEntry> aggregated = new LinkedHashMap<>();
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !stack.isOf(Items.ENCHANTED_BOOK)) continue;

            Map<String, Integer> enchants = getEnchantIdMap(stack);
            if (enchants.isEmpty()) continue;
            String signature = buildEnchantSignature(enchants);
            String display = buildEnchantDisplay(enchants);
            aggregated.computeIfAbsent(signature, key -> new MutableBookEntry(enchants, display)).count += stack.getCount();
        }

        return aggregated.values().stream()
            .map(entry -> new StoredEnchantBook(entry.enchants, entry.displayName, entry.count))
            .sorted(Comparator.comparing(StoredEnchantBook::getDisplayName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private Map<String, Integer> getEnchantIdMap(ItemStack stack) {
        ItemEnchantmentsComponent component = stack.isOf(Items.ENCHANTED_BOOK)
            ? stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT)
            : stack.getEnchantments();

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

    private Map<String, Integer> getSupportedEnchantIdMap(ItemStack stack) {
        return filterSupportedEnchants(getEnchantIdMap(stack));
    }

    private String buildEnchantDisplay(ItemStack stack) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : getEnchantIdMap(stack).entrySet()) names.add(formatEnchant(entry.getKey(), entry.getValue()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    private String buildEnchantDisplay(Map<String, Integer> enchants) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) names.add(formatEnchant(entry.getKey(), entry.getValue()));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    private String buildEnchantSignature(Map<String, Integer> enchants) {
        return enchants.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(";"));
    }

    private Map<String, Integer> filterSupportedEnchants(Map<String, Integer> enchants) {
        Map<String, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            if (EnchantingPlanSolver.isSupportedEnchant(entry.getKey())) filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private void updateStorageEntry(BlockPos pos, String title, List<StoredEnchantBook> books) {
        List<EnchantStorageEntry> entries = new ArrayList<>(storages.get());
        EnchantStorageEntry entry = entries.stream().filter(candidate -> candidate.pos.get().equals(pos)).findFirst().orElse(null);
        if (entry == null) {
            if (!scanOpenedContainers.get()) return;
            entry = new EnchantStorageEntry();
            entry.setPos(pos);
            entries.add(entry);
            debug("Registered new storage at " + pos + ".");
        }
        entry.updateState(title, books);
        storages.set(entries);
    }

    private boolean isKnownStorage(BlockPos pos) {
        return storages.get().stream().anyMatch(entry -> entry.pos.get().equals(pos));
    }

    private String getStorageTitle(BlockPos pos) {
        if (mc.currentScreen instanceof HandledScreen<?> handled) {
            String screenTitle = handled.getTitle().getString();
            if (!screenTitle.isBlank() && !screenTitle.equalsIgnoreCase("Chest") && !screenTitle.equalsIgnoreCase("Shulker Box") && !screenTitle.equalsIgnoreCase("Purple Shulker Box")) {
                return screenTitle;
            }
        }
        BlockEntity blockEntity = mc.world.getBlockEntity(pos);
        if (blockEntity instanceof LockableContainerBlockEntity lockable) {
            Text customName = lockable.getCustomName();
            if (customName != null && !customName.getString().isBlank()) return customName.getString();
        }
        if (mc.currentScreen instanceof HandledScreen<?> handled) {
            String screenTitle = handled.getTitle().getString();
            if (!screenTitle.isBlank()) return screenTitle;
        }
        return mc.world.getBlockState(pos).getBlock().getName().getString();
    }

    private BlockPos resolveOpenedStoragePos() {
        if (task != null && task.currentStoragePos != null && isStorageTaskStage(task.stage)) {
            BlockState expectedState = mc.world.getBlockState(task.currentStoragePos);
            if (isSupportedStorage(expectedState.getBlock()) && isWithinInteractRange(task.currentStoragePos, detectRange.get())) {
                return task.currentStoragePos.toImmutable();
            }
        }

        if (lastTargetedStoragePos != null) {
            BlockState targetedState = mc.world.getBlockState(lastTargetedStoragePos);
            if (isSupportedStorage(targetedState.getBlock()) && isWithinInteractRange(lastTargetedStoragePos, detectRange.get())) {
                return lastTargetedStoragePos.toImmutable();
            }
        }

        BlockPos knownStorage = findNearestKnownStorage();
        if (knownStorage != null) return knownStorage;

        if (!scanOpenedContainers.get()) return null;

        Vec3d eyePos = mc.player.getEyePos();
        double maxDistanceSq = detectRange.get() * detectRange.get();
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;
        int radius = Math.max(2, (int) Math.ceil(detectRange.get()));

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (!isSupportedStorage(state.getBlock())) continue;
                    double distSq = eyePos.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distSq > maxDistanceSq || distSq >= bestDistSq) continue;
                    bestPos = pos.toImmutable();
                    bestDistSq = distSq;
                }
            }
        }

        return bestPos;
    }

    private boolean isStorageTaskStage(Stage stage) {
        return stage == Stage.MoveToStorage
            || stage == Stage.OpenStorage
            || stage == Stage.WaitForStorage
            || stage == Stage.WithdrawBooks
            || stage == Stage.CloseStorage
            || stage == Stage.FetchBooks;
    }

    private BlockPos findNearestKnownStorage() {
        return storages.get().stream()
            .filter(entry -> entry.enabled.get())
            .map(entry -> entry.pos.get().toImmutable())
            .filter(pos -> isSupportedStorage(mc.world.getBlockState(pos).getBlock()))
            .filter(pos -> isWithinInteractRange(pos, detectRange.get()))
            .min(Comparator.comparingDouble(this::distanceToPos))
            .orElse(null);
    }

    private void updateTargetedStoragePos() {
        HitResult target = mc.crosshairTarget;
        if (!(target instanceof BlockHitResult blockHit)) return;
        BlockPos pos = blockHit.getBlockPos();
        if (isSupportedStorage(mc.world.getBlockState(pos).getBlock())) {
            lastTargetedStoragePos = pos.toImmutable();
            lastTargetedStorageSide = blockHit.getSide();
        }
    }

    private boolean isSupportedStorage(Block block) {
        return block instanceof AbstractChestBlock<?> || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock || block == Blocks.BARREL;
    }

    private boolean isStorageScreen() {
        return mc.currentScreen != null
            && !(mc.currentScreen instanceof WidgetScreen)
            && (mc.currentScreen instanceof ShulkerBoxScreen || mc.currentScreen instanceof GenericContainerScreen);
    }

    private BlockPos findNearestAnvil(Set<BlockPos> excluded) {
        BlockPos center = mc.player.getBlockPos();
        int radius = Math.max(2, (int) Math.ceil(anvilRange.get()));
        List<BlockPos> candidates = BlockPos.streamOutwards(center, radius, radius, radius)
            .filter(pos -> !excluded.contains(pos))
            .filter(pos -> mc.world.getBlockState(pos).getBlock() instanceof AnvilBlock)
            .map(BlockPos::toImmutable)
            .toList();

        BlockPos best = candidates.stream()
            .filter(pos -> findInteractionApproachPos(pos) != null)
            .min(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - center.getY()))
                .thenComparingDouble(this::horizontalDistanceSq)
                .thenComparingDouble(this::distanceToPos))
            .orElse(null);

        if (best == null) debug("No usable anvil candidate found within range " + radius + ". Raw anvils found: " + candidates.size());
        else debug("Selected anvil at " + best + ".");
        return best;
    }

    private BlockPos findNearestEnchantTable(Set<BlockPos> excluded) {
        BlockPos center = mc.player.getBlockPos();
        int radius = Math.max(2, (int) Math.ceil(enchantTableRange.get()));
        List<BlockPos> candidates = BlockPos.streamOutwards(center, radius, radius, radius)
            .filter(pos -> !excluded.contains(pos))
            .filter(pos -> mc.world.getBlockState(pos).getBlock() == Blocks.ENCHANTING_TABLE)
            .map(BlockPos::toImmutable)
            .toList();

        BlockPos best = candidates.stream()
            .filter(pos -> findInteractionApproachPos(pos) != null)
            .min(Comparator
                .comparingInt((BlockPos pos) -> Math.abs(pos.getY() - center.getY()))
                .thenComparingDouble(this::horizontalDistanceSq)
                .thenComparingDouble(this::distanceToPos))
            .orElse(null);

        if (best == null) debug("No usable enchant table candidate found within range " + radius + ". Raw tables found: " + candidates.size());
        else debug("Selected enchant table at " + best + ".");
        return best;
    }

    private boolean isWithinInteractRange(BlockPos pos, double range) {
        return distanceToPos(pos) <= range * range;
    }

    private boolean canInteractWithBlock(BlockPos pos) {
        return isWithinInteractRange(pos, BLOCK_INTERACT_RANGE);
    }

    private boolean canInteractWithBlockFrom(BlockPos standPos, BlockPos target, double range) {
        return getEyePosForStand(standPos).squaredDistanceTo(Vec3d.ofCenter(target)) <= range * range;
    }

    private double horizontalDistanceSq(BlockPos pos) {
        double dx = mc.player.getX() - (pos.getX() + 0.5);
        double dz = mc.player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
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
                    if (!canInteractWithBlockFrom(candidate, target, BLOCK_INTERACT_RANGE + 0.75)) continue;

                    double score = horizontalDistanceSq(candidate)
                        + Math.abs(candidate.getY() - mc.player.getBlockY()) * 4.0
                        + Math.abs(dx) + Math.abs(dz) * 0.05;

                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy == 0) score -= 0.5;

                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private Vec3d getEyePosForStand(BlockPos pos) {
        double eyeHeight = mc.player != null ? mc.player.getEyeHeight(mc.player.getPose()) : 1.62;
        return new Vec3d(pos.getX() + 0.5, pos.getY() + eyeHeight, pos.getZ() + 0.5);
    }

    private boolean canStandAt(BlockPos pos) {
        BlockState feetState = mc.world.getBlockState(pos);
        BlockState headState = mc.world.getBlockState(pos.up());
        BlockState belowState = mc.world.getBlockState(pos.down());

        if (!feetState.isAir() && !feetState.isReplaceable()) return false;
        if (!headState.isAir() && !headState.isReplaceable()) return false;
        if (belowState.isAir() || belowState.isReplaceable()) return false;
        return true;
    }

    private double distanceToPos(BlockPos pos) {
        return mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos));
    }

    private void interactBlock(BlockPos pos) {
        aimAt(pos);
        BlockHitResult hit = buildHitResult(pos);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private BlockHitResult buildHitResult(BlockPos pos) {
        if (pos.equals(lastTargetedStoragePos) && lastTargetedStorageSide != null) {
            Vec3d hitPos = Vec3d.ofCenter(pos).add(Vec3d.of(lastTargetedStorageSide.getVector()).multiply(0.5));
            return new BlockHitResult(hitPos, lastTargetedStorageSide, pos, false);
        }

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
        double dx = diff.x;
        double dy = diff.y;
        double dz = diff.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, dist)));
    }

    private int getAnvilUseCount(ItemStack stack) {
        int repairCost = stack.getOrDefault(DataComponentTypes.REPAIR_COST, 0);
        int uses = 0;
        int value = 0;
        while (value < repairCost) {
            uses++;
            value = AnvilScreenHandler.getNextCost(value);
        }
        return uses;
    }

    private String getItemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private int countInventoryItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int findInventorySlot(Item item, int minimumCount) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(item) && stack.getCount() >= minimumCount) return i;
        }
        return -1;
    }

    private String describeEnchantTableOption(EnchantmentScreenHandler handler, int optionIndex) {
        int power = optionIndex >= 0 && optionIndex < handler.enchantmentPower.length ? handler.enchantmentPower[optionIndex] : 0;
        int shownLevel = optionIndex >= 0 && optionIndex < handler.enchantmentLevel.length ? Math.max(handler.enchantmentLevel[optionIndex], 1) : 1;
        String name = "Unknown enchant";

        if (mc.world != null && optionIndex >= 0 && optionIndex < handler.enchantmentId.length && handler.enchantmentId[optionIndex] >= 0) {
            Registry<Enchantment> registry = mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
            Optional<RegistryEntry.Reference<Enchantment>> entry = registry.getEntry(handler.enchantmentId[optionIndex]);
            if (entry.isPresent()) {
                Optional<RegistryKey<Enchantment>> key = entry.get().getKey();
                if (key.isPresent()) name = formatEnchant(key.get().getValue().getPath(), shownLevel);
            }
        }

        return power > 0 ? name + " (" + power + " levels)" : name;
    }

    private boolean hasPendingPostTableConfirmation() {
        return pendingPostTableConfirmation != null;
    }

    private void confirmPendingPostTableEnchant() {
        if (pendingPostTableConfirmation == null) return;

        ItemStack held = mc.player.getMainHandStack();
        String currentSignature = held.isEmpty() ? null : buildEnchantSignature(getEnchantIdMap(held));
        if (held.isEmpty()
            || !pendingPostTableConfirmation.itemId().equals(getItemId(held))
            || !pendingPostTableConfirmation.enchantSignature().equals(currentSignature)) {
            notifyWarn("Hold the same enchant-table result in your main hand before continuing.");
            return;
        }

        pendingPostTableConfirmation = null;
        queuedManualEnchant = true;
        debug("Queued confirmed post-enchant-table max-enchant trigger.");
    }

    private void finishPostTablePreview(PlanningOutcome outcome) {
        ItemStack held = mc.player.getMainHandStack();
        String currentSignature = held.isEmpty() ? null : buildEnchantSignature(getEnchantIdMap(held));
        if (held.isEmpty()
            || !outcome.itemId().equals(getItemId(held))
            || currentSignature == null
            || currentSignature.isBlank()) {
            notifyWarn("Post-enchant-table preview finished, but the enchanted item is no longer in your main hand. Hold it again to continue manually.");
            pendingPostTableConfirmation = null;
            return;
        }

        pendingPostTableConfirmation = new PendingPostTableConfirmation(
            outcome.itemId(),
            outcome.itemName(),
            outcome.originalSelectedSlot(),
            currentSignature
        );
        String enchantSummary = buildEnchantDisplay(held);
        notifyInfo("Post-table preview only. Current roll: " + (enchantSummary.isBlank() ? outcome.itemName() : enchantSummary) + ".");
        notifyInfo("Press 'Continue After Table Enchant' to start max-enchanting " + outcome.itemName() + ".");
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

    private void notifyInfo(String message) {
        if (chatNotify.get()) ChatUtils.infoPrefix("Auto Enchanting", message);
        AddonLog.info(message);
    }

    private void notifyWarn(String message) {
        if (chatNotify.get()) ChatUtils.warningPrefix("Auto Enchanting", message);
        AddonLog.info(message);
    }

    private void notifyError(String message) {
        if (chatNotify.get()) ChatUtils.errorPrefix("Auto Enchanting", message);
        AddonLog.info(message);
    }

    private void debug(String message) {
        if (debugLogging.get()) AddonLog.info("[AutoEnchantingDebug] " + message);
    }

    private enum Stage {
        Idle,
        PrepareEnchantTable,
        MoveToEnchantTable,
        OpenEnchantTable,
        WaitForEnchantTable,
        LoadEnchantTableInputs,
        WaitForEnchantOptions,
        ClickEnchantOption,
        WaitForEnchantTableResult,
        CloseEnchantTable,
        PrepareRenameOnly,
        LoadRenameOnlyInput,
        FetchBooks,
        MoveToStorage,
        OpenStorage,
        WaitForStorage,
        WithdrawBooks,
        CloseStorage,
        PrepareAnvilStep,
        MoveToAnvil,
        OpenAnvil,
        WaitForAnvil,
        LoadStepInputs,
        WaitForResult,
        TakeResult,
        CloseAnvil,
        EquipFinalResult,
        Completed,
        Failed
    }

    private static final class MutableBookEntry {
        private final Map<String, Integer> enchants;
        private final String displayName;
        private int count;

        private MutableBookEntry(Map<String, Integer> enchants, String displayName) {
            this.enchants = new LinkedHashMap<>(enchants);
            this.displayName = displayName;
        }
    }

    private static final class InventoryBookCount {
        private final Map<String, Integer> enchants;
        private int count;

        private InventoryBookCount(Map<String, Integer> enchants) {
            this.enchants = new LinkedHashMap<>(enchants);
        }
    }

    private static final class AggregatedBookBuilder {
        private final String signature;
        private final Map<String, Integer> enchants;
        private final String displayName;
        private int totalCount;

        private AggregatedBookBuilder(String signature, Map<String, Integer> enchants, String displayName) {
            this.signature = signature;
            this.enchants = new LinkedHashMap<>(enchants);
            this.displayName = displayName;
        }
    }

    private record AggregatedBookType(String signature, Map<String, Integer> enchants, String displayName, int totalCount) {
    }

    private record PlanningBookSource(String signature, String displayName, Map<String, Integer> enchants, EnchantingPlanSolver.Node node) {
    }

    private record PlanningSnapshot(
        int token,
        String itemId,
        String itemName,
        Map<String, Integer> baseEnchants,
        Map<String, Integer> target,
        List<AggregatedBookType> availableTypes,
        int anvilUseCount,
        String desiredFinalName,
        boolean shouldRenameFinalItem,
        boolean previewOnly,
        boolean postTablePreview,
        int originalSelectedSlot,
        long planStartedNanos
    ) {
    }

    private record PlanningOutcome(
        int token,
        boolean success,
        String message,
        String itemId,
        String itemName,
        Map<String, Integer> baseEnchants,
        int baseAnvilUseCount,
        EnchantingPlanSolver.Plan plan,
        List<BookRequirement> requirements,
        int totalCost,
        String desiredFinalName,
        boolean previewOnly,
        boolean postTablePreview,
        int originalSelectedSlot,
        int availableTypeCount,
        String debugSummary
    ) {
        private static PlanningOutcome success(
            int token,
            String itemId,
            String itemName,
            Map<String, Integer> baseEnchants,
            int baseAnvilUseCount,
            EnchantingPlanSolver.Plan plan,
            List<BookRequirement> requirements,
            int totalCost,
            String desiredFinalName,
            boolean previewOnly,
            boolean postTablePreview,
            int originalSelectedSlot,
            int availableTypeCount,
            String debugSummary
        ) {
            return new PlanningOutcome(
                token,
                true,
                null,
                itemId,
                itemName,
                new LinkedHashMap<>(baseEnchants),
                baseAnvilUseCount,
                plan,
                requirements,
                totalCost,
                desiredFinalName,
                previewOnly,
                postTablePreview,
                originalSelectedSlot,
                availableTypeCount,
                debugSummary
            );
        }

        private static PlanningOutcome failure(int token, String message, String debugSummary, boolean previewOnly, boolean postTablePreview) {
            return new PlanningOutcome(token, false, message, null, null, Map.of(), 0, null, List.of(), 0, null, previewOnly, postTablePreview, -1, 0, debugSummary);
        }
    }

    private record PendingPostTableConfirmation(String itemId, String itemName, int originalSelectedSlot, String enchantSignature) {
    }

    private static final class BookRequirement {
        private final String signature;
        private final String displayName;
        private final Map<String, Integer> enchants;
        private boolean satisfied;

        private BookRequirement(String signature, String displayName, Map<String, Integer> enchants) {
            this.signature = signature;
            this.displayName = displayName;
            this.enchants = new LinkedHashMap<>(enchants);
        }
    }

    private static final class AutoEnchantTask {
        private final EnchantingPlanSolver.Plan plan;
        private final List<BookRequirement> requirements;
        private final int originalSelectedSlot;
        private final int totalCost;
        private final boolean pausedBaritone;
        private final String desiredFinalName;
        private final String heldItemId;
        private final String heldItemName;
        private final Map<String, Integer> initialItemEnchants;
        private final int initialItemAnvilUseCount;
        private final boolean renameOnly;
        private final Set<BlockPos> failedAnvils = new HashSet<>();
        private final Set<BlockPos> failedEnchantTables = new HashSet<>();
        private Stage stage = Stage.Idle;
        private int stageTicks;
        private int stepIndex;
        private int activeAnvilStepIndex = -1;
        private int currentAnvilStepRetries;
        private boolean pathingStarted;
        private boolean resultTakeClicked;
        private boolean renameRequestedForCurrentStep;
        private boolean baritoneResumedForModuleCommands;
        private BlockPos currentStoragePos;
        private BlockPos currentAnvilPos;
        private BlockPos currentEnchantTablePos;
        private EnchantingPlanSolver.MergeStep currentStep;
        private String tableEnchantResultDisplay;
        private String tableEnchantResultSignature;
        private String failureReason;

        private AutoEnchantTask(
            EnchantingPlanSolver.Plan plan,
            List<BookRequirement> requirements,
            int originalSelectedSlot,
            int totalCost,
            boolean pausedBaritone,
            String desiredFinalName,
            String heldItemId,
            String heldItemName,
            Map<String, Integer> initialItemEnchants,
            int initialItemAnvilUseCount,
            boolean renameOnly
        ) {
            this.plan = plan;
            this.requirements = new ArrayList<>(requirements);
            this.originalSelectedSlot = originalSelectedSlot;
            this.totalCost = totalCost;
            this.pausedBaritone = pausedBaritone;
            this.desiredFinalName = desiredFinalName;
            this.heldItemId = heldItemId;
            this.heldItemName = heldItemName;
            this.initialItemEnchants = new LinkedHashMap<>(initialItemEnchants);
            this.initialItemAnvilUseCount = initialItemAnvilUseCount;
            this.renameOnly = renameOnly;
        }

        private static AutoEnchantTask bootstrap(
            int originalSelectedSlot,
            boolean pausedBaritone,
            String heldItemId,
            String heldItemName,
            Map<String, Integer> initialItemEnchants,
            int initialItemAnvilUseCount
        ) {
            return new AutoEnchantTask(
                null,
                List.of(),
                originalSelectedSlot,
                0,
                pausedBaritone,
                null,
                heldItemId,
                heldItemName,
                initialItemEnchants,
                initialItemAnvilUseCount,
                false
            );
        }

        private static AutoEnchantTask renameOnly(
            int originalSelectedSlot,
            int totalCost,
            boolean pausedBaritone,
            String desiredFinalName,
            String heldItemId,
            String heldItemName,
            Map<String, Integer> initialItemEnchants,
            int initialItemAnvilUseCount
        ) {
            return new AutoEnchantTask(
                null,
                List.of(),
                originalSelectedSlot,
                totalCost,
                pausedBaritone,
                desiredFinalName,
                heldItemId,
                heldItemName,
                initialItemEnchants,
                initialItemAnvilUseCount,
                true
            );
        }
    }
}
