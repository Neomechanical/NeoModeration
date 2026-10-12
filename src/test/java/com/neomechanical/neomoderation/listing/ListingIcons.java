package com.neomechanical.neomoderation.listing;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugin's Modrinth, Hangar and SpigotMC icons, drawn from the company mark.
 *
 * <p>{@code assets/mark.svg} is a copy of the Neomechanical mark: what
 * {@code brandMarkSvg({ plate: "#2e3fcb", ink: "#ffffff" })} returns in neomechanical-platform's
 * {@code apps/web/lib/brand/mark.js}. The design system's rule for app icons (DESIGN.md, "App
 * icons and favicons") puts the mark on a brand square with a 22 percent corner radius. Here the
 * mark's plate becomes that square and fills the icon, so the dot and strokes keep their
 * proportions and only the corners change.
 *
 * <p>When the mark changes, copy the new SVG over {@code assets/mark.svg}, then run this from the
 * repository root with Java 17 or later and commit what it writes:
 *
 * <pre>java src/test/java/com/neomechanical/neomoderation/listing/ListingIcons.java</pre>
 *
 * <p>{@code ListingIconsTest} draws every icon again and fails when a committed file differs.
 */
public final class ListingIcons {

    public static final Path MARK = Path.of("assets", "mark.svg");

    /** The app icon's corner radius as a share of its side (DESIGN.md). */
    static final double CORNER_RADIUS = 0.22;

    public static final List<Icon> ICONS = List.of(
            // Modrinth serves a 96 px copy of the upload; 512 px leaves it room to scale.
            new Icon(Path.of("assets", "icons", "modrinth.png"), 512, null),
            // Hangar serves project avatars at 256 px.
            new Icon(Path.of("assets", "icons", "hangar.png"), 256, null),
            // SpigotMC shows a 96 px icon saved as JPEG, which has no transparency, on the white
            // its resource page puts behind it; the corners take that white.
            new Icon(Path.of("assets", "icons", "spigotmc.png"), 96, 0xFFFFFF));

    private ListingIcons() {
    }

    public static void main(String[] args) throws IOException {
        Mark mark = Mark.parse(Files.readString(MARK));
        for (Icon icon : ICONS) {
            Files.createDirectories(icon.file().getParent());
            if (!ImageIO.write(draw(mark, icon), "png", icon.file().toFile())) {
                throw new IOException("No PNG writer for " + icon.file());
            }
            System.out.println("Wrote " + icon.file() + " (" + icon.size() + " px)");
        }
    }

    /** One icon: its file, its side in pixels, and the RGB colour behind its corners (null: clear). */
    public record Icon(Path file, int size, Integer background) {
    }

