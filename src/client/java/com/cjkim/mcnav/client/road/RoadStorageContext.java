package com.cjkim.mcnav.client.road;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class RoadStorageContext {
    private static final RoadStorageContext UNBOUND = new RoadStorageContext(
            "未进入世界",
            "unbound",
            false
    );

    private final String displayName;
    private final String storageKey;
    private final boolean bound;

    private RoadStorageContext(String displayName, String storageKey, boolean bound) {
        this.displayName = displayName;
        this.storageKey = storageKey;
        this.bound = bound;
    }

    public static RoadStorageContext unbound() {
        return UNBOUND;
    }

    public static RoadStorageContext resolve(Minecraft client) {
        if (client == null) {
            return UNBOUND;
        }

        var singleplayerServer = client.getSingleplayerServer();
        if (singleplayerServer != null) {
            Path worldPath = singleplayerServer.getWorldPath(LevelResource.ROOT);
            String worldName = worldPath == null ? "单人存档" : safeLeafName(worldPath);
            String source = "singleplayer|" + normalizeSource(worldPath == null ? "unknown" : worldPath.toAbsolutePath().normalize().toString());
            return new RoadStorageContext(
                    "单人存档 · " + worldName,
                    "singleplayer-" + slugify(worldName) + "-" + shortHash(source),
                    true
            );
        }

        var currentServer = client.getCurrentServer();
        if (currentServer != null) {
            String address = currentServer.ip == null || currentServer.ip.isBlank() ? "unknown" : currentServer.ip.trim();
            String serverName = currentServer.name == null || currentServer.name.isBlank() ? address : currentServer.name.trim();
            String prefix = currentServer.isLan() ? "本地服务器" : "联机服务器";
            String source = "server|" + normalizeSource(address);
            return new RoadStorageContext(
                    prefix + " · " + serverName,
                    "server-" + slugify(serverName) + "-" + shortHash(source),
                    true
            );
        }

        return UNBOUND;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public boolean isBound() {
        return bound;
    }

    public Path resolveDataFile(Path baseDirectory) {
        return baseDirectory.resolve("instances").resolve(storageKey).resolve("roads.json");
    }

    private static String safeLeafName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "单人存档";
        }
        String raw = fileName.toString().trim();
        return raw.isEmpty() ? "单人存档" : raw;
    }

    private static String normalizeSource(String input) {
        return input == null ? "" : input.replace('\\', '/').trim();
    }

    private static String slugify(String input) {
        String normalized = normalizeSource(input).toLowerCase(Locale.ROOT);
        String slug = normalized
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug.isBlank() ? "instance" : slug;
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(normalizeSource(input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 8 && i < hash.length; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(normalizeSource(input).hashCode());
        }
    }
}
