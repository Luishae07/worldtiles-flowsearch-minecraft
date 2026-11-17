package org.flowsearch.worldtiles;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the Moon looks like: the height of the ground from NASA's LOLA laser map (LDEM_64: 64 pixels per degree, 16 bit, half metres,
 * measured from a sphere of 1737.4 km) and how bright it is from the surface pictures (grey JPEG tiles, zoom 6, 128 x 64 tiles of 256 pixels).
 */
final class MoonData {
    static final double M_DEG = 2 * Math.PI * 1737400.0 / 360; // metres on the Moon for one degree
    private static final int W = 23040, H = 11520;

    private MappedByteBuffer dem;
    private final File tileDir;
    private final Map<Integer, byte[]> tiles = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) {
            return size() > 48;
        }
    };

    MoonData(Path demFile, File tileDir) {
        this.tileDir = tileDir;
        try (FileChannel ch = FileChannel.open(demFile, StandardOpenOption.READ)) {
            if (ch.size() >= (long) W * H * 2) {
                dem = ch.map(FileChannel.MapMode.READ_ONLY, 0, (long) W * H * 2);
                dem.order(ByteOrder.LITTLE_ENDIAN);
            }
        } catch (IOException e) {
            dem = null;
        }
    }

    boolean hasHeights() {
        return dem != null;
    }

    private double dn(int x, int y) {
        return dem.getShort((y * W + x) * 2) * 0.5;
    }

    /** the height of the ground in metres above the reference sphere (bilinear between the pixels) */
    double heightM(double lat, double lonEast) {
        if (dem == null) return 0;
        double x = (((lonEast % 360) + 360) % 360) * 64 - 0.5, y = (90 - lat) * 64 - 0.5;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        int x1 = Math.floorMod(x0 + 1, W);
        x0 = Math.floorMod(x0, W);
        int y1 = Math.max(0, Math.min(H - 1, y0 + 1));
        y0 = Math.max(0, Math.min(H - 1, y0));
        return dn(x0, y0) * (1 - fx) * (1 - fy) + dn(x1, y0) * fx * (1 - fy) + dn(x0, y1) * (1 - fx) * fy + dn(x1, y1) * fx * fy;
    }

    /** how bright the ground is, 0 (dark mare basalt) to 1 (bright fresh ejecta); 0.5 when the picture is not there */
    synchronized double brightness(double lat, double lon) {
        double px = (lon + 180) / 360 * 32768, py = (90 - lat) / 180 * 16384;
        int ix = (int) Math.floor(px), iy = (int) Math.floor(py);
        if (iy < 0 || iy >= 16384) return 0.5;
        ix = Math.floorMod(ix, 32768);
        int key = (ix >> 8) * 64 + (iy >> 8);
        byte[] t = tiles.get(key);
        if (t == null) {
            t = new byte[256 * 256];
            java.util.Arrays.fill(t, (byte) 128);
            try {
                BufferedImage img = ImageIO.read(new File(tileDir, (ix >> 8) + "/" + (iy >> 8) + ".jpg"));
                if (img != null) {
                    var r = img.getRaster();
                    for (int yy = 0; yy < 256; yy++) for (int xx = 0; xx < 256; xx++) t[yy * 256 + xx] = (byte) r.getSample(xx, yy, 0);
                }
            } catch (Exception ignored) {
            }
            tiles.put(key, t);
        }
        return (t[(iy & 255) * 256 + (ix & 255)] & 0xFF) / 255.0;
    }
}
