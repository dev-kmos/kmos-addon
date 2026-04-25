package kmos.addon.enchanting;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class StoredEnchantBook {
    private final Map<String, Integer> enchants = new TreeMap<>();
    private String displayName;
    private int count;

    public StoredEnchantBook(Map<String, Integer> enchants, String displayName, int count) {
        if (enchants != null) this.enchants.putAll(enchants);
        this.displayName = displayName == null ? "" : displayName;
        this.count = Math.max(0, count);
    }

    public StoredEnchantBook(NbtCompound tag) {
        fromTag(tag);
    }

    public Map<String, Integer> getEnchants() {
        return Map.copyOf(enchants);
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getCount() {
        return count;
    }

    public String getSignature() {
        return enchants.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(";"));
    }

    public String toLine() {
        return count + "x " + displayName;
    }

    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putString("displayName", displayName);
        tag.putInt("count", count);

        NbtList enchantsTag = new NbtList();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            NbtCompound enchantTag = new NbtCompound();
            enchantTag.putString("id", entry.getKey());
            enchantTag.putInt("level", entry.getValue());
            enchantsTag.add(enchantTag);
        }
        tag.put("enchants", enchantsTag);
        return tag;
    }

    public StoredEnchantBook fromTag(NbtCompound tag) {
        enchants.clear();
        displayName = tag.getString("displayName").orElse("");
        count = tag.getInt("count").orElse(0);
        tag.getList("enchants").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(enchantTag -> {
                    String id = enchantTag.getString("id").orElse("");
                    int level = enchantTag.getInt("level").orElse(0);
                    if (!id.isBlank() && level > 0) enchants.put(id, level);
                });
            }
        });
        return this;
    }

    public static Map<String, Integer> copyEnchants(Map<String, Integer> enchants) {
        return new LinkedHashMap<>(enchants);
    }
}
