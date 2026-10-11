package com.neomechanical.neomoderation.listing;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListingIconsTest {

    private static final String REGENERATE =
            "run: java src/test/java/com/neomechanical/neomoderation/listing/ListingIcons.java";

    @Test
    void committedIconsAreTheMarkAsItIsNow() throws IOException {
        ListingIcons.Mark mark = ListingIcons.Mark.parse(Files.readString(ListingIcons.MARK));
        for (ListingIcons.Icon icon : ListingIcons.ICONS) {
            BufferedImage committed = ImageIO.read(icon.file().toFile());
            assertNotNull(committed, icon.file() + " is missing or is not an image; " + REGENERATE);
            BufferedImage expected = ListingIcons.draw(mark, icon);
            assertEquals(icon.size(), committed.getWidth(), icon.file() + " width");
            assertEquals(icon.size(), committed.getHeight(), icon.file() + " height");
            int differing = 0;
            for (int y = 0; y < icon.size(); y++) {
                for (int x = 0; x < icon.size(); x++) {
                    if (committed.getRGB(x, y) != expected.getRGB(x, y)) {
                        differing++;
                    }
                }
            }
            int count = differing;
            assertEquals(0, count, () -> icon.file() + " differs from " + ListingIcons.MARK + " in " + count
                    + " pixels; " + REGENERATE);
        }
    }

    @Test
    void eachIconIsTheMarkOnTheBrandSquare() throws IOException {
        ListingIcons.Mark mark = ListingIcons.Mark.parse(Files.readString(ListingIcons.MARK));
        assertEquals(0x2E3FCB, mark.plate().fill(), "the plate is the brand token, --nm-brand #2e3fcb");
        for (ListingIcons.Icon icon : ListingIcons.ICONS) {
            BufferedImage image = ImageIO.read(icon.file().toFile());
            assertNotNull(image, icon.file().toString());
            double scale = icon.size() / mark.plate().side();
            // A point on the plate clear of the glyph, and the centre of the mark's dot (139, 165).
            int plateX = (int) ((256 - mark.plate().x()) * scale);
            int plateY = (int) ((60 - mark.plate().y()) * scale);
            int dotX = (int) ((139 - mark.plate().x()) * scale);
            int dotY = (int) ((165 - mark.plate().y()) * scale);
            assertEquals(0xFF2E3FCB, image.getRGB(plateX, plateY), icon.file() + " plate");
            assertEquals(0xFFFFFFFF, image.getRGB(dotX, dotY), icon.file() + " dot");
            int corner = image.getRGB(0, 0);
            if (icon.background() == null) {
                assertEquals(0, corner >>> 24, icon.file() + ": the corner outside the square is not clear");
            } else {
                assertEquals(0xFF000000 | icon.background(), corner, icon.file() + " corner");
            }
        }
    }

    @Test
    void readsRelativeAndRepeatedPathCommands() {
        List<double[]> check = ListingIcons.segments("M222 300l58 58 105-133");
        assertEquals(2, check.size());
        assertArrayEquals(new double[]{222, 300, 280, 358}, check.get(0));
        assertArrayEquals(new double[]{280, 358, 385, 225}, check.get(1));

        List<double[]> step = ListingIcons.segments("M139 165h92v78h67");
        assertEquals(3, step.size());
        assertArrayEquals(new double[]{139, 165, 231, 165}, step.get(0));
        assertArrayEquals(new double[]{231, 165, 231, 243}, step.get(1));
        assertArrayEquals(new double[]{231, 243, 298, 243}, step.get(2));

        List<double[]> closed = ListingIcons.segments("M0 0 L10 0 10 10z");
        assertEquals(3, closed.size());
        assertArrayEquals(new double[]{10, 10, 0, 0}, closed.get(2));
    }

    @Test
    void refusesWhatItCannotDraw() {
        String plate = "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#2e3fcb\"/>";
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.segments("M0 0C1 1 2 2 3 3"));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.segments("M0 0 L1 x"));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.Mark.parse(
                svg(plate + "<ellipse cx=\"5\" cy=\"5\" rx=\"1\" ry=\"2\" fill=\"#fff\"/>")));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.Mark.parse(
                svg(plate + "<circle cx=\"5\" cy=\"5\" r=\"1\" fill=\"#fff\" transform=\"rotate(9)\"/>")));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.Mark.parse(svg(plate
                + "<path d=\"M1 1h5\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1\" stroke-linecap=\"butt\""
                + " stroke-linejoin=\"round\"/>")));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.Mark.parse(
                svg("<rect x=\"0\" y=\"0\" width=\"10\" height=\"12\" fill=\"#2e3fcb\"/>")));
        assertThrows(IllegalArgumentException.class, () -> ListingIcons.Mark.parse(
                svg("<circle cx=\"5\" cy=\"5\" r=\"1\" fill=\"#fff\"/>")));
    }

    private static String svg(String body) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">" + body + "</svg>";
    }
}
