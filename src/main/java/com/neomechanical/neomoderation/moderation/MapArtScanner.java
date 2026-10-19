package com.neomechanical.neomoderation.moderation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapPalette;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Everything the map-art check needs from a filled map: its id, its pixels and
 * a picture to send. Reflection keeps the plugin compiling against the old
 * Bukkit API while it runs on modern Paper.
 *
 * <p>Pixels come from the server's memory when possible, so a map drawn a
 * moment ago is checked at once; the world's saved map file is the fallback,
 * and it only changes when the world saves.</p>
 */
public final class MapArtScanner {
    static final int SIDE = 128;
    static final int PIXELS = SIDE * SIDE;

    private MapArtScanner() {
    }

    public static Material resolveFilledMapMaterial() {
        try {
            return Material.valueOf("FILLED_MAP");
        } catch (IllegalArgumentException ignored) {
            return Material.MAP;
        }
    }

    public static ItemStack mainHand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInMainHand");
            return (ItemStack) method.invoke(inventory);
        } catch (ReflectiveOperationException ignored) {
            return inventory.getItemInHand();
        }
    }

    public static ItemStack offHand(PlayerInventory inventory) {
        try {
            Method method = inventory.getClass().getMethod("getItemInOffHand");
            return (ItemStack) method.invoke(inventory);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Integer resolveMapId(MapMeta meta) {
        Object view = mapView(meta);
        if (view != null) {
            try {
                Object id = view.getClass().getMethod("getId").invoke(view);
                if (id instanceof Number number) {
                    return number.intValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the legacy id below.
            }
        }
        try {
            Object id = meta.getClass().getMethod("getMapId").invoke(meta);
            if (id instanceof Number number) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // No id on this server version.
        }
        return null;
    }

    /** The map's {@code MapView}, or {@code null} on a server too old to say. */
    public static Object mapView(MapMeta meta) {
        try {
            return meta.getClass().getMethod("getMapView").invoke(meta);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * A copy of the map's pixels as the server holds them now, or {@code null}
     * when this server's map data cannot be reached. Call on the thread that
     * owns the map's world.
     */
    public static byte[] livePixels(Object mapView) {
        if (mapView == null) {
            return null;
        }
        try {
            Field worldMapField = mapView.getClass().getDeclaredField("worldMap");
            worldMapField.setAccessible(true);
            Object worldMap = worldMapField.get(mapView);
            for (Class<?> type = worldMap.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() == byte[].class) {
                        field.setAccessible(true);
                        byte[] colors = (byte[]) field.get(worldMap);
                        if (colors != null && colors.length == PIXELS) {
                            return colors.clone();
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // An unknown server layout; the saved file is the fallback.
        }
        return null;
    }

    /** The pixels in the world's saved map file, or {@code null}. Safe off the main thread. */
    public static byte[] savedPixels(int mapId) {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        File mapFile = new File(new File(Bukkit.getWorlds().get(0).getWorldFolder(), "data"), "map_" + mapId + ".dat");
        if (!mapFile.isFile()) {
            return null;
        }
        byte[] colors = MapNbtReader.extractColorsFromNbt(mapFile);
        return colors != null && colors.length >= PIXELS ? colors : null;
    }

    /** The map as a base64 PNG, the form the moderation API takes. */
    @SuppressWarnings("deprecation")
    public static String pngBase64(byte[] pixels) {
        try {
            BufferedImage image = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < SIDE; y++) {
                for (int x = 0; x < SIDE; x++) {
                    Color color = MapPalette.getColor(pixels[y * SIDE + x]);
                    image.setRGB(x, y, color.getRGB());
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A fingerprint of the pixels, so the same picture is checked once. */
    public static String fingerprint(byte[] pixels) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixels));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The map id of an item, or {@code null} when it is not a filled map. */
    public static Integer mapIdOf(ItemStack item, Material filledMap) {
        if (item == null || item.getType() != filledMap || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta instanceof MapMeta mapMeta ? resolveMapId(mapMeta) : null;
    }

    /** Takes every copy of one map from an inventory, off-hand included. Returns how many stacks went. */
    public static int removeMap(PlayerInventory inventory, Material filledMap, int mapId) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Integer id = mapIdOf(contents[slot], filledMap);
            if (id != null && id == mapId) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        ItemStack offHand = offHand(inventory);
        Integer offHandId = mapIdOf(offHand, filledMap);
        if (offHandId != null && offHandId == mapId) {
            try {
                inventory.getClass().getMethod("setItemInOffHand", ItemStack.class).invoke(inventory, (ItemStack) null);
                removed++;
            } catch (ReflectiveOperationException ignored) {
                // No off-hand on this server version.
            }
        }
        return removed;
    }
}
