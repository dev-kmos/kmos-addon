package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.profiles.ProfilesRegistry;
import kmos.addon.profiles.ProfilesStore;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.tabs.builtin.ModulesTab;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleSetupsDynamic extends Module {
    public enum ApplyMode {
        EnableOnly,
        Strict
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgProfiles = settings.createGroup("Profiles");

    private final Setting<ApplyMode> applyMode = sgGeneral.add(new EnumSetting.Builder<ApplyMode>()
        .name("apply-mode")
        .description("EnableOnly only turns listed modules on. Strict also turns other managed modules off, except always-on entries.")
        .defaultValue(ApplyMode.EnableOnly)
        .build()
    );

    private final Setting<Boolean> restoreOnSwitchOrDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("restore-on-switch+disable")
        .description("Restores previous module states when switching profiles or turning the manager off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Shows debug messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<Module>> alwaysOn = sgGeneral.add(new ModuleListSetting.Builder()
        .name("always-on")
        .description("Modules that must stay enabled while the manager is active.")
        .build()
    );

    private final Setting<Boolean> showProfile1 = sgProfiles.add(new BoolSetting.Builder()
        .name("show-profile-1")
        .description("Shows Profile 1 in the modules list.")
        .defaultValue(true)
        .onChanged(value -> applyHiddenProfiles())
        .build()
    );

    private final Setting<Boolean> showProfile2 = sgProfiles.add(new BoolSetting.Builder()
        .name("show-profile-2")
        .description("Shows Profile 2 in the modules list.")
        .defaultValue(true)
        .onChanged(value -> applyHiddenProfiles())
        .build()
    );

    private final Setting<Boolean> showProfile3 = sgProfiles.add(new BoolSetting.Builder()
        .name("show-profile-3")
        .description("Shows Profile 3 in the modules list.")
        .defaultValue(true)
        .onChanged(value -> applyHiddenProfiles())
        .build()
    );

    private final Setting<Boolean> showProfile4 = sgProfiles.add(new BoolSetting.Builder()
        .name("show-profile-4")
        .description("Shows Profile 4 in the modules list.")
        .defaultValue(false)
        .onChanged(value -> applyHiddenProfiles())
        .build()
    );

    private final Setting<Boolean> showProfile5 = sgProfiles.add(new BoolSetting.Builder()
        .name("show-profile-5")
        .description("Shows Profile 5 in the modules list.")
        .defaultValue(false)
        .onChanged(value -> applyHiddenProfiles())
        .build()
    );

    // Runtime state used to restore module toggles and refresh the GUI when visibility changes.
    private int activeSlot = 0;
    private final Map<Module, Boolean> touchedPrev = new HashMap<>();
    private final Map<Module, Boolean> touchedTarget = new HashMap<>();
    private boolean pendingGuiRefresh = false;
    private boolean refreshingGui = false;

    public ModuleSetupsDynamic() {
        super(KmosAddon.CATEGORY, "module-setups-manager", "Manages up to 5 Meteor module profiles and applies them with one toggle.");
        autoSubscribe = false;
        meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(this);
    }

    public ApplyMode getApplyMode() {
        return applyMode.get();
    }

    public boolean isDebug() {
        return debug.get();
    }

    public boolean isProfileActive(int slot) {
        return activeSlot == slot;
    }

    public void applyHiddenProfiles() {
        List<Module> hidden = new ArrayList<>(Config.get().hiddenModules.get());

        hidden.removeIf(m -> m instanceof SetupProfileModule);

        for (int slot = 1; slot <= ProfilesStore.MAX_PROFILES; slot++) {
            SetupProfileModule pm = ProfilesRegistry.getProfileModule(slot);
            if (pm == null) continue;
            if (!isProfileVisible(slot)) hidden.add(pm);
        }

        Config.get().hiddenModules.set(hidden);
        requestModulesGuiRefresh();
    }

    public void onProfileDeactivated(int slot) {
        if (activeSlot != slot) return;
        if (restoreOnSwitchOrDisable.get()) revertTouched();
        clearTouched();
        activeSlot = 0;
    }

    private boolean isUntouchable(Module m) {
        if (m == null) return true;
        if (m == this) return true;
        return m instanceof SetupProfileModule;
    }

    public void activateProfile(int slot, List<Module> wanted) {
        if (!isActive()) {
            toggle();
            if (!isActive()) return;
        }

        if (restoreOnSwitchOrDisable.get() && activeSlot != 0 && activeSlot != slot) {
            revertTouched();
        }

        clearTouched();

        if (applyMode.get() == ApplyMode.EnableOnly) {
            enableListTracked(wanted);
            enableListTracked(alwaysOn.get());
        } else {
            for (Module m : Modules.get().getAll()) {
                if (m == null) continue;
                if (isUntouchable(m)) continue;

                boolean shouldBeOn = wanted.contains(m) || alwaysOn.get().contains(m);
                setModuleTracked(m, shouldBeOn);
            }
        }

        enforceAlwaysOn();
        activeSlot = slot;

        if (debug.get()) ChatUtils.info("[KMOS] Activated profile slot: %d", slot);
    }

    public void deactivateActiveProfileIfAny() {
        if (activeSlot == 0) return;

        SetupProfileModule old = ProfilesRegistry.getProfileModule(activeSlot);
        if (old != null && old.isActive()) {
            old.toggle();
        } else {
            onProfileDeactivated(activeSlot);
        }
    }

    @Override
    public void onDeactivate() {
        if (restoreOnSwitchOrDisable.get()) revertTouched();
        clearTouched();
        if (activeSlot != 0) {
            SetupProfileModule active = ProfilesRegistry.getProfileModule(activeSlot);
            if (active != null && active.isActive()) active.toggle();
        }
        activeSlot = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        if (!isActive()) return;
        enforceAlwaysOn();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press) return;
        if (Modules.get().isBinding()) return;
        if (handleProfileBind(true, event.key(), event.modifiers())) event.cancel();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press) return;
        if (Modules.get().isBinding()) return;
        if (handleProfileBind(false, event.button(), 0)) event.cancel();
    }

    private void enforceAlwaysOn() {
        for (Module m : alwaysOn.get()) {
            if (m == null) continue;
            if (isUntouchable(m)) continue;
            if (!m.isActive()) setModule(m, true);
        }
    }

    private void enableListTracked(List<Module> list) {
        if (list == null) return;
        for (Module m : list) {
            if (m == null) continue;
            if (isUntouchable(m)) continue;
            setModuleTracked(m, true);
        }
    }

    private void setModuleTracked(Module m, boolean enabled) {
        if (m.isActive() == enabled) return;
        if (restoreOnSwitchOrDisable.get()) recordTouch(m, m.isActive(), enabled);
        setModule(m, enabled);
    }

    private void recordTouch(Module m, boolean prev, boolean target) {
        if (!touchedPrev.containsKey(m)) touchedPrev.put(m, prev);
        touchedTarget.put(m, target);
    }

    private void revertTouched() {
        if (touchedPrev.isEmpty()) return;

        for (Map.Entry<Module, Boolean> entry : touchedPrev.entrySet()) {
            Module m = entry.getKey();
            Boolean prev = entry.getValue();
            Boolean target = touchedTarget.get(m);
            if (m == null || prev == null || target == null) continue;
            if (isUntouchable(m)) continue;
            if (m.isActive() != target) continue;
            setModule(m, prev);
        }

        if (debug.get()) ChatUtils.info("[KMOS] Profile changes reverted.");
    }

    private void clearTouched() {
        touchedPrev.clear();
        touchedTarget.clear();
    }

    private void setModule(Module m, boolean enabled) {
        if (enabled && !m.isActive()) m.toggle();
        else if (!enabled && m.isActive()) m.toggle();
    }

    private boolean handleProfileBind(boolean isKey, int value, int modifiers) {
        for (int slot = 1; slot <= ProfilesStore.MAX_PROFILES; slot++) {
            SetupProfileModule profile = ProfilesRegistry.getProfileModule(slot);
            if (profile == null) continue;
            if (!profile.keybind.isSet()) continue;
            if (profile.keybind.matches(isKey, value, modifiers)) {
                profile.toggle();
                return true;
            }
        }
        return false;
    }


    private boolean isProfileVisible(int slot) {
        return switch (slot) {
            case 1 -> showProfile1.get();
            case 2 -> showProfile2.get();
            case 3 -> showProfile3.get();
            case 4 -> showProfile4.get();
            case 5 -> showProfile5.get();
            default -> true;
        };
    }

    private void requestModulesGuiRefresh() {
        if (mc == null) return;
        if (GuiThemes.get() == null) return;
        if (mc.currentScreen != null && GuiThemes.get().isModulesScreen(mc.currentScreen)) {
            refreshModulesScreen();
            pendingGuiRefresh = false;
        } else {
            pendingGuiRefresh = true;
        }
    }

    private void refreshModulesScreen() {
        if (refreshingGui) return;
        if (GuiThemes.get() == null) return;
        refreshingGui = true;
        meteordevelopment.meteorclient.gui.tabs.Tab tab = Tabs.get(ModulesTab.class);
        if (tab == null) {
            refreshingGui = false;
            return;
        }
        tab.openScreen(GuiThemes.get());
        refreshingGui = false;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (refreshingGui) return;
        if (!pendingGuiRefresh) return;
        if (GuiThemes.get() == null) return;
        if (event.screen != null && GuiThemes.get().isModulesScreen(event.screen)) {
            pendingGuiRefresh = false;
            meteordevelopment.meteorclient.gui.tabs.Tab tab = Tabs.get(ModulesTab.class);
            if (tab == null) return;
            refreshModulesScreen();
            event.cancel();
        }
    }
}