    /** Draws the brand square, then every other shape of the mark in document order. */
    public static BufferedImage draw(Mark mark, Icon icon) {
        int size = icon.size();
        boolean opaque = icon.background() != null;
        double scale = size / mark.plate().side();
        double radius = CORNER_RADIUS * size;
        BufferedImage image = new BufferedImage(size, size,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                Pixel pixel = opaque ? Pixel.filled(icon.background()) : new Pixel();
                pixel.paint(mark.plate().fill(), coverage(roundedSquare(px, py, size, radius)));
                double markX = mark.plate().x() + px / scale;
                double markY = mark.plate().y() + py / scale;
                for (Shape shape : mark.shapes()) {
                    pixel.paint(shape.colour(), coverage(shape.distance(markX, markY) * scale));
                }
                image.setRGB(x, y, pixel.argb());
            }
        }
        return image;
    }

    /** How much of a pixel an edge covers when the pixel's centre is {@code distance} px outside it. */
    static double coverage(double distance) {
        return Math.min(1, Math.max(0, 0.5 - distance));
    }

    /** Signed distance from a point to a {@code size} square at the origin with rounded corners. */
    static double roundedSquare(double x, double y, double size, double radius) {
        double half = size / 2;
        double qx = Math.abs(x - half) - (half - radius);
        double qy = Math.abs(y - half) - (half - radius);
        double outside = Math.sqrt(square(Math.max(qx, 0)) + square(Math.max(qy, 0)));
        return outside + Math.min(Math.max(qx, qy), 0) - radius;
    }

    private static double square(double value) {
        return value * value;
    }

    /** The mark: its plate, and the shapes drawn on it. */
    public record Mark(Plate plate, List<Shape> shapes) {

        /**
         * Reads the SVG the mark is published as. It holds a square plate ({@code rect}), filled
         * circles and stroked straight-line paths with round caps and joins; anything else is
         * refused, so a redrawn mark cannot come out wrong without a word.
         */
        public static Mark parse(String svg) {
            Element root = document(svg).getDocumentElement();
            require("svg".equals(root.getTagName()), "the mark must be an <svg> element");
            Plate plate = null;
            List<Shape> shapes = new ArrayList<>();
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeType() == Node.COMMENT_NODE
                        || (node.getNodeType() == Node.TEXT_NODE && node.getTextContent().isBlank())) {
                    continue;
                }
                require(node instanceof Element, "unexpected content in the mark: " + node.getNodeName());
                Element element = (Element) node;
                require(!element.hasAttribute("transform"), "transforms are not supported");
                switch (element.getTagName()) {
                    case "rect" -> {
                        require(plate == null && shapes.isEmpty(), "the plate must be the first and only <rect>");
                        plate = plate(element);
                    }
                    case "circle" -> {
                        require(unset(element, "stroke"), "a stroked <circle> is not supported");
                        shapes.add(new Circle(number(element, "cx"), number(element, "cy"),
                                number(element, "r"), colour(element.getAttribute("fill"))));
                    }
                    case "path" -> shapes.add(stroke(element));
                    default -> throw new IllegalArgumentException("<" + element.getTagName() + "> is not supported");
                }
            }
            require(plate != null, "the mark has no plate (<rect>)");
            return new Mark(plate, List.copyOf(shapes));
        }

        private static Plate plate(Element rect) {
            require(unset(rect, "stroke"), "a stroked plate is not supported");
            double width = number(rect, "width");
            require(width == number(rect, "height"), "the plate must be square");
            // The plate's own corner radius (rx) gives way to the app icon's.
            return new Plate(number(rect, "x"), number(rect, "y"), width, colour(rect.getAttribute("fill")));
        }

        private static Stroke stroke(Element path) {
            require("none".equals(path.getAttribute("fill")), "a filled <path> is not supported");
            require("round".equals(path.getAttribute("stroke-linecap"))
                    && "round".equals(path.getAttribute("stroke-linejoin")),
                    "a <path> must have round caps and joins");
            return new Stroke(segments(path.getAttribute("d")), number(path, "stroke-width"),
                    colour(path.getAttribute("stroke")));
        }

        private static boolean unset(Element element, String attribute) {
            return !element.hasAttribute(attribute) || "none".equals(element.getAttribute(attribute));
        }

        private static double number(Element element, String attribute) {
            require(element.hasAttribute(attribute), "<" + element.getTagName() + "> has no " + attribute);
            return Double.parseDouble(element.getAttribute(attribute));
        }

        private static Document document(String svg) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            } catch (ParserConfigurationException | SAXException | IOException e) {
                throw new IllegalArgumentException("cannot read the mark: " + e.getMessage(), e);
            }
        }
    }

    /** The mark's square plate, in the mark's own units. */
    public record Plate(double x, double y, double side, int fill) {
    }

    /** Something drawn on the plate, measured in the mark's own units. */
    public interface Shape {
        int colour();

        /** Signed distance from a point to the shape's edge: negative inside. */
        double distance(double x, double y);
    }

    record Circle(double cx, double cy, double r, int colour) implements Shape {
        @Override
        public double distance(double x, double y) {
            return Math.sqrt(square(x - cx) + square(y - cy)) - r;
        }
    }

    /** Round caps and joins make a stroke exactly the union of one capsule per segment. */
    record Stroke(List<double[]> segments, double width, int colour) implements Shape {
        @Override
        public double distance(double x, double y) {
            double nearest = Double.POSITIVE_INFINITY;
            for (double[] segment : segments) {
                nearest = Math.min(nearest, segmentDistance(x, y, segment));
            }
            return nearest - width / 2;
        }

        private static double segmentDistance(double x, double y, double[] segment) {
            double dx = segment[2] - segment[0];
            double dy = segment[3] - segment[1];
            double lengthSquared = dx * dx + dy * dy;
            double t = lengthSquared == 0 ? 0
                    : Math.min(1, Math.max(0, ((x - segment[0]) * dx + (y - segment[1]) * dy) / lengthSquared));
            return Math.sqrt(square(x - segment[0] - t * dx) + square(y - segment[1] - t * dy));
        }
    }

    private static final Pattern PATH_TOKEN =
            Pattern.compile("[\\s,]*([A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)");

    /**
     * The straight segments of SVG path data, each {x1, y1, x2, y2}. Reads M, L, H, V and Z in
     * both absolute and relative forms, with repeated coordinates after a command.
     */
    static List<double[]> segments(String data) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = PATH_TOKEN.matcher(data);
        int end = 0;
        while (matcher.find() && matcher.start() == end) {
            tokens.add(matcher.group(1));
            end = matcher.end();
        }
        require(data.substring(end).isBlank(), "cannot read path data at: " + data.substring(end));

        List<double[]> segments = new ArrayList<>();
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        char command = 0;
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                require("MmLlHhVvZz".indexOf(command) >= 0, "path command " + command + " is not supported");
                index++;
                if (command == 'Z' || command == 'z') {
                    segments.add(new double[]{x, y, startX, startY});
                    x = startX;
                    y = startY;
                }
                continue;
            }
            require(command != 0 && command != 'Z' && command != 'z', "a number has no command: " + token);
            boolean relative = Character.isLowerCase(command);
            double nextX = x;
            double nextY = y;
            switch (Character.toUpperCase(command)) {
                case 'M', 'L' -> {
                    nextX = coordinate(tokens, index++) + (relative ? x : 0);
                    nextY = coordinate(tokens, index++) + (relative ? y : 0);
                }
                case 'H' -> nextX = coordinate(tokens, index++) + (relative ? x : 0);
                default -> nextY = coordinate(tokens, index++) + (relative ? y : 0);
            }
            if (Character.toUpperCase(command) == 'M') {
                startX = nextX;
                startY = nextY;
                // Coordinates after a moveto are linetos.
                command = relative ? 'l' : 'L';
            } else {
                segments.add(new double[]{x, y, nextX, nextY});
            }
            x = nextX;
            y = nextY;
        }
        return segments;
    }

    private static double coordinate(List<String> tokens, int index) {
        require(index < tokens.size() && !Character.isLetter(tokens.get(index).charAt(0)),
                "path data ends before a coordinate");
        return Double.parseDouble(tokens.get(index));
    }

    /** An SVG colour written as #rgb or #rrggbb, as 0xRRGGBB. */
    static int colour(String value) {
        require(value.matches("#(\\p{XDigit}{3}|\\p{XDigit}{6})"), "colour " + value + " is not #rgb or #rrggbb");
        String hex = value.substring(1);
        if (hex.length() == 3) {
            hex = hex.replaceAll("(.)", "$1$1");
        }
        return Integer.parseInt(hex, 16);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** A pixel being painted with the SVG painter's model, its channels premultiplied by alpha. */
    private static final class Pixel {
        private double alpha;
        private double red;
        private double green;
        private double blue;

        static Pixel filled(int rgb) {
            Pixel pixel = new Pixel();
            pixel.paint(rgb, 1);
            return pixel;
        }

        void paint(int rgb, double cover) {
            if (cover <= 0) {
                return;
            }
            red = channel(rgb, 16) * cover + red * (1 - cover);
            green = channel(rgb, 8) * cover + green * (1 - cover);
            blue = channel(rgb, 0) * cover + blue * (1 - cover);
            alpha = cover + alpha * (1 - cover);
        }

        int argb() {
            int a = (int) Math.round(alpha * 255);
            if (a == 0) {
                return 0;
            }
            return a << 24 | toByte(red / alpha) << 16 | toByte(green / alpha) << 8 | toByte(blue / alpha);
        }

        private static double channel(int rgb, int shift) {
            return ((rgb >> shift) & 0xFF) / 255.0;
        }

        private static int toByte(double value) {
            return (int) Math.round(Math.min(1, Math.max(0, value)) * 255);
        }
    }
}
