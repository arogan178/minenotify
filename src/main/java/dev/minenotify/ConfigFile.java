package dev.minenotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class ConfigFile {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public int port = 47653;
    public String token = newToken();
    public Settings settings = new Settings();

    public static ConfigFile load(Path path) throws IOException {
        ConfigFile config;
        if (!Files.exists(path)) {
            config = new ConfigFile();
        } else {
            try {
                if (Files.size(path) > 65536) throw new IllegalArgumentException("Configuration too large");
                config = JSON.fromJson(Files.readString(path), ConfigFile.class);
                if (config == null) throw new IllegalArgumentException("Empty configuration");
                if (config.port < 1024 || config.port > 65535) config.port = 47653;
                if (config.token == null || !config.token.matches("[a-fA-F0-9]{64,128}")) config.token = newToken();
                if (config.settings == null) config.settings = new Settings();
                config.settings.normalize();
            } catch (RuntimeException e) {
                Files.copy(path, path.resolveSibling(path.getFileName() + ".invalid-" + System.currentTimeMillis()));
                config = new ConfigFile();
            }
        }
        config.save(path);
        return config;
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "minenotify-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.writeString(temp, JSON.toJson(this));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
