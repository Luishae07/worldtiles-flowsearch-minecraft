package org.flowsearch.worldtiles;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/** Builds the world from the squares of the tile service: ground, roads, water, trees and houses with rooms. One block per metre. */
public final class TileGen extends ChunkGenerator {
    static final int SEA = 62;
    private static final int T = Tile.TILE;

    private final TileStore store;
    private final BlockData grassBlock = Material.GRASS_BLOCK.createBlockData();
    private final BlockData oakLeaves = Material.OAK_LEAVES.createBlockData("[persistent=true]");

    volatile Indoor indoor;

    /** places where the houses of the map are left out (a stadium: the pitch and the stands are built there instead): {x, z, cos, sin, half length, half width} */
    final java.util.concurrent.CopyOnWriteArrayList<double[]> zones = new java.util.concurrent.CopyOnWriteArrayList<>();

    boolean hasZones() {
        return !zones.isEmpty() || Landmarks.any();
    }

    boolean inZone(int X, int Z) {
        if (Landmarks.covers(X, Z)) return true; // under a landmark: the houses of the map are left out
        for (double[] z : zones) {
            double dx = X + 0.5 - z[0], dz = Z + 0.5 - z[1];
            double u = dx * z[2] + dz * z[3], v = -dx * z[3] + dz * z[2];
            if (Math.abs(u) <= z[4] && Math.abs(v) <= z[5]) return true;
        }
        return false;
    }

    TileGen(TileStore store) {
        this.store = store;
    }

    private static Material surfaceMat(int s) {
        return switch (s) {
            case Tile.S_PLAZA -> Material.POLISHED_ANDESITE;
            case Tile.S_ASPHALT -> Material.GRAY_CONCRETE;
            case Tile.S_GRAVEL -> Material.GRAVEL;
            case Tile.S_DIRT -> Material.COARSE_DIRT;
            case Tile.S_SAND -> Material.SAND;
            default -> Material.GRASS_BLOCK;
        };
    }

    private static Material wallMat(int m) {
        return switch (m) {
            case 11 -> Material.BRICKS;
            case 12 -> Material.STONE_BRICKS;
            case 40 -> Material.GRAY_CONCRETE; // a data center
            case 13 -> Material.LIGHT_GRAY_CONCRETE;
            case 14 -> Material.WHITE_TERRACOTTA;
            case 15 -> Material.YELLOW_TERRACOTTA;
            default -> Material.SMOOTH_SANDSTONE;
        };
    }

    private static Material roofMat(int m) {
        return switch (m) {
            case 21 -> Material.DEEPSLATE_TILES;
            case 22 -> Material.OXIDIZED_COPPER;
            case 23 -> Material.STONE;
            case 24 -> Material.RED_TERRACOTTA;       // the colours below come from the satellite picture
            case 25 -> Material.BROWN_TERRACOTTA;
            case 26 -> Material.LIGHT_GRAY_CONCRETE;
            case 27 -> Material.GRAY_CONCRETE;
            case 29 -> Material.WHITE_CONCRETE;
            case 30 -> Material.YELLOW_TERRACOTTA;
            case 31 -> Material.TERRACOTTA;
            case 32 -> Material.WHITE_TERRACOTTA;
            case 33 -> Material.GREEN_CONCRETE;
            default -> Material.ORANGE_TERRACOTTA;
        };
    }

