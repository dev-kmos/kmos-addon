package kmos.addon.trades;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;

public class TradeEntry implements ISerializable<TradeEntry> {
    public final Settings settings = new Settings();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Whether this trade entry is active.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Item> inputItem = sgGeneral.add(new ItemSetting.Builder()
        .name("input-item")
        .description("Item you give to the villager.")
        .defaultValue(Items.EMERALD)
        .build()
    );

    public final Setting<Integer> maxInput = sgGeneral.add(new IntSetting.Builder()
        .name("max-input")
        .description("Max total amount of input item to spend per villager window.")
        .defaultValue(64)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    public final Setting<Item> outputItem = sgGeneral.add(new ItemSetting.Builder()
        .name("output-item")
        .description("Item you want to receive from the villager.")
        .defaultValue(Items.EMERALD)
        .build()
    );

    public TradeEntry() {
    }

    public TradeEntry(NbtCompound tag) {
        fromTag(tag);
    }

    public String getDisplayName() {
        String input = inputItem.get() == Items.AIR ? "(none)" : inputItem.get().toString();
        String output = outputItem.get() == Items.AIR ? "(none)" : outputItem.get().toString();
        String max = Integer.toString(maxInput.get());
        return input + " -> " + output + " | max " + max;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("settings", settings.toTag());
        return tag;
    }

    @Override
    public TradeEntry fromTag(NbtCompound tag) {
        tag.getCompound("settings").ifPresent(settings::fromTag);
        return this;
    }
}


