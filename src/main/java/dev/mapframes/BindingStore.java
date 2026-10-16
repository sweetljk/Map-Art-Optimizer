package dev.mapframes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Persists only local display bindings, never the server's map data or Xaero's cache. */
final class BindingStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private Config config = new Config();

    BindingStore(Path path) throws IOException {
        this.path = path;
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Config loaded = GSON.fromJson(reader, Config.class);
                if (loaded == null || loaded.bindings == null || loaded.format != 1) {
                    throw new IOException("Unsupported or empty binding configuration");
                }
                for (Binding binding : loaded.bindings) {
                    if (binding == null || !binding.valid()) {
                        throw new IOException("Invalid map binding in configuration");
                    }
                }
                config = loaded;
            } catch (RuntimeException error) {
                throw new IOException("Cannot parse map binding configuration", error);
            }
        }
    }

    Binding find(String world, int mapId) {
        for (Binding binding : config.bindings) {
            if (binding.mapId() == mapId && binding.world().equals(world)) return binding;
        }
        return null;
    }

    void put(Binding binding) throws IOException {
        if (!binding.valid()) throw new IllegalArgumentException("Invalid map binding");
        List<Binding> replacement = new ArrayList<>(config.bindings);
        replacement.removeIf(old -> old.mapId() == binding.mapId() && old.world().equals(binding.world()));
        replacement.add(binding);
        save(replacement);
    }

    boolean remove(String world, int mapId) throws IOException {
        List<Binding> replacement = new ArrayList<>(config.bindings);
        if (!replacement.removeIf(old -> old.mapId() == mapId && old.world().equals(world))) return false;
        save(replacement);
        return true;
    }

    private void save(List<Binding> replacement) throws IOException {
        Config next = new Config();
        next.bindings = replacement;
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "xaero-map-frames-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(next), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            config = next;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Binding(String world, int mapId, int minX, int minZ, int size) {
        boolean valid() {
            return world != null && !world.isBlank() && mapId >= 0
                    && (size == 128 || size == 256 || size == 512 || size == 1024 || size == 2048)
                    && minX >= -30_000_000 && minZ >= -30_000_000
                    && (long) minX + size <= 30_000_000 && (long) minZ + size <= 30_000_000;
        }
    }

    private static final class Config {
        int format = 1;
        List<Binding> bindings = new ArrayList<>();
    }
}
