package kmos.addon.modules;

import kmos.addon.KmosAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.player.AntiAFK;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;

public class AutoAntiAfk extends Module {
    private static final double POS_EPS = 0.0001;
    private static final float ANGLE_EPS = 0.001f;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> idleSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("idle-seconds")
        .description("How long the player can stay still before AFK mode turns on.")
        .defaultValue(60)
        .min(1)
        .max(300)
        .build()
    );

    private final Setting<Boolean> enableKillAura = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-kill-aura")
        .description("Also enables Kill Aura when AFK mode turns on.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> exitOnInput = sgGeneral.add(new BoolSetting.Builder()
        .name("exit-on-input")
        .description("Turns AFK mode off when keyboard or mouse input is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> inputGraceMs = sgGeneral.add(new IntSetting.Builder()
        .name("input-grace-ms")
        .description("Ignores input briefly right after AFK mode turns on.")
        .defaultValue(500)
        .min(0)
        .max(2000)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Shows debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private boolean afkActive = false;
    private long lastActivityMs = 0L;
    private long lastInputMs = 0L;
    private long afkEnabledMs = 0L;

    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private int lastInventorySignature = 0;

    private boolean prevAntiAfk = false;
    private boolean prevKillAura = false;
    private boolean changedAntiAfk = false;
    private boolean changedKillAura = false;

    public AutoAntiAfk() {
        super(KmosAddon.CATEGORY, "auto-anti-afk", "Turns on Meteor Anti AFK after you stay inactive for the configured time.");
    }

    @Override
    public void onActivate() {
        resetTracking();
        if (debug.get()) ChatUtils.info("[KMOS] Auto Anti-AFK enabled.");
    }

    @Override
    public void onDeactivate() {
        disableAfkModules();
        afkActive = false;
        if (debug.get()) ChatUtils.info("[KMOS] Auto Anti-AFK disabled.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (!afkActive) {
            boolean moved = playerMoved();
            boolean looked = cameraMoved();
            boolean interacting = isPlayerInteracting() || inventoryChanged();
            if (moved || looked || interacting) {
                markActivity();
                updateLastState();
            }

            long idleFor = System.currentTimeMillis() - lastActivityMs;
            if (idleFor >= idleSeconds.get() * 1000L) {
                enableAfkModules();
                afkActive = true;
                afkEnabledMs = System.currentTimeMillis();
                lastInputMs = 0L;
                if (debug.get()) ChatUtils.info("[KMOS] AFK detected. Enabled Anti-AFK%s.",
                    enableKillAura.get() ? " + Kill Aura" : "");
            }
        } else {
            if (exitOnInput.get() && lastInputMs > 0) {
                disableAfkModules();
                afkActive = false;
                lastActivityMs = System.currentTimeMillis();
                updateLastState();
                if (debug.get()) ChatUtils.info("[KMOS] Input detected. AFK disabled.");
            }
        }
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        markActivity();
        if (!exitOnInput.get()) return;
        if (afkActive && inputGraceMs.get() > 0 && System.currentTimeMillis() - afkEnabledMs < inputGraceMs.get()) return;
        lastInputMs = System.currentTimeMillis();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        markActivity();
        if (!exitOnInput.get()) return;
        if (afkActive && inputGraceMs.get() > 0 && System.currentTimeMillis() - afkEnabledMs < inputGraceMs.get()) return;
        lastInputMs = System.currentTimeMillis();
    }

    private void resetTracking() {
        lastActivityMs = System.currentTimeMillis();
        lastInputMs = 0L;
        updateLastState();
        prevAntiAfk = false;
        prevKillAura = false;
        changedAntiAfk = false;
        changedKillAura = false;
    }

    private boolean playerMoved() {
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        return Math.abs(x - lastX) > POS_EPS || Math.abs(y - lastY) > POS_EPS || Math.abs(z - lastZ) > POS_EPS;
    }

    private boolean cameraMoved() {
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        return Math.abs(yaw - lastYaw) > ANGLE_EPS || Math.abs(pitch - lastPitch) > ANGLE_EPS;
    }

    private void updateLastState() {
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        lastZ = mc.player.getZ();
        lastYaw = mc.player.getYaw();
        lastPitch = mc.player.getPitch();
        lastInventorySignature = inventorySignature();
    }

    private void markActivity() {
        lastActivityMs = System.currentTimeMillis();
    }

    private boolean isPlayerInteracting() {
        if (mc.currentScreen != null) return true;
        if (mc.options.useKey.isPressed()) return true;
        if (mc.options.attackKey.isPressed()) return true;
        if (mc.options.jumpKey.isPressed()) return true;
        if (mc.options.sneakKey.isPressed()) return true;
        if (mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()) return true;
        if (mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()) return true;
        if (mc.player.isUsingItem()) return true;
        if (mc.player.handSwinging) return true;
        return mc.crosshairTarget != null && mc.crosshairTarget.getType() != null && mc.options.pickItemKey.isPressed();
    }

    private boolean inventoryChanged() {
        return inventorySignature() != lastInventorySignature;
    }

    private int inventorySignature() {
        int hash = 1;
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var stack = inventory.getStack(slot);
            hash = 31 * hash + Item.getRawId(stack.getItem());
            hash = 31 * hash + stack.getCount();
        }
        return hash;
    }

    private void enableAfkModules() {
        AntiAFK antiAfk = Modules.get().get(AntiAFK.class);
        if (antiAfk != null) {
            prevAntiAfk = antiAfk.isActive();
            changedAntiAfk = !antiAfk.isActive();
            if (changedAntiAfk) antiAfk.toggle();
        }

        if (enableKillAura.get()) {
            KillAura ka = Modules.get().get(KillAura.class);
            if (ka != null) {
                prevKillAura = ka.isActive();
                changedKillAura = !ka.isActive();
                if (changedKillAura) ka.toggle();
            }
        }
    }

    private void disableAfkModules() {
        AntiAFK antiAfk = Modules.get().get(AntiAFK.class);
        if (antiAfk != null && changedAntiAfk && !prevAntiAfk && antiAfk.isActive()) antiAfk.toggle();

        KillAura ka = Modules.get().get(KillAura.class);
        if (ka != null && changedKillAura && !prevKillAura && ka.isActive()) ka.toggle();

        changedAntiAfk = false;
        changedKillAura = false;
    }
}


