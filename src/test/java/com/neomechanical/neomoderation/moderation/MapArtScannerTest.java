package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MapArtScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsColorsFromMinecraftDataCompound() throws Exception {
        byte[] expected = new byte[128 * 128];
        Arrays.fill(expected, (byte) 34);
        File mapFile = writeMapNbt("map_7.dat", expected, false);

        assertArrayEquals(expected, extractColors(mapFile));
    }

    @Test
    void rejectsNegativeByteArrayLength() throws Exception {
        File mapFile = writeMapNbt("map_bad.dat", new byte[0], true);

        assertNull(extractColors(mapFile));
    }

    private File writeMapNbt(String name, byte[] colors, boolean negativeLength) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (DataOutputStream output = new DataOutputStream(
                new GZIPOutputStream(new FileOutputStream(file)))) {
            output.writeByte(10); // root TAG_Compound
            output.writeUTF("");
            output.writeByte(3); // DataVersion TAG_Int
            output.writeUTF("DataVersion");
            output.writeInt(4189);
            output.writeByte(10); // data TAG_Compound
            output.writeUTF("data");
            output.writeByte(7); // colors TAG_Byte_Array
            output.writeUTF("colors");
            output.writeInt(negativeLength ? -1 : colors.length);
            if (!negativeLength) {
                output.write(colors);
            }
            output.writeByte(0); // end data
            output.writeByte(0); // end root
        }
        return file;
    }

    private byte[] extractColors(File file) throws Exception {
        return MapNbtReader.extractColorsFromNbt(file);
    }
}
