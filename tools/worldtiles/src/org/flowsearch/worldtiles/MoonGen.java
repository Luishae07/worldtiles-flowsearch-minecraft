package org.flowsearch.worldtiles;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/** The Moon, one block per metre across: x is the longitude and z the latitude (30.3 km per degree), the height comes from the NASA laser map. */
public final class MoonGen extends ChunkGenerator {
    static final int BASE = 180;      // y of a place that is at the reference height
    static final double V = 50;       // metres of real height for one block of height (the real range is 20 km, the game has 380 blocks)
    private final MoonData data;

    MoonGen(MoonData data) {
        this.data = data;
    }

    static double latOf(double z) { return Math.max(-90, Math.min(90, -z / MoonData.M_DEG)); }
    static double lonOf(double x) { return x / MoonData.M_DEG; }
    static int blockX(double lon) { return (int) Math.round(lon * MoonData.M_DEG); }
    static int blockZ(double lat) { return (int) Math.round(-lat * MoonData.M_DEG); }

    private static long hash(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    private static double unit(long h) { return (h & 0xFFFFFF) / (double) 0x1000000; }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** smooth little hills, a few blocks high */
    private static double noise(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = smooth(x - x0), fz = smooth(z - z0);
        double a = unit(hash(x0, z0)), b = unit(hash(x0 + 1, z0)), c = unit(hash(x0, z0 + 1)), d = unit(hash(x0 + 1, z0 + 1));
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    /** small craters (5 to 33 m wide): a bowl with a low rim. The NASA map is 470 m per pixel, so these are what it cannot show. */
    private static double craters(int X, int Z) {
        double off = 0;
        int cx0 = Math.floorDiv(X, 80), cz0 = Math.floorDiv(Z, 80);
        for (int cz = cz0 - 1; cz <= cz0 + 1; cz++) {
            for (int cx = cx0 - 1; cx <= cx0 + 1; cx++) {
                long h = hash(cx, cz);
                if (unit(h) > 0.42) continue;
                double px = cx * 80 + unit(h >>> 8) * 80, pz = cz * 80 + unit(h >>> 20) * 80;
                double r = 5 + unit(h >>> 32) * unit(h >>> 40) * 30, d = Math.hypot(X - px, Z - pz);
                double depth = r * 0.2;
                if (d < r) off -= depth * (1 - (d / r) * (d / r));
                else if (d < r * 1.3) off += depth * 0.35 * (1 - (d - r) / (r * 0.3));
            }
        }
        return off;
    }

    int heightY(int X, int Z) {
        double lat = latOf(Z), lon = lonOf(X);
        double y = BASE + data.heightM(lat, lon) / V + craters(X, Z) + (noise(X / 14.0, Z / 14.0) - 0.5) * 2.2;
        return (int) Math.max(3, Math.min(316, Math.round(y)));
    }

    private static Material surface(double b) {
        if (b < 0.26) return Material.SMOOTH_BASALT;
        if (b < 0.40) return Material.BLACKSTONE;
        if (b < 0.52) return Material.STONE;
        if (b < 0.64) return Material.ANDESITE;
        if (b < 0.78) return Material.LIGHT_GRAY_CONCRETE;
        return Material.CALCITE;
    }

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData out) {
        int minY = out.getMinHeight();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int X = chunkX * 16 + dx, Z = chunkZ * 16 + dz;
                int y = heightY(X, Z);
                double b = data.brightness(latOf(Z), lonOf(X)) + (unit(hash(X, Z)) - 0.5) * 0.14;
                out.setBlock(dx, minY, dz, Material.BEDROCK);
                out.setRegion(dx, minY + 1, dz, dx + 1, y - 2, dz + 1, Material.STONE);
                out.setRegion(dx, y - 2, dz, dx + 1, y, dz + 1, Material.ANDESITE);
                out.setBlock(dx, y, dz, surface(b));
            }
        }
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        int X = blockX(23.4730), Z = blockZ(0.6741); // Apollo 11, Tranquility Base
        return new Location(world, X + 0.5, heightY(X, Z) + 1, Z + 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo w, int x, int y, int z) { return Biome.THE_VOID; }

            @Override
            public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.THE_VOID); }
        };
    }
}
