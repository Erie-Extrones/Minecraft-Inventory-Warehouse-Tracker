package dev.warehouse.storage;

import com.google.gson.Gson;
import dev.warehouse.WarehouseClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** A single JSON file holding one value of type T, with atomic writes and debounced saving. */
public final class JsonStore<T> {
    private final Gson gson;
    private final Type type;
    private final Supplier<T> defaultSupplier;
    private Path file;
    private T value;
    private volatile boolean dirty;
    private long dirtySinceMs;

    public JsonStore(Gson gson, Type type, Supplier<T> defaultSupplier) {
        this.gson = gson;
        this.type = type;
        this.defaultSupplier = defaultSupplier;
        this.value = defaultSupplier.get();
    }

    public synchronized void bind(Path file) {
        this.file = file;
        this.dirty = false;
        load();
    }

    public synchronized void unbind() {
        flush();
        this.file = null;
        this.value = defaultSupplier.get();
    }

    public boolean isBound() {
        return file != null;
    }

    public Path file() {
        return file;
    }

    public T get() {
        return value;
    }

    public synchronized void set(T newValue) {
        this.value = newValue != null ? newValue : defaultSupplier.get();
        markDirty();
    }

    public synchronized void markDirty() {
        if (!dirty) dirtySinceMs = System.currentTimeMillis();
        dirty = true;
    }

    /** Save if dirty for at least debounceMs. Call every tick. */
    public synchronized void tick(long debounceMs) {
        if (dirty && file != null && System.currentTimeMillis() - dirtySinceMs >= debounceMs) {
            flush();
        }
    }

    public synchronized void flush() {
        if (!dirty || file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                gson.toJson(value, type, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            WarehouseClient.LOGGER.error("Failed to save {}", file, e);
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            value = defaultSupplier.get();
            return;
        }
        try (Reader r = Files.newBufferedReader(file)) {
            T loaded = gson.fromJson(r, type);
            value = loaded != null ? loaded : defaultSupplier.get();
        } catch (Exception e) {
            WarehouseClient.LOGGER.error("Failed to load {}, starting empty (backup written)", file, e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            value = defaultSupplier.get();
        }
    }
}
