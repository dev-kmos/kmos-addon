package kmos.addon.settings;

import kmos.addon.enchanting.EnchantStorageEntry;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.screens.EditSystemScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WConfirmedButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EnchantStorageEntryListSetting extends Setting<List<EnchantStorageEntry>> {
    public EnchantStorageEntryListSetting(String name, String description, List<EnchantStorageEntry> defaultValue, Consumer<List<EnchantStorageEntry>> onChanged, Consumer<Setting<List<EnchantStorageEntry>>> onModuleActivated, IVisible visible) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);
    }

    @Override
    protected List<EnchantStorageEntry> parseImpl(String str) {
        return new ArrayList<>();
    }

    @Override
    protected boolean isValueValid(List<EnchantStorageEntry> value) {
        return true;
    }

    @Override
    protected void resetImpl() {
        value = new ArrayList<>(defaultValue);
    }

    @Override
    public NbtCompound save(NbtCompound tag) {
        NbtList list = new NbtList();
        for (EnchantStorageEntry entry : get()) list.add(entry.toTag());
        tag.put("value", list);
        return tag;
    }

    @Override
    public List<EnchantStorageEntry> load(NbtCompound tag) {
        get().clear();
        tag.getList("value").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(compound -> get().add(new EnchantStorageEntry(compound)));
            }
        });
        return get();
    }

    public void setEntries(List<EnchantStorageEntry> entries) {
        set(new ArrayList<>(entries));
    }

    public static void fillTable(GuiTheme theme, WTable table, EnchantStorageEntryListSetting setting) {
        table.clear();

        List<EnchantStorageEntry> entries = new ArrayList<>(setting.get());
        for (int i = 0; i < entries.size(); i++) {
            int entryIndex = i;
            EnchantStorageEntry entry = entries.get(i);

            WCheckbox enabled = table.add(theme.checkbox(entry.enabled.get())).widget();
            enabled.action = () -> {
                entry.enabled.set(enabled.checked);
                setting.setEntries(entries);
            };

            WHorizontalList booksCell = table.add(theme.horizontalList()).padRight(6).widget();
            booksCell.spacing = 2;
            booksCell.add(theme.item(new ItemStack(Items.ENCHANTED_BOOK)));
            WLabel countLabel = booksCell.add(theme.label("x" + entry.getStoredBookCount())).widget();
            if (entry.getStoredBookCount() <= 0) countLabel.color = new Color(255, 96, 96);
            else if (entry.getStoredBookCount() == 1) countLabel.color = new Color(255, 220, 96);
            else countLabel.color = theme.textColor();
            WLabel label = table.add(theme.label(entry.getDisplayName())).expandCellX().widget();
            label.color = theme.textColor();

            WButton edit = table.add(theme.button(GuiRenderer.EDIT)).widget();
            edit.action = () -> MeteorClient.mc.setScreen(new EditEnchantStorageEntryScreen(theme, entry, setting, () -> fillTable(theme, table, setting)));

            WButton duplicate = table.add(theme.button(GuiRenderer.COPY)).widget();
            duplicate.action = () -> {
                EnchantStorageEntry copy = new EnchantStorageEntry(entry.toTag());
                entries.add(copy);
                setting.setEntries(entries);
                fillTable(theme, table, setting);
            };

            WMinus remove = table.add(theme.minus()).widget();
            remove.action = () -> {
                entries.remove(entryIndex);
                setting.setEntries(entries);
                fillTable(theme, table, setting);
            };

            table.row();
        }

        if (!entries.isEmpty()) {
            table.add(theme.horizontalSeparator()).expandX();
            table.row();
        }

        WButton add = table.add(theme.button("New Storage")).expandX().widget();
        add.action = () -> MeteorClient.mc.setScreen(new EditEnchantStorageEntryScreen(theme, null, setting, () -> fillTable(theme, table, setting)));

        WConfirmedButton removeAll = table.add(theme.confirmedButton("Remove All", "Confirm")).expandX().widget();
        removeAll.action = () -> {
            entries.clear();
            setting.setEntries(entries);
            fillTable(theme, table, setting);
        };
    }

    public static class Builder extends SettingBuilder<Builder, List<EnchantStorageEntry>, EnchantStorageEntryListSetting> {
        public Builder() {
            super(new ArrayList<>(0));
        }

        @Override
        public EnchantStorageEntryListSetting build() {
            return new EnchantStorageEntryListSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }

    private static class EditEnchantStorageEntryScreen extends EditSystemScreen<EnchantStorageEntry> {
        private final EnchantStorageEntryListSetting setting;

        public EditEnchantStorageEntryScreen(GuiTheme theme, EnchantStorageEntry value, EnchantStorageEntryListSetting setting, Runnable reload) {
            super(theme, value, reload);
            this.setting = setting;
        }

        @Override
        public void initWidgets() {
            super.initWidgets();

            WButton fromCrosshair = add(theme.button("Use Crosshair Block")).expandX().widget();
            fromCrosshair.action = () -> {
                HitResult target = MeteorClient.mc.crosshairTarget;
                if (target instanceof BlockHitResult blockHit) value.setPos(blockHit.getBlockPos());
            };

            add(theme.horizontalSeparator()).expandX();

            if (!value.getLastTitle().isBlank()) {
                add(theme.label("Stored state: " + value.getLastTitle())).expandX();
            } else {
                add(theme.label("Stored state: unscanned")).expandX();
            }

            String report = value.getLastReport();
            if (report == null || report.isBlank()) {
                add(theme.label("No enchanted books recorded yet.")).expandX();
                return;
            }

            String[] lines = report.split("\\R");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) add(theme.label(lines[i])).expandX();
            }
        }

        @Override
        public EnchantStorageEntry create() {
            EnchantStorageEntry entry = new EnchantStorageEntry();
            if (MeteorClient.mc.player != null) {
                BlockPos pos = MeteorClient.mc.player.getBlockPos();
                entry.setPos(pos);
            }
            return entry;
        }

        @Override
        public boolean save() {
            List<EnchantStorageEntry> entries = new ArrayList<>(setting.get());
            if (isNew) entries.add(value);
            setting.setEntries(entries);
            return true;
        }

        @Override
        public Settings getSettings() {
            return value.settings;
        }
    }
}
