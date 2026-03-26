package kmos.addon.chests;

import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class ChestEntry implements ISerializable<ChestEntry> {
    public final Settings settings = new Settings();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enables or disables this chest entry.")
        .defaultValue(true)
        .build()
    );

    public final Setting<BlockPos> pos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("pos")
        .description("Block position of the chest to interact with.")
        .defaultValue(new BlockPos(0, 0, 0))
        .build()
    );

    public final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance from the chest before this entry can trigger.")
        .defaultValue(3.0)
        .min(0.0)
        .max(10.0)
        .build()
    );

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Controls whether items are put into the chest or taken from it.")
        .defaultValue(Mode.IN)
        .build()
    );

    public final Setting<Item> item = sgGeneral.add(new ItemSetting.Builder()
        .name("item")
        .description("Legacy single-item field kept only for migrating older configs.")
        .defaultValue(Items.AIR)
        .visible(() -> false)
        .build()
    );

    public final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Items handled by this chest entry.")
        .build()
    );

    public final Setting<Integer> maxStacks = sgGeneral.add(new IntSetting.Builder()
        .name("max-stacks")
        .description("Maximum number of matching stacks allowed on the destination side. Set 0 for no limit.")
        .defaultValue(0)
        .min(0)
        .max(36)
        .build()
    );

    public ChestEntry() {
    }

    public ChestEntry(NbtCompound tag) {
        fromTag(tag);
    }

    public void setPos(BlockPos value) {
        pos.set(value);
    }

    public String getDisplayName() {
        BlockPos p = pos.get();
        String itemName = getItemsDisplay();
        String limit = maxStacks.get() <= 0 ? "no-limit" : (maxStacks.get() + " stacks");
        return String.format("%s | %d %d %d | r=%.1f | %s | %s",
            mode.get(), p.getX(), p.getY(), p.getZ(), range.get(), itemName, limit);
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("settings", settings.toTag());
        return tag;
    }

    @Override
    public ChestEntry fromTag(NbtCompound tag) {
        tag.getCompound("settings").ifPresent(settings::fromTag);
        if (items.get().isEmpty() && item.get() != Items.AIR) {
            List<Item> migrated = new ArrayList<>();
            migrated.add(item.get());
            items.set(migrated);
        }
        return this;
    }

    public List<Item> getItems() {
        if (!items.get().isEmpty()) return items.get();
        if (item.get() == Items.AIR) return List.of();
        return List.of(item.get());
    }

    private String getItemsDisplay() {
        List<Item> configured = getItems();
        if (configured.isEmpty()) return "(none)";
        if (configured.size() == 1) return configured.getFirst().toString();
        if (configured.size() == 2) return configured.get(0) + ", " + configured.get(1);
        return configured.get(0) + ", " + configured.get(1) + ", +" + (configured.size() - 2);
    }

    public enum Mode {
        IN,
        OUT;

        @Override
        public String toString() {
            return this == IN ? "Put Into Chest" : "Take From Chest";
        }
    }
}


