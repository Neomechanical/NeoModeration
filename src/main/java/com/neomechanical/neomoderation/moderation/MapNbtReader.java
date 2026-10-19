package com.neomechanical.neomoderation.moderation;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Reads the pixel bytes of a saved map ({@code world/data/map_<id>.dat}) with a
 * minimal NBT walker, refusing any collection larger than a map could hold.
 */
final class MapNbtReader {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_COMPOUND = 10;
    private static final int MAX_NBT_COLLECTION_LENGTH = 16 * 1024 * 1024;

    private MapNbtReader() {
    }

    /** The {@code colors} array of a map file, or {@code null} when it cannot be read. */
    static byte[] extractColorsFromNbt(File file) {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new FileInputStream(file)))) {
            int rootTag = input.readUnsignedByte();
            if (rootTag != TAG_COMPOUND) {
                return null;
            }
            readName(input);
            return findColorsInCompound(input);
        } catch (IOException | RuntimeException e) {
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
            case 3, 5 -> input.skipNBytes(4);
            case 4, 6 -> input.skipNBytes(8);
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
