package kmos.addon.profiles;

import kmos.addon.modules.ModuleSetupsDynamic;
import kmos.addon.modules.SetupProfileModule;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProfilesRegistry {
    private static final List<ProfilesStore.ProfileData> profiles = new ArrayList<>();
    private static final Map<Integer, SetupProfileModule> slotToModule = new HashMap<>();

    public static void initAndRegisterModules() {
        profiles.clear();
        slotToModule.clear();

        List<ProfilesStore.ProfileData> loaded = ProfilesStore.load();
        profiles.addAll(loaded);

        // Register all fixed profile modules so the manager and binds can resolve them consistently.
        for (ProfilesStore.ProfileData p : profiles) {
            SetupProfileModule m = new SetupProfileModule(p.slot);
            slotToModule.put(p.slot, m);
            Modules.get().add(m);
        }

        ModuleSetupsDynamic manager = Modules.get().get(ModuleSetupsDynamic.class);
        if (manager != null) manager.applyHiddenProfiles();
    }

    public static List<ProfilesStore.ProfileData> getProfiles() {
        return profiles;
    }

    public static SetupProfileModule getProfileModule(int slot) {
        return slotToModule.get(slot);
    }

    public static ProfilesStore.ProfileData getProfileData(int slot) {
        for (ProfilesStore.ProfileData p : profiles) {
            if (p.slot == slot) return p;
        }
        return null;
    }

    public static void updateProfileName(int slot, String newName) {
        ProfilesStore.ProfileData p = getProfileData(slot);
        if (p == null) return;
        p.name = (newName == null || newName.isBlank()) ? p.name : newName.trim();
        ProfilesStore.save(profiles);
    }

    public static void updateProfileModules(int slot, List<String> moduleNames) {
        ProfilesStore.ProfileData p = getProfileData(slot);
        if (p == null) return;
        p.modules = (moduleNames == null) ? new ArrayList<>() : new ArrayList<>(moduleNames);
        ProfilesStore.save(profiles);
    }

    public static void resetProfile(int slot) {
        ProfilesStore.ProfileData p = getProfileData(slot);
        if (p == null) return;
        p.name = "Profile " + slot;
        p.modules = new ArrayList<>();
        ProfilesStore.save(profiles);
    }
}


