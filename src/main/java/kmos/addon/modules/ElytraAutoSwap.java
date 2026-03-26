package kmos.addon.modules;

import kmos.addon.KmosAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

public class ElytraAutoSwap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Shows debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> thresholdPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("threshold-percent")
        .description("Swaps the elytra when remaining durability drops below this percentage.")
        .defaultValue(5.0)
        .min(0.1)
        .max(100.0)
        .build()
    );

    private final Setting<Integer> cooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Delay after a successful swap.")
        .defaultValue(20)
        .min(0)
        .max(200)
        .build()
    );

    private final Setting<Integer> noReplacementCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("no-replacement-cooldown-ticks")
        .description("Delay after no suitable replacement elytra is found.")
        .defaultValue(100)
        .min(0)
        .max(400)
        .build()
    );

    private final Setting<Integer> debugEveryTicks = sgGeneral.add(new IntSetting.Builder()
        .name("debug-every-ticks")
        .description("Prints periodic debug information every N ticks while the low-durability condition is active. Set 0 to disable.")
        .defaultValue(0)
        .min(0)
        .max(200)
        .build()
    );

    private int cooldown = 0;
    private int debugCounter = 0;

    public ElytraAutoSwap() {
        super(KmosAddon.CATEGORY, "elytra-auto-swap", "Swaps out a worn elytra when its durability drops below the configured threshold.");
    }

    @Override
    public void onActivate() {
        if (debug.get()) ChatUtils.info("KMOS ElytraAutoSwap ENABLED");
    }

    @Override
    public void onDeactivate() {
        if (debug.get()) ChatUtils.info("KMOS ElytraAutoSwap DISABLED");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Read the equipped chest item directly instead of assuming handler slot positions.
        ItemStack worn = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (worn.isEmpty() || worn.getItem() != Items.ELYTRA) return;
        if (!worn.isDamageable()) return;

        int max = worn.getMaxDamage();
        int dmg = worn.getDamage();
        int remaining = max - dmg;
        double remainingPercent = (remaining * 100.0) / max;

        if (remainingPercent > thresholdPercent.get()) {
            debugCounter = 0;
            return;
        }

        // Resolve the current handler slot that represents the equipped elytra.
        int equippedSlot = findEquippedElytraHandlerSlot(worn);
        if (equippedSlot == -1) {
            if (debug.get()) ChatUtils.info("Elytra is equipped, but couldn't map it to a handler slot.");
            cooldown = Math.max(cooldown, noReplacementCooldownTicks.get());
            return;
        }

        // Optional periodic debug output while the elytra is below the threshold.
        if (debug.get() && debugEveryTicks.get() > 0) {
            debugCounter++;
            if (debugCounter >= debugEveryTicks.get()) {
                debugCounter = 0;
                ChatUtils.info("Elytra low: %.2f%% (slot %d), searching replacement...", remainingPercent, equippedSlot);
            }
        }

        // Search the current screen handler for the best replacement elytra.
        int bestSlot = -1;
        int bestRemaining = -1;

        int totalSlots = mc.player.currentScreenHandler.slots.size();
        for (int i = 0; i < totalSlots; i++) {
            if (i == equippedSlot) continue;

            ItemStack st = mc.player.currentScreenHandler.getSlot(i).getStack();
            if (st.isEmpty() || st.getItem() != Items.ELYTRA) continue;
            if (!st.isDamageable()) continue;

            int r = st.getMaxDamage() - st.getDamage();
            if (r > bestRemaining && r > remaining) {
                bestRemaining = r;
                bestSlot = i;
            }
        }

        if (bestSlot == -1) {
            if (debug.get()) ChatUtils.info("No better elytra found.");
            cooldown = Math.max(cooldown, noReplacementCooldownTicks.get());
            return;
        }

        // Perform a three-click pickup swap between the equipped slot and the replacement slot.
        click(equippedSlot);
        click(bestSlot);
        click(equippedSlot);

        if (debug.get()) ChatUtils.info("Swapped elytra (slot %d) with slot %d.", equippedSlot, bestSlot);

        cooldown = cooldownTicks.get();
        debugCounter = 0;
    }

    private int findEquippedElytraHandlerSlot(ItemStack equippedStack) {
        int totalSlots = mc.player.currentScreenHandler.slots.size();
        for (int i = 0; i < totalSlots; i++) {
            Slot slot = mc.player.currentScreenHandler.getSlot(i);
            ItemStack st = slot.getStack();
            if (st.isEmpty() || st.getItem() != Items.ELYTRA) continue;

            // Match the exact stack to avoid confusing identical elytras elsewhere in inventory.
            if (!ItemStack.areEqual(st, equippedStack)) continue;

            // Prefer the armor slot so identical inventory stacks do not win the match first.
            if (slot.inventory == mc.player.getInventory()) {
                int idx = slot.getIndex();
                if (idx >= 36 && idx <= 39) return i;
            }
        }
        return -1;
    }

    private void click(int slot) {
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            slot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );
    }
}


