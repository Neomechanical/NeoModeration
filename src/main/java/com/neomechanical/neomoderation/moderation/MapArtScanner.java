package com.neomechanical.neomoderation.moderation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapView;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

public final class MapArtScanner {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_COMPOUND = 10;
    private static final int MAX_NBT_COLLECTION_LENGTH = 16 * 1024 * 1024;

    private MapArtScanner() {
    }

    public static String imageHash(String image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(image.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    public static Integer resolveMapId(MapMeta meta) {
        try {
            Method getMapView = meta.getClass().getMethod("getMapView");
            Object view = getMapView.invoke(meta);
            if (view instanceof MapView mapView) {
                return (int) mapView.getId();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Method getMapId = meta.getClass().getMethod("getMapId");
            Object id = getMapId.invoke(meta);
            if (id instanceof Number number) {
                return number.intValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    public static String getBase64Image(int mapId) {
        try {
            if (Bukkit.getWorlds().isEmpty()) {
                return null;
            }
            File worldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
            File dataFolder = new File(worldFolder, "data");
            File mapFile = new File(dataFolder, "map_" + mapId + ".dat");
            if (!mapFile.exists()) {
                return null;
            }

            byte[] colors = extractColorsFromNbt(mapFile);
            if (colors == null || colors.length < 16384) {
                return null;
            }

            BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    int index = y * 128 + x;
                    @SuppressWarnings("deprecation")
                    Color color = MapPalette.getColor(colors[index]);
                    image.setRGB(x, y, color.getRGB());
                }
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] extractColorsFromNbt(File file) {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            int rootTag = input.readUnsignedByte();
            if (rootTag != TAG_COMPOUND) {
                return null;
            }
            readName(input);
            return findColorsInCompound(input);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] findColorsInCompound(DataInputStream input) throws IOException {
        while (true) {
            int tag = input.readUnsignedByte();
            if (tag == TAG_END) {
                return null;
            }
            String name = readName(input);
            if (tag == TAG_BYTE_ARRAY) {
                int length = readLength(input);
                if ("colors".equals(name)) {
                    byte[] payload = new byte[length];
                    input.readFully(payload);
                    return payload;
                }
                input.skipNBytes(length);
                continue;
            }
            if (tag == TAG_COMPOUND) {
                byte[] nested = findColorsInCompound(input);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            skipTagPayload(input, tag);
        }
    }

    private static String readName(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] nameBytes = new byte[length];
        input.readFully(nameBytes);
        return new String(nameBytes, StandardCharsets.UTF_8);
    }

    private static int readLength(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_NBT_COLLECTION_LENGTH) {
            throw new IOException("Invalid NBT collection length: " + length);
        }
        return length;
    }

    private static void skipElements(DataInputStream input, int count, int elementBytes) throws IOException {
        long bytes = Math.multiplyExact((long) count, elementBytes);
        if (bytes > MAX_NBT_COLLECTION_LENGTH) {
            throw new IOException("NBT payload is too large: " + bytes);
        }
        input.skipNBytes(bytes);
    }

    private static void skipTagPayload(DataInputStream input, int tag) throws IOException {
        switch (tag) {
            case 1 -> input.skipNBytes(1);
            case 2 -> input.skipNBytes(2);
            case 3 -> input.skipNBytes(4);
            case 4 -> input.skipNBytes(8);
            case 5 -> input.skipNBytes(4);
            case 6 -> input.skipNBytes(8);
            case TAG_BYTE_ARRAY -> input.skipNBytes(readLength(input));
            case 8 -> input.skipNBytes(input.readUnsignedShort());
            case 9 -> {
                int listType = input.readUnsignedByte();
                int length = readLength(input);
                for (int i = 0; i < length; i++) {
                    skipTagPayload(input, listType);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    int subTag = input.readUnsignedByte();
                    if (subTag == TAG_END) {
                        break;
                    }
                    readName(input);
                    skipTagPayload(input, subTag);
                }
            }
            case 11 -> skipElements(input, readLength(input), Integer.BYTES);
            case 12 -> skipElements(input, readLength(input), Long.BYTES);
            default -> throw new IOException("Unsupported NBT tag: " + tag);
        }
    }
}
