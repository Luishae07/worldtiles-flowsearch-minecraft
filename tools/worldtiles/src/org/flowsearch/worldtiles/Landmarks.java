package org.flowsearch.worldtiles;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Landmarks: models of famous buildings (the Eiffel Tower, the Burj Khalifa, ...), turned into blocks by tools/landmarkvox (one block per metre, the surface only,
 * so a lattice stays a lattice). The folder has index.txt (one line per landmark) and one file per landmark; only the index is read at the start. A model is
 * read when a chunk near it is built (a few stay in memory) and is placed like the trees. The houses of the map under it are left out.
 * Models: Open Landmarks (CC BY 4.0) and the CC0 library of Alex Wohlbruck; the places are from OpenStreetMap data (ODbL).
 */
final class Landmarks {
    private static final int CELL = 64;      // the size of a square of the grid in which the landmarks are looked up
    private static final int KEEP = 12;      // models that stay in memory

    static final class Lm {
        String id, name, file, licence;
        int ax, az, h, elev;
        int minX, maxX, minZ, maxZ;          // the area of the model, in blocks of the world
        volatile int baseY = Integer.MIN_VALUE;
        volatile double scale = 1;
        // the blocks; null while the model is not in memory
        volatile Data data;
    }

    static final class Data {
        int n;
        int[] x, y, z;
        byte[] m;
        BitSet foot;                          // the columns of the model (and 2 blocks around them), over the area minX-2 .. maxX+2
        int fw;
    }

    private static final Map<Long, List<Lm>> GRID = new HashMap<>();
    static final List<Lm> ALL = new ArrayList<>();
    private static File dir;
    private static Logger log;
    /** the models that are in memory, the least used first */
    private static final LinkedHashMap<String, Lm> LOADED = new LinkedHashMap<>(16, 0.75f, true);

    private static final Material[] MATS = {
            Material.WHITE_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.BLACK_CONCRETE, Material.BROWN_CONCRETE, Material.MUD_BRICKS,
            Material.TERRACOTTA, Material.DEEPSLATE, Material.ORANGE_CONCRETE, Material.RED_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
            Material.GREEN_CONCRETE, Material.CYAN_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.BLUE_CONCRETE, Material.PURPLE_CONCRETE, Material.PINK_CONCRETE,
            Material.STONE, Material.SMOOTH_STONE, Material.SMOOTH_SANDSTONE, Material.OXIDIZED_COPPER, Material.GOLD_BLOCK, Material.IRON_BLOCK,
            Material.STONE_BRICKS, Material.BRICKS, Material.WHITE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.RED_TERRACOTTA, Material.BROWN_TERRACOTTA,
            Material.ORANGE_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.CYAN_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA, Material.SANDSTONE, Material.GLASS
    };
    /** the colours of these blocks as they look (the same order) */
    private static final int[] COLS = {
            0xCFD5D6, 0x7D7D73, 0x36393D, 0x080A0F, 0x603C20, 0x89684F,
            0x985E43, 0x4D4D51, 0xE06100, 0x8E2121, 0xF0AF15, 0x5EA818,
            0x495B24, 0x157788, 0x2489C7, 0x2C2E8F, 0x641F9C, 0xD5658E,
            0x7D7D7D, 0x9E9E9E, 0xE0D6A8, 0x52A385, 0xF6D03D, 0xDCDCDC,
            0x7A7A7A, 0x96584A, 0xD1B2A1, 0xBA8523, 0x8F3D2F, 0x4D3323,
            0xA25325, 0x4C532A, 0x575B5B, 0x716C89, 0xE0D3A4, 0xA9D4F0
    };

    static boolean any() {
        return !ALL.isEmpty();
    }

