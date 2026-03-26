package kmos.addon.settings;

import kmos.addon.trades.TradeEntry;
import kmos.addon.util.AddonLog;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TradeEntryListSetting extends Setting<List<TradeEntry>> {
    private static final Path EXPORT_PATH = FabricLoader.getInstance().getGameDir().resolve("config").resolve("kmos-auto-trade-offers.snbt");

    public TradeEntryListSetting(String name, String description, List<TradeEntry> defaultValue, Consumer<List<TradeEntry>> onChanged, Consumer<Setting<List<TradeEntry>>> onModuleActivated, IVisible visible) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);
    }

    @Override
    protected List<TradeEntry> parseImpl(String str) {
        return new ArrayList<>();
    }

    @Override
    protected boolean isValueValid(List<TradeEntry> value) {
        return true;
    }

    @Override
    protected void resetImpl() {
        value = new ArrayList<>(defaultValue);
    }

    @Override
    public NbtCompound save(NbtCompound tag) {
        NbtList list = new NbtList();
        for (TradeEntry entry : get()) list.add(entry.toTag());
        tag.put("value", list);
        return tag;
    }

    @Override
    public List<TradeEntry> load(NbtCompound tag) {
        get().clear();
        tag.getList("value").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(compound -> get().add(new TradeEntry(compound)));
            }
        });
        return get();
    }

    public void setEntries(List<TradeEntry> entries) {
        set(new ArrayList<>(entries));
    }

    public boolean exportToFile() {
        try {
            Files.createDirectories(EXPORT_PATH.getParent());
            NbtCompound tag = new NbtCompound();
            save(tag);
            Files.writeString(EXPORT_PATH, tag.toString(), StandardCharsets.UTF_8);
            AddonLog.info("Exported AutoTrade offers to " + EXPORT_PATH);
            return true;
        } catch (IOException e) {
            AddonLog.error("Failed to export AutoTrade offers.", e);
            return false;
        }
    }

    public boolean importFromFile() {
        try {
            if (!Files.exists(EXPORT_PATH)) return false;

            String text = Files.readString(EXPORT_PATH, StandardCharsets.UTF_8);
            NbtCompound tag = StringNbtReader.readCompound(text);
            load(tag);
            AddonLog.info("Imported AutoTrade offers from " + EXPORT_PATH);
            return true;
        } catch (Exception e) {
            AddonLog.error("Failed to import AutoTrade offers.", e);
            return false;
        }
    }

    public static void fillTable(GuiTheme theme, WTable table, TradeEntryListSetting setting) {
        table.clear();

        List<TradeEntry> entries = new ArrayList<>(setting.get());
        for (int i = 0; i < entries.size(); i++) {
            int entryIndex = i;
            TradeEntry entry = entries.get(i);

            WCheckbox enabled = table.add(theme.checkbox(entry.enabled.get())).widget();
            enabled.action = () -> {
                entry.enabled.set(enabled.checked);
                setting.setEntries(entries);
            };

            WLabel maxLabel = table.add(theme.label("max " + entry.maxInput.get() + " |")).widget();
            maxLabel.color = theme.textSecondaryColor();

            table.add(theme.item(new ItemStack(entry.inputItem.get()))).widget();
            table.add(theme.label("->")).widget();
            table.add(theme.item(new ItemStack(entry.outputItem.get()))).widget();
            table.add(theme.label(" ")).expandCellX();

            WButton edit = table.add(theme.button(GuiRenderer.EDIT)).widget();
            edit.action = () -> MeteorClient.mc.setScreen(new EditTradeEntryScreen(theme, entry, setting, () -> fillTable(theme, table, setting)));

            WButton up = table.add(theme.button("^")).widget();
            up.action = () -> {
                if (entryIndex <= 0) return;
                TradeEntry moved = entries.remove(entryIndex);
                entries.add(entryIndex - 1, moved);
                setting.setEntries(entries);
                fillTable(theme, table, setting);
            };
            up.tooltip = "Move up";

            WButton down = table.add(theme.button("v")).widget();
            down.action = () -> {
                if (entryIndex >= entries.size() - 1) return;
                TradeEntry moved = entries.remove(entryIndex);
                entries.add(entryIndex + 1, moved);
                setting.setEntries(entries);
                fillTable(theme, table, setting);
            };
            down.tooltip = "Move down";

            WButton duplicate = table.add(theme.button(GuiRenderer.COPY)).widget();
            duplicate.action = () -> {
                TradeEntry copy = new TradeEntry(entry.toTag());
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

        WButton add = table.add(theme.button("New Offer")).expandX().widget();
        add.action = () -> MeteorClient.mc.setScreen(new EditTradeEntryScreen(theme, null, setting, () -> fillTable(theme, table, setting)));

        WConfirmedButton removeAll = table.add(theme.confirmedButton("Remove All", "Confirm")).expandX().widget();
        removeAll.action = () -> {
            entries.clear();
            setting.setEntries(entries);
            fillTable(theme, table, setting);
        };

        table.row();

        WButton export = table.add(theme.button("Export")).expandX().widget();
        export.action = () -> {
            if (setting.exportToFile()) AddonLog.info("AutoTrade export completed.");
            else AddonLog.warn("AutoTrade export failed.");
        };

        WButton importButton = table.add(theme.button("Import")).expandX().widget();
        importButton.action = () -> {
            if (setting.importFromFile()) fillTable(theme, table, setting);
        };
    }

    public static class Builder extends SettingBuilder<Builder, List<TradeEntry>, TradeEntryListSetting> {
        public Builder() {
            super(new ArrayList<>(0));
        }

        @Override
        public TradeEntryListSetting build() {
            return new TradeEntryListSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }

    private static class EditTradeEntryScreen extends EditSystemScreen<TradeEntry> {
        private final TradeEntryListSetting setting;

        public EditTradeEntryScreen(GuiTheme theme, TradeEntry value, TradeEntryListSetting setting, Runnable reload) {
            super(theme, value, reload);
            this.setting = setting;
        }

        @Override
        public TradeEntry create() {
            return new TradeEntry();
        }

        @Override
        public boolean save() {
            List<TradeEntry> entries = new ArrayList<>(setting.get());
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


