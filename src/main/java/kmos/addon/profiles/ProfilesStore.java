package kmos.addon.profiles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class ProfilesStore {
    public static final int MAX_PROFILES = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<ProfileData>>() {}.getType();

    public static class ProfileData {
        public int slot;                  // 1..MAX_PROFILES
        public String name;               // User-facing profile name
        public List<String> modules;      // Stored as Meteor module names
    }

    private static Path getFilePath() {
        // Store profile data in the addon config directory inside the active Minecraft instance.
        Path mcDir = MinecraftClient.getInstance().runDirectory.toPath();
        return mcDir.resolve("config").resolve("kmos-addon").resolve("profiles.json");
    }

    public static List<ProfileData> load() {
        Path path = getFilePath();
        try {
            if (!Files.exists(path)) return createDefaults();
            String json = Files.readString(path, StandardCharsets.UTF_8);
            List<ProfileData> list = GSON.fromJson(json, LIST_TYPE);
            if (list == null) return createDefaults();
            return normalize(list);
        } catch (Exception e) {
            e.printStackTrace();
            return createDefaults();
        }
    }

    public static boolean save(List<ProfileData> profiles) {
        Path path = getFilePath();
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(normalize(profiles), LIST_TYPE);
            Files.writeString(path, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static List<ProfileData> normalize(List<ProfileData> input) {
        List<ProfileData> out = new ArrayList<>();

        // Rebuild a stable 1..MAX_PROFILES layout even if older data is missing or malformed.
        ProfileData[] bySlot = new ProfileData[MAX_PROFILES + 1];
        int nextSlot = 1;
        for (ProfileData p : input) {
            if (p == null) continue;
            int slot = p.slot;
            if (slot < 1 || slot > MAX_PROFILES) {
                while (nextSlot <= MAX_PROFILES && bySlot[nextSlot] != null) nextSlot++;
                if (nextSlot <= MAX_PROFILES) slot = nextSlot++;
            }
            if (slot >= 1 && slot <= MAX_PROFILES && bySlot[slot] == null) {
                bySlot[slot] = p;
            }
        }

        for (int slot = 1; slot <= MAX_PROFILES; slot++) {
            ProfileData p = bySlot[slot];
            if (p == null) {
                p = new ProfileData();
                p.slot = slot;
                p.name = "Profile " + slot;
                p.modules = new ArrayList<>();
            } else {
                p.slot = slot;
                if (p.name == null || p.name.isBlank()) p.name = "Profile " + slot;
                if (p.modules == null) p.modules = new ArrayList<>();
            }
            out.add(p);
        }

        return out;
    }

    private static List<ProfileData> createDefaults() {
        List<ProfileData> out = new ArrayList<>();
        for (int slot = 1; slot <= MAX_PROFILES; slot++) {
            ProfileData p = new ProfileData();
            p.slot = slot;
            p.name = "Profile " + slot;
            p.modules = new ArrayList<>();
            out.add(p);
        }
        return out;
    }
}


