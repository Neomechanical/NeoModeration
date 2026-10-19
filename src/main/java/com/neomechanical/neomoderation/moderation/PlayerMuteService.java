package com.neomechanical.neomoderation.moderation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoModeration's own mutes, kept in mutes.yml. Changes are written on a
 * background thread, many at once as one write, so a raid that mutes dozens of
 * players never waits on the disk; {@link #close()} writes what is left.
 */
public final class PlayerMuteService implements AutoCloseable {
    private final ConcurrentHashMap<UUID, Long> mutedUntilMillis = new ConcurrentHashMap<>();
    private final File storageFile;
    private final Object diskLock = new Object();
    private final AtomicBoolean writeQueued = new AtomicBoolean();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "NeoModeration-mutes");
        thread.setDaemon(true);
        return thread;
    });

    public PlayerMuteService(JavaPlugin plugin) {
        this(new File(plugin.getDataFolder(), "mutes.yml"));
    }

    PlayerMuteService(File storageFile) {
        this.storageFile = storageFile;
        load();
    }

    public void mute(UUID playerId, int durationSeconds) {
        long durationMs = Math.max(1L, durationSeconds) * 1000L;
        mutedUntilMillis.put(playerId, System.currentTimeMillis() + durationMs);
        save();
    }

    public boolean isMuted(UUID playerId) {
        Long until = mutedUntilMillis.get(playerId);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            if (mutedUntilMillis.remove(playerId, until)) {
                save();
            }
            return false;
        }
        return true;
    }

    public int remainingSeconds(UUID playerId) {
        Long until = mutedUntilMillis.get(playerId);
        if (until == null) {
            return 0;
        }
        long remainingMs = until - System.currentTimeMillis();
        if (remainingMs <= 0) {
            if (mutedUntilMillis.remove(playerId, until)) {
                save();
            }
            return 0;
        }
        return (int) Math.ceil(remainingMs / 1000.0);
    }

    public void unmute(UUID playerId) {
        if (mutedUntilMillis.remove(playerId) != null) {
            save();
        }
    }

    private void load() {
        if (storageFile == null || !storageFile.exists()) {
            return;
        }
        synchronized (diskLock) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
            long now = System.currentTimeMillis();
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long until = yaml.getLong(key, 0L);
                    if (until > now) {
                        mutedUntilMillis.put(id, until);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed UUID keys.
                }
            }
        }
    }

    /** Writes any change still waiting; called when the plugin stops. */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                write();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            write();
        }
    }

    private void save() {
        if (storageFile == null || !writeQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.execute(() -> {
                writeQueued.set(false);
                write();
            });
        } catch (RejectedExecutionException stopped) {
            writeQueued.set(false);
            write();
        }
    }

    private void write() {
        if (storageFile == null) {
            return;
        }
        synchronized (diskLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : mutedUntilMillis.entrySet()) {
                if (entry.getValue() > now) {
                    yaml.set(entry.getKey().toString(), entry.getValue());
                }
            }
            try {
                File parent = storageFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Path target = storageFile.toPath();
                Path temp = target.resolveSibling(storageFile.getName() + ".tmp");
                yaml.save(temp.toFile());
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                try {
                    yaml.save(storageFile);
                } catch (IOException ignoredAgain) {
                    // Best-effort persistence.
                }
            }
        }
    }
}
