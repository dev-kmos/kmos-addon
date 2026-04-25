package kmos.addon.enchanting;

import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EnchantStorageEntry implements ISerializable<EnchantStorageEntry> {
    public final Settings settings = new Settings();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> enabled = sgGeneral.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Enables tracking for this storage entry.")
        .defaultValue(true)
        .build()
    );

    public final Setting<BlockPos> pos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("pos")
        .description("Block position of the tracked chest, shulker, or other container.")
        .defaultValue(BlockPos.ORIGIN)
        .build()
    );

    private String lastTitle = "";
    private String lastReport = "";
    private final List<StoredEnchantBook> storedBooks = new ArrayList<>();

    public EnchantStorageEntry() {
    }

    public EnchantStorageEntry(NbtCompound tag) {
        fromTag(tag);
    }

    public void setPos(BlockPos value) {
        pos.set(value);
    }

    public String getLastTitle() {
        return lastTitle;
    }

    public String getLastReport() {
        return lastReport;
    }

    public List<StoredEnchantBook> getStoredBooks() {
        return List.copyOf(storedBooks);
    }

    public void updateState(String title, List<StoredEnchantBook> books) {
        lastTitle = title == null ? "" : title;
        storedBooks.clear();
        if (books != null) {
            for (StoredEnchantBook book : books) storedBooks.add(new StoredEnchantBook(book.toTag()));
        }
        rebuildReport();
    }

    public String getDisplayName() {
        BlockPos p = pos.get();
        String suffix = lastTitle.isBlank() ? "unscanned" : lastTitle;
        return p.getX() + " " + p.getY() + " " + p.getZ() + " | " + suffix;
    }

    public int getStoredBookCount() {
        return storedBooks.stream().mapToInt(StoredEnchantBook::getCount).sum();
    }

    private int parseLineCount(String line) {
        int marker = line.indexOf('x');
        if (marker <= 0) return 1;
        try {
            return Integer.parseInt(line.substring(0, marker).trim());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("settings", settings.toTag());
        tag.putString("lastTitle", lastTitle);
        tag.putString("lastReport", lastReport);
        NbtList booksTag = new NbtList();
        for (StoredEnchantBook book : storedBooks) booksTag.add(book.toTag());
        tag.put("storedBooks", booksTag);
        return tag;
    }

    @Override
    public EnchantStorageEntry fromTag(NbtCompound tag) {
        tag.getCompound("settings").ifPresent(settings::fromTag);
        lastTitle = tag.getString("lastTitle").orElse("");
        lastReport = tag.getString("lastReport").orElse("");
        storedBooks.clear();
        tag.getList("storedBooks").ifPresent(list -> {
            for (int i = 0; i < list.size(); i++) {
                list.getCompound(i).ifPresent(bookTag -> storedBooks.add(new StoredEnchantBook(bookTag)));
            }
        });
        if (storedBooks.isEmpty() && !lastReport.isBlank()) {
            Arrays.stream(lastReport.split("\\R"))
                .skip(1)
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .forEach(line -> storedBooks.add(new StoredEnchantBook(java.util.Map.of(), line.replaceFirst("^\\d+x\\s+", ""), parseLineCount(line))));
        }
        rebuildReport();
        return this;
    }

    private void rebuildReport() {
        List<String> lines = new ArrayList<>();
        lines.add(lastTitle == null ? "" : lastTitle);
        for (StoredEnchantBook book : storedBooks) lines.add(book.toLine());
        lastReport = String.join(System.lineSeparator(), lines);
    }
}
