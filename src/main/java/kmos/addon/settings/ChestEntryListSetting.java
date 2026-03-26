package kmos.addon.settings;

import kmos.addon.chests.ChestEntry;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.screens.EditSystemScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WConfirmedButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.Settings;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChestEntryListSetting extends Setting<List<ChestEntry>> {
    public ChestEntryListSetting(String name, String description, List<ChestEntry> defaultValue, Consumer<List<ChestEntry>> onChanged, Consumer<Setting<List<ChestEntry>>> onModuleActivated, IVisible visible) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);
    }

    @Override
    protected List<ChestEntry> parseImpl(String str) {
        return new ArrayList<>();
    }

    @Override
    protected boolean isValueValid(List<ChestEntry> value) {
        return true;
    }

    @Override
    protected void resetImpl() {
        value = new ArrayList<>(defaultValue);
    }

    @Override
    public NbtCompound save(NbtCompound tag) {
        NbtList list = new NbtList();
        for (ChestEntry entry : get()) {
            list.add(entry.toTag());
        }
        tag.put("value", list);
        return tag;
    }

    @Override
    public List<ChestEntry> load(NbtCompound tag) {
        get().clear();
        tag.getList("value").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(compound -> get().add(new ChestEntry(compound)));
            }
        });
        return get();
    }

    public void setEntries(List<ChestEntry> entries) {
        set(new ArrayList<>(entries));
    }

    public static void fillTable(GuiTheme theme, WTable table, ChestEntryListSetting setting) {
        table.clear();

        List<ChestEntry> entries = new ArrayList<>(setting.get());
        for (int i = 0; i < entries.size(); i++) {
            int entryIndex = i;
            ChestEntry entry = entries.get(i);

            WCheckbox enabled = table.add(theme.checkbox(entry.enabled.get())).widget();
            enabled.action = () -> {
                entry.enabled.set(enabled.checked);
                setting.setEntries(entries);
            };

            WLabel label = table.add(theme.label(entry.getDisplayName())).expandCellX().widget();
            label.color = theme.textColor();

            WButton edit = table.add(theme.button(GuiRenderer.EDIT)).widget();
            edit.action = () -> MeteorClient.mc.setScreen(new EditChestEntryScreen(theme, entry, setting, () -> fillTable(theme, table, setting)));

            WButton duplicate = table.add(theme.button(GuiRenderer.COPY)).widget();
            duplicate.action = () -> {
                ChestEntry copy = new ChestEntry(entry.toTag());
                entries.add(copy);
                setting.setEntries(entries);
                fillTable(theme, table, setting);
            };
            duplicate.tooltip = "Duplicate";

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

        WButton add = table.add(theme.button("New Chest")).expandX().widget();
        add.action = () -> MeteorClient.mc.setScreen(new EditChestEntryScreen(theme, null, setting, () -> fillTable(theme, table, setting)));

        WConfirmedButton removeAll = table.add(theme.confirmedButton("Remove All", "Confirm")).expandX().widget();
        removeAll.action = () -> {
            entries.clear();
            setting.setEntries(entries);
            fillTable(theme, table, setting);
        };
    }

    public static class Builder extends SettingBuilder<Builder, List<ChestEntry>, ChestEntryListSetting> {
        public Builder() {
            super(new ArrayList<>(0));
        }

        @Override
        public ChestEntryListSetting build() {
            return new ChestEntryListSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }

    private static class EditChestEntryScreen extends EditSystemScreen<ChestEntry> {
        private final ChestEntryListSetting setting;

        public EditChestEntryScreen(GuiTheme theme, ChestEntry value, ChestEntryListSetting setting, Runnable reload) {
            super(theme, value, reload);
            this.setting = setting;
        }

        @Override
        public void initWidgets() {
            super.initWidgets();

            WButton fromCrosshair = add(theme.button("Use Crosshair Block")).expandX().widget();
            fromCrosshair.action = () -> {
                HitResult target = MeteorClient.mc.crosshairTarget;
                if (target instanceof BlockHitResult blockHit) {
                    value.setPos(blockHit.getBlockPos());
                }
            };
        }

        @Override
        public ChestEntry create() {
            ChestEntry entry = new ChestEntry();
            if (MeteorClient.mc.player != null) {
                BlockPos pos = MeteorClient.mc.player.getBlockPos();
                entry.setPos(pos);
            }
            return entry;
        }

        @Override
        public boolean save() {
            List<ChestEntry> entries = new ArrayList<>(setting.get());
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


