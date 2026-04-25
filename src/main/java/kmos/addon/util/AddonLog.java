package kmos.addon.util;

import kmos.addon.KmosAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class AddonLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("kmos-addon.log");
    private static final Pattern BLOCK_POS = Pattern.compile("BlockPos\\{x=-?\\d+, y=-?\\d+, z=-?\\d+}");
    private static final Pattern DECIMAL_VEC = Pattern.compile("\\(-?\\d+(?:\\.\\d+)?,\\s*-?\\d+(?:\\.\\d+)?,\\s*-?\\d+(?:\\.\\d+)?\\)");
    private static final Pattern XYZ_ASSIGNMENTS = Pattern.compile("(x|y|z)=-?\\d+(?:\\.\\d+)?");
    private static final Pattern PLAIN_TRIPLE = Pattern.compile("\\b-?\\d+\\s+-?\\d+\\s+-?\\d+\\b");
    private static final Pattern FILE_PATH = Pattern.compile("([A-Za-z]:[\\\\/][^\\s\"'<>]+|/[^\\s\"'<>]+)");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)\\b(password|passwd|token|secret|api[_-]?key)\\b\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{10,}");
    private static final Pattern WEBHOOK_URL = Pattern.compile("https?://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/[^\\s\"'<>]+");
    private static boolean initialized = false;

    private AddonLog() {
    }

    public static void info(String msg) {
        String sanitized = sanitize(msg);
        KmosAddon.LOG.info(sanitized);
        write("INFO", sanitized, null);
    }

    public static void warn(String msg) {
        String sanitized = sanitize(msg);
        KmosAddon.LOG.warn(sanitized);
        write("WARN", sanitized, null);
    }

    public static void error(String msg, Throwable t) {
        String sanitized = sanitize(msg);
        if (t == null) {
            KmosAddon.LOG.error(sanitized);
        } else {
            KmosAddon.LOG.error(sanitized + " (" + sanitize(t.toString()) + ")");
        }
        write("ERROR", sanitized, t);
    }

    private static void write(String level, String msg, Throwable t) {
        synchronized (LOCK) {
            try {
                initIfNeeded();
                StringBuilder line = new StringBuilder()
                    .append('[').append(LocalDateTime.now().format(TS)).append("] ")
                    .append('[').append(level).append("] ")
                    .append(msg)
                    .append(System.lineSeparator());

                if (t != null) {
                    line.append(sanitize(t.toString())).append(System.lineSeparator());
                    for (StackTraceElement ste : t.getStackTrace()) {
                        line.append("    at ").append(ste).append(System.lineSeparator());
                    }
                }

                Files.writeString(FILE, line.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Keep runtime safe even if file logging fails.
            }
        }
    }

    private static void initIfNeeded() throws IOException {
        if (initialized) return;
        Files.createDirectories(FILE.getParent());
        String header = "===== KMOS Addon Log started " + LocalDateTime.now().format(TS) + " =====" + System.lineSeparator();
        Files.writeString(FILE, header, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        initialized = true;
    }

    private static String sanitize(String msg) {
        if (msg == null || msg.isBlank()) return msg;

        String sanitized = msg;
        sanitized = BLOCK_POS.matcher(sanitized).replaceAll("BlockPos{x=<redacted>, y=<redacted>, z=<redacted>}");
        sanitized = DECIMAL_VEC.matcher(sanitized).replaceAll("(<redacted>, <redacted>, <redacted>)");
        sanitized = XYZ_ASSIGNMENTS.matcher(sanitized).replaceAll("$1=<redacted>");
        sanitized = sanitized.replaceAll("pos=-?\\d+(?:\\.\\d+)?,\\s*-?\\d+(?:\\.\\d+)?,\\s*-?\\d+(?:\\.\\d+)?", "pos=<redacted>");
        sanitized = PLAIN_TRIPLE.matcher(sanitized).replaceAll("<redacted> <redacted> <redacted>");
        sanitized = WEBHOOK_URL.matcher(sanitized).replaceAll("<webhook>");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer <redacted>");
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1=<redacted>");
        sanitized = EMAIL.matcher(sanitized).replaceAll("<email>");
        sanitized = UUID.matcher(sanitized).replaceAll("<uuid>");
        sanitized = IPV4.matcher(sanitized).replaceAll("<ip>");
        sanitized = FILE_PATH.matcher(sanitized).replaceAll("<path>");
        return sanitized;
    }
}