    private static boolean windowAt(int x, int z, int r, int wh) {
        return wh >= 5 && r >= 1 && r < wh - 2 && (r % 4 == 1 || r % 4 == 2) && Math.floorMod(x + z, 3) != 0;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        int minY = data.getMinHeight(), maxY = data.getMaxHeight();
        int baseX = chunkX * 16, baseZ = chunkZ * 16;
        Tile[] cache = new Tile[4];
        int[] cacheKey = new int[8];
        int used = 0;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int X = baseX + dx, Z = baseZ + dz;
                int tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
                Tile t = null;
                for (int k = 0; k < used; k++) if (cacheKey[k * 2] == tx && cacheKey[k * 2 + 1] == tz) t = cache[k];
                if (t == null) {
                    t = store.get(tx, tz);
                    if (used < 4) {
                        cache[used] = t;
                        cacheKey[used * 2] = tx;
                        cacheKey[used * 2 + 1] = tz;
                        used++;
                    }
                }
                if (t == null) { // the service could not make this square: a flat meadow, so that the world is not full of holes
                    data.setRegion(dx, minY, dz, dx + 1, SEA - 3, dz + 1, Material.STONE);
                    data.setRegion(dx, SEA - 3, dz, dx + 1, SEA, dz + 1, Material.DIRT);
                    data.setBlock(dx, SEA, dz, Material.GRASS_BLOCK);
                    continue;
                }
                column(data, t, X - tx * T, Z - tz * T, dx, dz, minY, maxY);
            }
        }
        trees(data, chunkX, chunkZ, minY, maxY);
        Landmarks.paste(this, data, chunkX, chunkZ, minY, maxY);
        if (LOGGED.incrementAndGet() <= 8) { // the first chunks: so that the log shows that this generator really made them
            java.util.logging.Logger.getLogger("WorldTiles").info("made chunk " + chunkX + "," + chunkZ + ": y of the ground at its corner = "
                    + data.getType(0, 120, 0) + "/" + data.getType(0, 80, 0) + " (minY " + minY + ", maxY " + maxY + ")");
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    private void column(ChunkData data, Tile t, int lx, int lz, int dx, int dz, int minY, int maxY) {
        int i = Tile.idx(lx, lz);
        int g = Math.min(maxY - 2, SEA + t.ground[i]);
        int sf = t.surf[i];
        boolean open = hasZones() && (t.wallH[i] & 0xFF) > 0 && inZone(t.tx * T + lx, t.tz * T + lz);
        int wh = open ? 0 : t.wallH[i] & 0xFF, rh = open ? 0 : t.roofH[i] & 0xFF;
        data.setBlock(dx, minY, dz, Material.BEDROCK);
        data.setRegion(dx, minY + 1, dz, dx + 1, Math.max(minY + 1, g - 3), dz + 1, Material.STONE);
        data.setRegion(dx, Math.max(minY + 1, g - 3), dz, dx + 1, g, dz + 1, sf == Tile.S_WATER ? Material.SAND : Material.DIRT);
        if (wh > 0) {
            data.setBlock(dx, g, dz, Material.OAK_PLANKS);
            int top = Math.min(g + wh + rh, maxY - 1);
            boolean peri = t.peri[i] != 0, door = t.door[i] != 0, lad = t.ladder[i] != 0, hall = t.hall[i] != 0;
            Material wall = wallMat(t.wallM[i] & 0xFF), roof = roofMat(t.roofM[i] & 0xFF);
            int WX = t.tx * T + lx, WZ = t.tz * T + lz;
            for (int y = g + 1; y <= top; y++) {
                if (y >= g + wh) {
                    data.setBlock(dx, y, dz, roof);
                    continue;
                }
                int r = y - g - 1;
                if (peri) {
                    if (door && r < 2) continue;
                    data.setBlock(dx, y, dz, windowAt(WX, WZ, r, wh) ? Material.GLASS : wall);
                } else if (lad) {
                    BlockFace face = ladderFace(t, lx, lz);
                    if (face != null) {
                        Ladder l = (Ladder) Material.LADDER.createBlockData();
                        l.setFacing(face);
                        data.setBlock(dx, y, dz, l);
                    }
                } else if (!hall && r % 4 == 3) {
                    data.setBlock(dx, y, dz, Material.OAK_PLANKS);
                }
            }
            // the walls of the rooms that somebody mapped in this house (OpenStreetMap indoor data), 3 high on top of each floor
            if (!peri && !lad && indoor != null) {
                indoor.ensure(WX, WZ);
                int[] rooms = indoor.at(WX, WZ);
                if (rooms != null) {
                    boolean gap = indoor.isDoor(WX, WZ);
                    for (int v : rooms) {
                        int y0 = g + 1 + 4 * ((v >> 6) - 8);
                        Material m = Indoor.MATS[Math.min(Indoor.MATS.length - 1, v & 63)];
                        for (int y = y0; y <= y0 + 2; y++) {
                            if (y <= g || y >= g + wh || y > top) continue;
                            if (gap && y < y0 + 2) continue;
                            data.setBlock(dx, y, dz, m);
                        }
                    }
                }
            }
            return;
        }
        if (sf == Tile.S_WATER) {
            data.setBlock(dx, g, dz, Material.WATER);
        } else {
            data.setBlock(dx, g, dz, open ? Material.GRASS_BLOCK : surfaceMat(sf));
        }
    }

    /** a ladder is fixed to a wall: it faces away from the wall next to it */
    private static BlockFace ladderFace(Tile t, int lx, int lz) {
        int[][] d = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        BlockFace[] face = {BlockFace.SOUTH, BlockFace.NORTH, BlockFace.EAST, BlockFace.WEST};
        for (int k = 0; k < 4; k++) {
            int j = Tile.idx(lx + d[k][0], lz + d[k][1]);
            if (t.peri[j] != 0 && t.door[j] == 0 && t.isB(j)) return face[k];
        }
        return null;
    }

    /** trees: a trunk and a ball of leaves; the ones of the next squares and chunks reach into this chunk too */
    private void trees(ChunkData data, int chunkX, int chunkZ, int minY, int maxY) {
        int x0 = chunkX * 16, z0 = chunkZ * 16, x1 = x0 + 15, z1 = z0 + 15;
        int txa = Math.floorDiv(x0 - 3, T), txb = Math.floorDiv(x1 + 3, T), tza = Math.floorDiv(z0 - 3, T), tzb = Math.floorDiv(z1 + 3, T);
        for (int tz = tza; tz <= tzb; tz++) {
            for (int tx = txa; tx <= txb; tx++) {
                Tile t = store.get(tx, tz);
                if (t == null) continue;
                for (int n = 0; n + 2 < t.trees.length; n += 3) {
                    int TX = tx * T + t.trees[n], TZ = tz * T + t.trees[n + 1];
                    if (TX < x0 - 3 || TX > x1 + 3 || TZ < z0 - 3 || TZ > z1 + 3) continue;
                    int i = Tile.idx(t.trees[n], t.trees[n + 1]);
                    if (t.surf[i] == Tile.S_WATER) continue;
                    int base = SEA + t.ground[i] + 1, th = Math.max(2, t.trees[n + 2] - 2);
                    for (int y = 0; y < th; y++) put(data, TX, base + y, TZ, x0, z0, minY, maxY, Material.OAK_LOG.createBlockData());
                    int cy = base + th;
                    for (int ox = -2; ox <= 2; ox++)
                        for (int oy = -1; oy <= 2; oy++)
                            for (int oz = -2; oz <= 2; oz++) {
                                if (ox * ox + oy * oy * 1.6 + oz * oz > 5.2 || (ox == 0 && oz == 0 && oy < 1)) continue;
                                put(data, TX + ox, cy + oy, TZ + oz, x0, z0, minY, maxY, oakLeaves);
                            }
                }
            }
        }
    }

    private static void put(ChunkData data, int X, int y, int Z, int x0, int z0, int minY, int maxY, BlockData b) {
        int dx = X - x0, dz = Z - z0;
        if (dx < 0 || dx > 15 || dz < 0 || dz > 15 || y < minY || y >= maxY) return;
        data.setBlock(dx, y, dz, b);
    }

    /** the height of the top of the highest block at a place (the roof of a house), or Integer.MIN_VALUE when the square is not known; blocking: wait for the square */
    int surfaceY(int X, int Z, boolean blocking) {
        int tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
        Tile t = blocking ? store.get(tx, tz) : store.peek(tx, tz);
        if (t == null) return Integer.MIN_VALUE;
        int i = Tile.idx(X - tx * T, Z - tz * T);
        int g = SEA + t.ground[i], wh = t.wallH[i] & 0xFF, rh = t.roofH[i] & 0xFF;
        if (wh > 0 && hasZones() && inZone(X, Z)) wh = 0;
        return wh > 0 ? g + wh + rh : g;
    }

    /** what a block of a house is: 1 outer wall, 2 door, 4 ladder, 8 open hall (no ceilings); 0 when the square is not known */
    int flags(int X, int Z) {
        int tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
        Tile t = store.peek(tx, tz);
        if (t == null) return 0;
        int i = Tile.idx(X - tx * T, Z - tz * T);
        return (t.peri[i] != 0 ? 1 : 0) | (t.door[i] != 0 ? 2 : 0) | (t.ladder[i] != 0 ? 4 : 0) | (t.hall[i] != 0 ? 8 : 0);
    }

    /** one block of the ground: {y of the top block of the ground, height of a house wall here (0 = none), kind of surface, 1 if water}, or null when the square is not here yet */
    int[] cell(int X, int Z) {
        int tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
        Tile t = store.peek(tx, tz);
        if (t == null) return null;
        int i = Tile.idx(X - tx * T, Z - tz * T);
        int sf = t.surf[i];
        int wall = t.wallH[i] & 0xFF;
        if (wall > 0 && hasZones() && inZone(X, Z)) wall = 0;
        return new int[]{SEA + t.ground[i], wall, sf, sf == Tile.S_WATER ? 1 : 0, t.wallM[i] & 0xFF};
    }

    /** the first spawn: on St. Peter's Square (a place with paving and no house), as the website starts in Vatican City */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        int cx = 323, cz = 77;
        for (int r = 0; r < 160; r += 2) {
            for (int dz = -r; dz <= r; dz += 2) {
                for (int dx = -r; dx <= r; dx += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int X = cx + dx, Z = cz + dz, tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
                    Tile t = store.get(tx, tz);
                    if (t == null) continue;
                    int i = Tile.idx(X - tx * T, Z - tz * T);
                    if ((t.wallH[i] & 0xFF) == 0 && (t.surf[i] == Tile.S_PLAZA || t.surf[i] == Tile.S_GRAVEL)) {
                        return new Location(world, X + 0.5, SEA + t.ground[i] + 1, Z + 0.5);
                    }
                }
            }
        }
        return new Location(world, 0.5, SEA + 40, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo w, int x, int y, int z) {
                return Biome.PLAINS;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo w) {
                return List.of(Biome.PLAINS);
            }
        };
    }
}
