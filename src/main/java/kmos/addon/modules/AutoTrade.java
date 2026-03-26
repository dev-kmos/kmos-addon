package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.settings.TradeEntryListSetting;
import kmos.addon.trades.TradeEntry;
import kmos.addon.util.AddonLog;
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
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutoTrade extends Module {
    private static NbtCompound cachedTag;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOffers = settings.createGroup("Offers");

    private final Setting<Integer> delayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay after a completed trade before another one can start.")
        .defaultValue(2)
        .min(0)
        .max(40)
        .build()
    );

    private final Setting<Boolean> closeWhenDone = sgGeneral.add(new BoolSetting.Builder()
        .name("close-when-done")
        .description("Closes the villager screen when no configured trade can be completed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Writes detailed Auto Trade Plus debug logs to kmos-addon.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<TradeEntry>> offers = sgOffers.add(new TradeEntryListSetting.Builder()
        .name("offers")
        .description("Trade entries in priority order. Higher rows are checked first.")
        .build()
    );

    private int cooldown = 0;
    private int sessionSyncId = -1;
    private boolean noMatchReported = false;
    private final Map<Integer, Integer> spentInput = new HashMap<>();
    private int stallTicks = 0;

    private Match pendingMatch = null;
    private int pendingTicks = 0;
    private static final int STALL_LIMIT_TICKS = 6;

    public AutoTrade() {
        super(KmosAddon.CATEGORY, "auto-trade-plus", "Executes configured villager trades automatically from a priority list of offers.");
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag != null) cachedTag = tag.copy();
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        cachedTag = tag.copy();
        return this;
    }

    public static NbtCompound getCachedTag() {
        return cachedTag == null ? null : cachedTag.copy();
    }

    @Override
    public void onActivate() {
        resetSession();
    }

    @Override
    public void onDeactivate() {
        resetSession();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        long now = mc.player.age;
        InteractionGate.clearExpiredReservation(now);

        if (!(mc.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
            if (sessionSyncId != -1) debug("Merchant screen closed or replaced.");
            resetSession();
            return;
        }

        if (!InteractionGate.acquire(InteractionGate.Owner.TradeFlow, now)) {
            debug("Trade flow blocked by " + InteractionGate.getActiveOwner() + ".");
            return;
        }
        InteractionGate.clearReservation(InteractionGate.Owner.TradeFlow);

        if (sessionSyncId != handler.syncId) startSession(handler.syncId);

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (pendingMatch != null) {
            if (pendingTicks > 0) {
                debug("Waiting on pending trade. pendingTicks=" + pendingTicks + ", offerIndex=" + pendingMatch.offerIndex);
                pendingTicks--;
                return;
            }

            if (++stallTicks >= STALL_LIMIT_TICKS) {
                debug("Stall timeout reached while waiting to execute a pending trade.");
                if (closeWhenDone.get()) {
                    mc.player.closeHandledScreen();
                    resetSession();
                } else {
                    pendingMatch = null;
                    pendingTicks = 0;
                    stallTicks = 0;
                }
                return;
            }

            TradeAttemptResult result = executePendingTrade(handler, pendingMatch);
            Match finished = pendingMatch;
            pendingMatch = null;

            if (chatFeedback) {
                if (result.success) info(result.message);
                else warning(result.message);
            }
            if (result.success) AddonLog.info("AutoTrade: " + result.message);
            else AddonLog.warn("AutoTrade: " + result.message);

            if (result.success) {
                spentInput.merge(finished.entryIndex, result.spentInput, Integer::sum);
                noMatchReported = false;
                stallTicks = 0;
                debug("Trade success. entry=" + finished.entryIndex + ", spent=" + result.spentInput + ", cooldown=" + delayTicks.get());
                cooldown = delayTicks.get();
            } else {
                debug("Trade failed. entry=" + finished.entryIndex + ", reason=" + result.message);
                if (closeWhenDone.get()) {
                    mc.player.closeHandledScreen();
                    resetSession();
                } else {
                    stallTicks = 0;
                }
                return;
            }
            return;
        }

        if (++stallTicks >= STALL_LIMIT_TICKS) {
            debug("Stall timeout reached. Closing merchant screen.");
            if (closeWhenDone.get()) {
                mc.player.closeHandledScreen();
                resetSession();
            } else {
                stallTicks = 0;
            }
            return;
        }

        FindResult findResult = findMatch(handler);
        if (findResult.match == null) {
            debug("No match. insufficient=" + findResult.insufficientMessage + ", noStock=" + findResult.noStockMessage);
            if (!noMatchReported && chatFeedback) {
                if (findResult.insufficientMessage != null) warning(findResult.insufficientMessage);
                else if (findResult.noStockMessage != null) warning(findResult.noStockMessage);
                else info("No matching trade available.");
                noMatchReported = true;
            }
            if (closeWhenDone.get()) {
                mc.player.closeHandledScreen();
                resetSession();
            } else {
                stallTicks = 0;
            }
            return;
        }

        queueTrade(handler, findResult.match);
    }

    private void queueTrade(MerchantScreenHandler handler, Match match) {
        debug("Queue trade. entry=" + match.entryIndex + ", offerIndex=" + match.offerIndex + ", requiredInput=" + match.requiredInput
            + ", playerInput=" + countPlayerItem(match.entry.inputItem.get()) + ", playerOutput=" + countPlayerItem(match.entry.outputItem.get()));
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(match.offerIndex));
        }
        handler.setRecipeIndex(match.offerIndex);
        handler.switchTo(match.offerIndex);
        pendingMatch = match;
        pendingTicks = 1;
        stallTicks = 0;
    }

    private void startSession(int syncId) {
        sessionSyncId = syncId;
        cooldown = 0;
        noMatchReported = false;
        spentInput.clear();
        pendingMatch = null;
        pendingTicks = 0;
        stallTicks = 0;
        AddonLog.info("AutoTrade: session started (syncId=" + syncId + ").");
        debug("Recipes available=" + (mc.player.currentScreenHandler instanceof MerchantScreenHandler handler ? handler.getRecipes().size() : -1));
    }

    private void resetSession() {
        if (sessionSyncId != -1) AddonLog.info("AutoTrade: session ended.");
        sessionSyncId = -1;
        cooldown = 0;
        noMatchReported = false;
        spentInput.clear();
        pendingMatch = null;
        pendingTicks = 0;
        stallTicks = 0;
        InteractionGate.release(InteractionGate.Owner.TradeFlow);
        InteractionGate.clearExpiredReservation(mc.player != null ? mc.player.age : 0);
    }

    private FindResult findMatch(MerchantScreenHandler handler) {
        List<TradeEntry> entries = offers.get();
        if (entries.isEmpty()) return FindResult.none();

        TradeOfferList recipes = handler.getRecipes();
        if (recipes == null || recipes.isEmpty()) return FindResult.none();

        Map<Item, Integer> inventoryCounts = new HashMap<>();

        String insufficientMessage = null;
        String noStockMessage = null;

        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            TradeEntry entry = entries.get(entryIndex);
            if (!entry.enabled.get()) continue;
            if (entry.inputItem.get() == Items.AIR || entry.outputItem.get() == Items.AIR) continue;

            int max = entry.maxInput.get();
            int spent = spentInput.getOrDefault(entryIndex, 0);
            int remainingBudget = max - spent;
            int availableInput = inventoryCounts.computeIfAbsent(entry.inputItem.get(), this::countPlayerItem);
            debug("Check entry=" + entryIndex + ", input=" + entry.inputItem.get() + ", output=" + entry.outputItem.get()
                + ", remainingBudget=" + remainingBudget + ", availableInput=" + availableInput);
            if (remainingBudget <= 0) continue;
            if (availableInput <= 0) continue;

            int minRequired = Integer.MAX_VALUE;
            boolean seenMatchingOffer = false;

            for (int offerIndex = 0; offerIndex < recipes.size(); offerIndex++) {
                TradeOffer offer = recipes.get(offerIndex);
                if (offer == null) continue;

                ItemStack buyFirst = offer.getDisplayedFirstBuyItem();
                ItemStack buySecond = offer.getDisplayedSecondBuyItem();
                ItemStack sell = offer.getSellItem();

                if (buyFirst.isEmpty() || sell.isEmpty()) continue;
                if (buyFirst.getItem() != entry.inputItem.get()) continue;
                if (sell.getItem() != entry.outputItem.get()) continue;

                // Intentionally limited to single-input offers to keep matching predictable.
                if (!buySecond.isEmpty()) continue;

                seenMatchingOffer = true;
                debug("Offer match candidate. entry=" + entryIndex + ", offerIndex=" + offerIndex + ", cost=" + buyFirst.getCount()
                    + ", disabled=" + offer.isDisabled() + ", outputCount=" + sell.getCount());
                if (offer.isDisabled()) {
                    String outputName = entry.outputItem.get().getName().getString();
                    noStockMessage = "Villager currently does not sell your trade for " + outputName + ".";
                    continue;
                }

                int requiredInput = buyFirst.getCount();
                minRequired = Math.min(minRequired, requiredInput);
                if (requiredInput > remainingBudget) continue;
                if (requiredInput > availableInput) continue;

                return FindResult.match(new Match(entry, entryIndex, offerIndex, requiredInput, sell.getCount()));
            }

            if (seenMatchingOffer && minRequired != Integer.MAX_VALUE && availableInput > 0 && minRequired > availableInput) {
                String inputName = entry.inputItem.get().getName().getString();
                insufficientMessage = "Not enough " + inputName + ". Minimum trade cost is " + minRequired + ", you have " + availableInput + ".";
            }
        }

        return new FindResult(null, insufficientMessage, noStockMessage);
    }

    private TradeAttemptResult executePendingTrade(MerchantScreenHandler handler, Match match) {
        ItemStack preview = handler.getSlot(2).getStack();
        debug("Execute trade. offerIndex=" + match.offerIndex + ", preview=" + describeStack(preview));
        if (preview.isEmpty()) {
            return TradeAttemptResult.fail("Trade skipped: no valid output (out of stock / wrong cost / missing items).");
        }

        int beforeInput = countPlayerItem(match.entry.inputItem.get());
        int beforeOutput = countPlayerItem(match.entry.outputItem.get());

        mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);

        int afterInput = countPlayerItem(match.entry.inputItem.get());
        int afterOutput = countPlayerItem(match.entry.outputItem.get());
        debug("Trade click result. beforeInput=" + beforeInput + ", afterInput=" + afterInput + ", beforeOutput=" + beforeOutput + ", afterOutput=" + afterOutput);

        int gained = Math.max(0, afterOutput - beforeOutput);
        int measuredSpent = Math.max(0, beforeInput - afterInput);
        if (gained <= 0) {
            return TradeAttemptResult.fail("Trade skipped: output not moved to inventory.");
        }

        int tradesDone = Math.max(1, gained / Math.max(1, match.outputCount));
        int spentTotal = measuredSpent > 0 ? measuredSpent : match.requiredInput * tradesDone;
        String outputName = match.entry.outputItem.get().getName().getString();
        String inputName = match.entry.inputItem.get().getName().getString();
        double pricePerItem = (double) spentTotal / (double) gained;
        String message = "Traded " + spentTotal + "x " + inputName + " for " + gained + "x " + outputName
            + " (" + String.format(java.util.Locale.ROOT, "%.2f", pricePerItem) + " " + inputName + "/item).";
        return TradeAttemptResult.ok(spentTotal, message);
    }

    private int countPlayerItem(Item item) {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private void debug(String message) {
        if (debugLogging.get()) AddonLog.info("[AutoTradeDebug] " + message);
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return stack.getItem().toString() + " x" + stack.getCount();
    }

    private record Match(TradeEntry entry, int entryIndex, int offerIndex, int requiredInput, int outputCount) {
    }

    private record FindResult(Match match, String insufficientMessage, String noStockMessage) {
        private static FindResult match(Match match) {
            return new FindResult(match, null, null);
        }

        private static FindResult none() {
            return new FindResult(null, null, null);
        }
    }

    private record TradeAttemptResult(boolean success, int spentInput, String message) {
        private static TradeAttemptResult ok(int spentInput, String message) {
            return new TradeAttemptResult(true, spentInput, message);
        }

        private static TradeAttemptResult fail(String message) {
            return new TradeAttemptResult(false, 0, message);
        }
    }
}