    private static byte nearest(int rgb) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, best = 0;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < COLS.length; i++) {
            int c = COLS[i], dr = r - (c >> 16 & 255), dg = g - (c >> 8 & 255), db = b - (c & 255);
            long d = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return (byte) best;
    }

    static void load(File folder, Logger logger) {
        dir = folder;
        log = logger;
        File index = new File(folder, "index.txt");
        if (!index.isFile()) {
            log.info("Landmarks: no " + index + ", none loaded");
            return;
        }
        try (BufferedReader in = Files.newBufferedReader(index.toPath(), StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.split("\t");
                if (p.length < 12) continue;
                try {
                    Lm l = new Lm();
                    l.id = p[0];
                    l.name = p[1];
                    double lon = Double.parseDouble(p[2]), lat = Double.parseDouble(p[3]);
                    l.ax = Cmds.blockX(lon);
                    l.az = Cmds.blockZ(lat);
                    l.minX = l.ax + Integer.parseInt(p[4]);
                    l.maxX = l.ax + Integer.parseInt(p[5]);
                    l.minZ = l.az + Integer.parseInt(p[6]);
                    l.maxZ = l.az + Integer.parseInt(p[7]);
                    l.h = Integer.parseInt(p[8]);
                    l.elev = Integer.parseInt(p[9]);
                    l.file = p[10];
                    l.licence = p[11];
                    ALL.add(l);
                    for (int cz = Math.floorDiv(l.minZ - 2, CELL); cz <= Math.floorDiv(l.maxZ + 2, CELL); cz++)
                        for (int cx = Math.floorDiv(l.minX - 2, CELL); cx <= Math.floorDiv(l.maxX + 2, CELL); cx++)
                            GRID.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(l);
                } catch (NumberFormatException e) {
                    // a bad line is left out
                }
            }
        } catch (Exception e) {
            log.warning("Landmarks: the index could not be read: " + e);
        }
        log.info("Landmarks: " + ALL.size() + " models in " + folder);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** the blocks of a model: read from its file the first time, then kept (the least used ones are dropped) */
    private static Data data(Lm l) {
        Data d = l.data;
        if (d != null) {
            synchronized (LOADED) {
                LOADED.get(l.id); // used now
            }
            return d;
        }
        synchronized (l) {
            if (l.data != null) return l.data;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(new File(dir, l.file).toPath()), 1 << 16), StandardCharsets.US_ASCII), 1 << 16)) {
                int n = Integer.parseInt(in.readLine().trim());
                d = new Data();
                d.x = new int[n];
                d.y = new int[n];
                d.z = new int[n];
                d.m = new byte[n];
                HashMap<Integer, Byte> cache = new HashMap<>();
                int k = 0;
                for (String line; k < n && (line = in.readLine()) != null; ) {
                    int a = line.indexOf(' '), b = line.indexOf(' ', a + 1), c = line.indexOf(' ', b + 1);
                    d.x[k] = Integer.parseInt(line, 0, a, 10);
                    d.y[k] = Integer.parseInt(line, a + 1, b, 10);
                    d.z[k] = Integer.parseInt(line, b + 1, c, 10);
                    d.m[k] = cache.computeIfAbsent(Integer.parseInt(line, c + 1, line.length(), 16), Landmarks::nearest);
                    k++;
                }
                d.n = k;
                d.fw = l.maxX - l.minX + 5;
                d.foot = new BitSet(d.fw * (l.maxZ - l.minZ + 5));
                for (int i = 0; i < d.n; i++)
                    for (int dz = -2; dz <= 2; dz++)
                        for (int dx = -2; dx <= 2; dx++) {
                            int fx = l.ax + d.x[i] + dx - (l.minX - 2), fz = l.az + d.z[i] + dz - (l.minZ - 2);
                            if (fx >= 0 && fx < d.fw && fz >= 0) d.foot.set(fz * d.fw + fx);
                        }
            } catch (Exception e) {
                log.warning("Landmark " + l.id + " could not be read: " + e);
                d = new Data(); // empty, so that it is not tried again for every chunk
                d.foot = new BitSet();
                d.fw = 1;
            }
            l.data = d;
        }
        synchronized (LOADED) {
            LOADED.put(l.id, l);
            while (LOADED.size() > KEEP) {
                var it = LOADED.entrySet().iterator();
                Lm old = it.next().getValue();
                it.remove();
                old.data = null;
            }
        }
        return d;
    }

    /** true when a block of the map at this place is under a landmark (a house of the map must not stand there) */
    static boolean covers(int X, int Z) {
        List<Lm> cell = GRID.get(key(Math.floorDiv(X, CELL), Math.floorDiv(Z, CELL)));
        if (cell == null) return false;
        for (Lm l : cell) {
            if (X < l.minX - 2 || X > l.maxX + 2 || Z < l.minZ - 2 || Z > l.maxZ + 2) continue;
            Data d = data(l);
            int fx = X - (l.minX - 2), fz = Z - (l.minZ - 2);
            if (fx >= 0 && fx < d.fw && d.foot.get(fz * d.fw + fx)) return true;
        }
        return false;
    }

    /** puts the parts of the landmarks that are inside this chunk into it */
    static void paste(TileGen gen, ChunkData data, int chunkX, int chunkZ, int minY, int maxY) {
        if (ALL.isEmpty()) return;
        int x0 = chunkX * 16, z0 = chunkZ * 16;
        List<Lm> seen = null;
        for (int cz = Math.floorDiv(z0, CELL); cz <= Math.floorDiv(z0 + 15, CELL); cz++)
            for (int cx = Math.floorDiv(x0, CELL); cx <= Math.floorDiv(x0 + 15, CELL); cx++) {
                List<Lm> cell = GRID.get(key(cx, cz));
                if (cell == null) continue;
                for (Lm l : cell) {
                    if (l.maxX < x0 || l.minX > x0 + 15 || l.maxZ < z0 || l.minZ > z0 + 15) continue;
                    if (seen == null) seen = new ArrayList<>();
                    if (!seen.contains(l)) seen.add(l);
                }
            }
        if (seen == null) return;
        for (Lm l : seen) {
            if (l.baseY == Integer.MIN_VALUE) {
                int g = gen.surfaceY(l.ax, l.az, true); // the ground at the middle point
                if (g == Integer.MIN_VALUE) continue;
                l.baseY = g + 1 + l.elev;
                // the world is not higher than maxY: a taller model is squeezed in height only (so that its top is not cut off)
                l.scale = Math.min(1.0, (maxY - 2 - l.baseY) / (double) Math.max(1, l.h));
            }
            Data d = data(l);
            int base = l.baseY, ax = l.ax, az = l.az;
            double sc = l.scale;
            for (int i = 0; i < d.n; i++) {
                int X = ax + d.x[i] - x0, Z = az + d.z[i] - z0;
                if (X < 0 || X > 15 || Z < 0 || Z > 15) continue;
                int Y = base + (int) Math.floor(d.y[i] * sc);
                if (Y < minY || Y >= maxY) continue;
                data.setBlock(X, Y, Z, MATS[d.m[i]]);
            }
        }
    }
}
