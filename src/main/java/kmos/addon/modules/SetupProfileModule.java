package kmos.addon.modules;

import kmos.addon.KmosAddon;
import kmos.addon.profiles.ProfilesRegistry;
import kmos.addon.profiles.ProfilesStore;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.ArrayList;
import java.util.List;

public class SetupProfileModule extends Module {
    private final int slot;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> profileName = sgGeneral.add(new StringSetting.Builder()
        .name("profile-name")
        .description("Display name shown for this profile.")
        .defaultValue("Profile")
        .build()
    );

    private final Setting<List<Module>> modulesInProfile = sgGeneral.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Modules controlled by this profile. In Strict mode, other managed modules are turned off.")
        .build()
    );

    // Cache the last known name so profile-name updates can be persisted without onChanged init issues.
    private String lastNameCache = null;
    private boolean applied = false;

    public SetupProfileModule(int slot) {
        super(KmosAddon.CATEGORY, "profile-" + slot, "Toggles this saved module profile through the setups manager.");
        this.slot = slot;

        ProfilesStore.ProfileData data = ProfilesRegistry.getProfileData(slot);

        if (data != null) {
            if (data.name != null) profileName.set(data.name);

            List<Module> resolved = new ArrayList<>();
            if (data.modules != null) {
                for (String modName : data.modules) {
                    Module m = Modules.get().get(modName);
                    if (m != null) resolved.add(m);
                }
            }
            modulesInProfile.set(resolved);
        } else {
            profileName.set("Profile " + slot);
        }

        lastNameCache = profileName.get();
    }

    @Override
    public void onActivate() {
        applied = false;
        applyNow();
    }

    @Override
    public void onDeactivate() {
        applied = false;
        ModuleSetupsDynamic manager = Modules.get().get(ModuleSetupsDynamic.class);
        if (manager != null) manager.onProfileDeactivated(slot);
    }

    @Override
    public String getInfoString() {
        return profileName.get();
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        // Persist profile-name changes from the GUI without relying on onChanged during module init.
        String now = profileName.get();
        if (now != null && lastNameCache != null && !now.equals(lastNameCache)) {
            ProfilesRegistry.updateProfileName(slot, now);
            lastNameCache = now;
        } else if (lastNameCache == null) {
            lastNameCache = now;
        }

        if (isActive() && !applied) applyNow();
    }

    private void applyNow() {
        ModuleSetupsDynamic manager = Modules.get().get(ModuleSetupsDynamic.class);

        if (manager == null) {
            ChatUtils.info("[KMOS] Manager not found (module-setups-manager).");
            toggle();
            return;
        }

        if (!manager.isProfileActive(slot)) manager.deactivateActiveProfileIfAny();

        // Save first so profile application always uses the latest GUI state.
        saveProfile();

        manager.activateProfile(slot, modulesInProfile.get());
        applied = true;

        if (manager.isDebug()) ChatUtils.info("[KMOS] Applied profile slot: %d", slot);
    }

    private void saveProfile() {
        ProfilesRegistry.updateProfileName(slot, profileName.get());
        ProfilesRegistry.updateProfileModules(slot, toNameList(modulesInProfile.get()));
        lastNameCache = profileName.get();
    }

    private static List<String> toNameList(List<Module> modules) {
        List<String> out = new ArrayList<>();
        if (modules == null) return out;

        for (Module m : modules) {
            if (m != null) out.add(m.name);
        }
        return out;
    }
}


