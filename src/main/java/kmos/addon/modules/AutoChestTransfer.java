package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.chests.ChestEntry;
import kmos.addon.settings.ChestEntryListSetting;
import kmos.addon.util.InteractionGate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoChestTransfer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<ChestEntry>> entries = sgGeneral.add(new ChestEntryListSetting.Builder()
        .name("chests")
        .description("Configured chest transfer entries.")
        .build()
    );

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay between chest interaction steps.")
        .defaultValue(10)
        .min(1)
        .max(200)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes detailed Auto Chest Transfer debug logs to kmos-addon.log.")
        .defaultValue(false)
        .build()
    );

    private int cooldown = 0;
    private ChestEntry activeEntry = null;
    private int waitForScreenTicks = 0;
    private int openAttemptsLeft = 0;
    private int transferStallTicks = 0;
    private int activeTicks = 0;
    private final Map<BlockPos, Double> blocked = new HashMap<>();
    private static final double INTERACT_RANGE = 4.5;
    private static final int OPEN_WAIT_TICKS = 3;
    private static final int OPEN_RETRY_COUNT = 2;
    private static final int TRANSFER_SYNC_TICKS = 0;
    private static final int TRANSFER_STALL_LIMIT = 2;
    private static final int ACTIVE_TIMEOUT_TICKS = 16;
    private static final int MAX_MOVES_PER_TICK = 12;

    public AutoChestTransfer() {
        super(KmosAddon.CATEGORY, "auto-chest-transfer", "Opens configured chests nearby and moves selected items in or out automatically.");
    }

    @Override
    public void onActivate() {
        cooldown = 0;
        activeEntry = null;
        waitForScreenTicks = 0;
        openAttemptsLeft = 0;
        transferStallTicks = 0;
        activeTicks = 0;
        blocked.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);

        if (cooldown > 0) {
            cooldown--;
        }

        List<ChestEntry> currentEntries = entries.get();
        updateBlocked(currentEntries);

        if (activeEntry != null) {
            InteractionGate.acquire(InteractionGate.Owner.ChestFlow, now);
            if (++activeTicks >= ACTIVE_TIMEOUT_TICKS) {
                debug("Active timeout. Closing entry at " + activeEntry.pos.get());
                finishActive(true);
                return;
            }

            if (mc.player.currentScreenHandler instanceof MerchantScreenHandler) {
                debug("Merchant UI took over while chest entry active. Closing current screen.");
                finishActive(true);
                return;
            }

            if (isContainerOpen()) {
                if (waitForScreenTicks > 0) {
                    debug("Waiting for chest screen sync. ticks=" + waitForScreenTicks + ", pos=" + activeEntry.pos.get());
                    waitForScreenTicks--;
                    return;
                }

                ScreenHandler handler = mc.player.currentScreenHandler;
                int moved = transfer(activeEntry, handler, MAX_MOVES_PER_TICK);
                if (moved > 0) {
                    transferStallTicks = 0;
                    debug("Moved " + moved + " stacks for chest entry at " + activeEntry.pos.get());
                    waitForScreenTicks = TRANSFER_SYNC_TICKS;
                    return;
                }

                transferStallTicks++;
                debug("No chest transfer progress. stallTicks=" + transferStallTicks + ", pos=" + activeEntry.pos.get());
                if (isTransferComplete(activeEntry, handler) || transferStallTicks >= TRANSFER_STALL_LIMIT) {
                    debug("Chest entry completed or stalled. Closing screen at " + activeEntry.pos.get());
                    finishActive(true);
                }
                return;
            }

            if (waitForScreenTicks > 0) {
                debug("Waiting for chest screen to open. ticks=" + waitForScreenTicks + ", pos=" + activeEntry.pos.get());
                waitForScreenTicks--;
                return;
            }

            // Retry the same chest a few times before marking it as blocked.
            if (openAttemptsLeft > 0) {
                BlockPos retryPos = activeEntry.pos.get();
                debug("Retry opening chest. attemptsLeft=" + openAttemptsLeft + ", pos=" + retryPos);
                aimAt(retryPos);
                Vec3d retryVec = new Vec3d(retryPos.getX() + 0.5, retryPos.getY() + 0.5, retryPos.getZ() + 0.5);
                BlockHitResult retryHit = new BlockHitResult(retryVec, Direction.UP, retryPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, retryHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                openAttemptsLeft--;
                waitForScreenTicks = OPEN_WAIT_TICKS;
                return;
            }

            debug("Chest open failed, giving up on entry at " + activeEntry.pos.get());
            finishActive(false);
        }

        if (mc.currentScreen != null) return;
        if (cooldown > 0) return;
        if (currentEntries.isEmpty()) return;
        if (!InteractionGate.acquire(InteractionGate.Owner.ChestFlow, now)) return;

        ChestEntry target = findTarget(currentEntries);
        if (target == null) {
            InteractionGate.release(InteractionGate.Owner.ChestFlow);
            return;
        }

        BlockPos pos = target.pos.get();
        debug("Opening chest target at " + pos + ", mode=" + target.mode.get() + ", items=" + target.getItems());
        aimAt(pos);
        Vec3d hitVec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        activeEntry = target;
        waitForScreenTicks = OPEN_WAIT_TICKS;
        openAttemptsLeft = OPEN_RETRY_COUNT;
        transferStallTicks = 0;
        activeTicks = 0;
        blocked.put(pos, target.range.get());
        cooldown = delayTicks.get();
    }

    private ChestEntry findTarget(List<ChestEntry> entries) {
        double bestDist = Double.MAX_VALUE;
        ChestEntry best = null;

        for (ChestEntry e : entries) {
            if (!e.enabled.get()) continue;
            BlockPos pos = e.pos.get();
            if (blocked.containsKey(pos)) continue;

            double range = Math.min(e.range.get(), INTERACT_RANGE);
            double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dist > range * range) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }

        return best;
    }

    private void updateBlocked(List<ChestEntry> entries) {
        if (blocked.isEmpty()) return;

        Map<BlockPos, Double> ranges = new HashMap<>();
        for (ChestEntry e : entries) ranges.put(e.pos.get(), Math.min(e.range.get(), INTERACT_RANGE));

        blocked.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            double range = ranges.getOrDefault(pos, entry.getValue());
            double dist = mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            return dist > range * range;
        });
    }

    private int transfer(ChestEntry entry, ScreenHandler handler, int maxMoves) {
        List<Item> configuredItems = entry.getItems();
        if (configuredItems.isEmpty()) return 0;
        Set<Item> itemSet = new HashSet<>(configuredItems);
        int maxStacks = entry.maxStacks.get();
        int moved = 0;

        if (entry.mode.get() == ChestEntry.Mode.IN) {
            if (!hasChestSpaceFor(handler, itemSet)) return 0;

            int remainingStacks = Integer.MAX_VALUE;
            if (maxStacks > 0) {
                remainingStacks = maxStacks - countItemStacksInChest(handler, itemSet);
                if (remainingStacks <= 0) return 0;
            }

            for (int i = 0; i < handler.slots.size(); i++) {
                if (moved >= maxMoves) break;
                Slot slot = handler.slots.get(i);
                if (slot.inventory != mc.player.getInventory()) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty() || !itemSet.contains(stack.getItem())) continue;

                int before = maxStacks > 0 ? countItemStacksInChest(handler, itemSet) : 0;
                String movedStack = debugLogging.get() ? describeStack(stack) : null;
                quickMove(handler, i);
                moved++;
                if (movedStack != null) debug("QUICK_MOVE player->chest slot=" + i + ", stack=" + movedStack);
                if (maxStacks > 0) {
                    int after = countItemStacksInChest(handler, itemSet);
                    remainingStacks -= Math.max(0, after - before);
                    if (remainingStacks <= 0) break;
                }
            }
        } else {
            int remainingStacks = Integer.MAX_VALUE;
            if (maxStacks > 0) {
                remainingStacks = maxStacks - countItemStacksInPlayer(handler, itemSet);
                if (remainingStacks <= 0) return 0;
            }

            for (int i = 0; i < handler.slots.size(); i++) {
                if (moved >= maxMoves) break;
                if (countEmptyPlayerSlots(handler) <= 1) break;
                Slot slot = handler.slots.get(i);
                if (slot.inventory == mc.player.getInventory()) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty() || !itemSet.contains(stack.getItem())) continue;

                int before = maxStacks > 0 ? countItemStacksInPlayer(handler, itemSet) : 0;
                String movedStack = debugLogging.get() ? describeStack(stack) : null;
                quickMove(handler, i);
                moved++;
                if (movedStack != null) debug("QUICK_MOVE chest->player slot=" + i + ", stack=" + movedStack);
                if (maxStacks > 0) {
                    int after = countItemStacksInPlayer(handler, itemSet);
                    remainingStacks -= Math.max(0, after - before);
                    if (remainingStacks <= 0) break;
                }
            }
        }

        return moved;
    }

    private int countEmptyPlayerSlots(ScreenHandler handler) {
        int empty = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory != mc.player.getInventory()) continue;
            if (slot.getStack().isEmpty()) empty++;
        }
        return empty;
    }

    private int countItemStacksInPlayer(ScreenHandler handler, Set<Item> items) {
        int count = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory != mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && items.contains(stack.getItem())) count++;
        }
        return count;
    }

    private int countItemStacksInChest(ScreenHandler handler, Set<Item> items) {
        int count = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && items.contains(stack.getItem())) count++;
        }
        return count;
    }

    private void quickMove(ScreenHandler handler, int slot) {
        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    private boolean isTransferComplete(ChestEntry entry, ScreenHandler handler) {
        List<Item> configuredItems = entry.getItems();
        if (configuredItems.isEmpty()) return true;
        Set<Item> itemSet = new HashSet<>(configuredItems);

        if (entry.mode.get() == ChestEntry.Mode.IN) {
            if (!hasPlayerItem(handler, itemSet)) return true;
            if (!hasChestSpaceFor(handler, itemSet)) return true;
            return entry.maxStacks.get() > 0 && countItemStacksInChest(handler, itemSet) >= entry.maxStacks.get();
        }

        if (countEmptyPlayerSlots(handler) <= 1) return true;
        if (!hasChestItem(handler, itemSet)) return true;
        return entry.maxStacks.get() > 0 && countItemStacksInPlayer(handler, itemSet) >= entry.maxStacks.get();
    }

    private boolean hasPlayerItem(ScreenHandler handler, Set<Item> items) {
        for (Slot slot : handler.slots) {
            if (slot.inventory != mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && items.contains(stack.getItem())) return true;
        }

        return false;
    }

    private boolean hasChestItem(ScreenHandler handler, Set<Item> items) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty() && items.contains(stack.getItem())) return true;
        }

        return false;
    }

    private boolean isContainerOpen() {
        return mc.currentScreen != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler
            && !(mc.player.currentScreenHandler instanceof MerchantScreenHandler);
    }

    private boolean hasChestSpaceFor(ScreenHandler handler, Set<Item> items) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) return true;
            if (!items.contains(stack.getItem())) continue;
            if (stack.getCount() < stack.getMaxCount()) return true;
        }

        return false;
    }

    private void finishActive(boolean closeScreen) {
        if (closeScreen && isContainerOpen()) {
            debug("Closing chest screen for entry at " + activeEntry.pos.get());
            mc.player.closeHandledScreen();
        }

        activeEntry = null;
        waitForScreenTicks = 0;
        openAttemptsLeft = 0;
        transferStallTicks = 0;
        activeTicks = 0;
        cooldown = delayTicks.get();
        InteractionGate.release(InteractionGate.Owner.ChestFlow);
    }

    private void debug(String message) {
        if (debugLogging.get()) kmos.addon.util.AddonLog.info("[AutoChestTransferDebug] " + message);
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getItem().toString() + " x" + stack.getCount();
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
}


