package kmos.addon;

import com.mojang.logging.LogUtils;
import kmos.addon.modules.AutoAntiAfk;
import kmos.addon.modules.AutoChestTransfer;
import kmos.addon.modules.AutoEnchantTableBatch;
import kmos.addon.modules.AutoTrade;
import kmos.addon.modules.AutoVillagerClick;
import kmos.addon.modules.AutoShulkerPack;
import kmos.addon.modules.BaritoneMaterialDebug;
import kmos.addon.modules.EnchantContainerAnalyzer;
import kmos.addon.modules.ElytraAutoSwap;
import kmos.addon.modules.MasterMute;
import kmos.addon.modules.ModuleSetupsDynamic;
import kmos.addon.hud.MasterMuteHud;
import kmos.addon.profiles.ProfilesRegistry;
import kmos.addon.settings.ChestEntryListSetting;
import kmos.addon.settings.EnchantStorageEntryListSetting;
import kmos.addon.settings.ActionButtonSetting;
import kmos.addon.settings.TradeEntryListSetting;
import kmos.addon.util.AddonLog;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class KmosAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("KMOS Addon");
    public static final HudGroup HUD_GROUP = new HudGroup("KMOS Addon");
    @Override
    public void onInitialize() {
        AddonLog.info("KMOS ADDON LOADED");

        SettingsWidgetFactory.registerCustomFactory(ChestEntryListSetting.class, theme ->
            (table, setting) -> ChestEntryListSetting.fillTable(theme, table, (ChestEntryListSetting) setting)
        );
        SettingsWidgetFactory.registerCustomFactory(TradeEntryListSetting.class, theme ->
            (table, setting) -> TradeEntryListSetting.fillTable(theme, table, (TradeEntryListSetting) setting)
        );
        SettingsWidgetFactory.registerCustomFactory(EnchantStorageEntryListSetting.class, theme ->
            (table, setting) -> EnchantStorageEntryListSetting.fillTable(theme, table, (EnchantStorageEntryListSetting) setting)
        );
        SettingsWidgetFactory.registerCustomFactory(ActionButtonSetting.class, theme ->
            (table, setting) -> {
                var button = table.add(theme.button(((ActionButtonSetting) setting).getButtonText())).expandX().widget();
                button.action = ((ActionButtonSetting) setting)::press;
            }
        );

        Modules.get().add(new ElytraAutoSwap());
        Modules.get().add(new AutoAntiAfk());
        Modules.get().add(new AutoChestTransfer());
        Modules.get().add(new AutoTrade());
        Modules.get().add(new AutoVillagerClick());
        Modules.get().add(new AutoShulkerPack());
        Modules.get().add(new BaritoneMaterialDebug());
        Modules.get().add(new EnchantContainerAnalyzer());
        Modules.get().add(new AutoEnchantTableBatch());
        Modules.get().add(new ModuleSetupsDynamic());
        Modules.get().add(new MasterMute());
        Modules.get().sortModules();

        // Keep KMOS modules visible even if an older config hid the category entries.
        List<meteordevelopment.meteorclient.systems.modules.Module> hidden = new ArrayList<>(meteordevelopment.meteorclient.systems.config.Config.get().hiddenModules.get());
        hidden.removeIf(module -> module != null && module.category == CATEGORY);
        meteordevelopment.meteorclient.systems.config.Config.get().hiddenModules.set(hidden);
        AddonLog.info("Registered KMOS modules and cleared hidden entries for KMOS category.");

        ProfilesRegistry.initAndRegisterModules();

        Hud.get().register(MasterMuteHud.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "kmos.addon";
    }
}



