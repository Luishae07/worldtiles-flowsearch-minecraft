package org.flowsearch.worldtiles;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Earth in the sky of the Moon. It stands where the real Earth is seen from the place where the player is (high up on the near side,
 * under the horizon on the far side) and shows the real day and night of this minute. It is made of small blocks (pixel art), different for
 * every player (only the player on the Moon sees his own), and moves with the player so that it stays as far away as the real one.
 */
final class EarthSky {
    private static final int HALF = 10;                      // the ball is 21 blocks across
    private static final double DIST = 85, CELL = 0.5;       // blocks away, and the size of one block (the ball is about 7 degrees wide: the real Earth is 2, but it would be too small)

    private record Pal(Material m, int r, int g, int b) {}

    private static final Pal[] PALETTE = {
            new Pal(Material.WHITE_CONCRETE, 207, 213, 214), new Pal(Material.LIGHT_GRAY_CONCRETE, 125, 125, 115), new Pal(Material.GRAY_CONCRETE, 55, 58, 62),
            new Pal(Material.BLACK_CONCRETE, 8, 10, 15), new Pal(Material.BLUE_CONCRETE, 45, 47, 143), new Pal(Material.LIGHT_BLUE_CONCRETE, 36, 137, 199),
            new Pal(Material.CYAN_CONCRETE, 21, 119, 136), new Pal(Material.GREEN_CONCRETE, 73, 91, 36), new Pal(Material.LIME_CONCRETE, 94, 168, 24),
            new Pal(Material.YELLOW_CONCRETE, 240, 175, 21), new Pal(Material.ORANGE_CONCRETE, 224, 97, 0), new Pal(Material.BROWN_CONCRETE, 96, 60, 32),
            new Pal(Material.BLUE_TERRACOTTA, 74, 60, 91), new Pal(Material.CYAN_TERRACOTTA, 86, 91, 91), new Pal(Material.GREEN_TERRACOTTA, 76, 83, 42),
            new Pal(Material.BROWN_TERRACOTTA, 77, 51, 36), new Pal(Material.BLACK_TERRACOTTA, 37, 22, 16), new Pal(Material.WHITE_TERRACOTTA, 209, 177, 161),
            new Pal(Material.LIGHT_GRAY_TERRACOTTA, 135, 106, 97), new Pal(Material.LAPIS_BLOCK, 31, 67, 140), new Pal(Material.DEEPSLATE, 55, 55, 58),
            new Pal(Material.BLUE_WOOL, 53, 57, 157), new Pal(Material.SNOW_BLOCK, 249, 254, 254)};

    private static final class Sky {
        ItemDisplay root;
        final List<BlockDisplay> cells = new ArrayList<>();
        double az = 1e9, el = 1e9;
        Location anchor;
        long colorsAt;
    }

    private final JavaPlugin plugin;
    private final MoonGen moon;
    private BufferedImage tex;
    private final List<int[]> cellIJ = new ArrayList<>();
    private BlockData[] colors;
    private long colorsAt;
    private final Map<UUID, Sky> skies = new HashMap<>();

    EarthSky(JavaPlugin plugin, MoonGen moon) {
        this.plugin = plugin;
        this.moon = moon;
        try {
            tex = ImageIO.read(new File("/srv/uploads/earthsite/tiles/planets/earth.jpg"));
        } catch (Exception e) {
            plugin.getLogger().warning("EarthSky: no Earth picture: " + e);
        }
        for (int j = HALF; j >= -HALF; j--) for (int i = -HALF; i <= HALF; i++) if (i * i + j * j <= (HALF + 0.5) * (HALF + 0.5)) cellIJ.add(new int[]{i, j});
    }

    void start() {
        if (tex == null) return;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(skies.keySet())) drop(id);
    }

    // ---- where the Sun and the Moon are (low precision, a fraction of a degree) ----

    /** {sub-lunar latitude, sub-lunar east longitude, sub-solar latitude, sub-solar east longitude} on Earth, in radians */
    private static double[] subPoints() {
        double d = System.currentTimeMillis() / 86400000.0 + 2440587.5 - 2451545.0;
        double eps = Math.toRadians(23.439 - 0.0000004 * d);
        double L = 280.460 + 0.9856474 * d, g = Math.toRadians(357.528 + 0.9856003 * d);
        double ls = Math.toRadians(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double alphaS = Math.atan2(Math.cos(eps) * Math.sin(ls), Math.cos(ls)), decS = Math.asin(Math.sin(eps) * Math.sin(ls));
        double Lm = 218.316 + 13.176396 * d, Mm = Math.toRadians(134.963 + 13.064993 * d), F = Math.toRadians(93.272 + 13.229350 * d);
        double lm = Math.toRadians(Lm + 6.289 * Math.sin(Mm)), bm = Math.toRadians(5.128 * Math.sin(F));
        double x = Math.cos(bm) * Math.cos(lm), y = Math.cos(bm) * Math.sin(lm) * Math.cos(eps) - Math.sin(bm) * Math.sin(eps), z = Math.cos(bm) * Math.sin(lm) * Math.sin(eps) + Math.sin(bm) * Math.cos(eps);
        double alphaM = Math.atan2(y, x), decM = Math.asin(z);
        double theta = Math.toRadians(280.46061837 + 360.98564736629 * d); // Greenwich sidereal time
        return new double[]{decM, norm(alphaM - theta), decS, norm(alphaS - theta)};
    }

    private static double norm(double a) {
        a = a % (2 * Math.PI);
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** the colour of the Earth in the picture at (u, v), both from -1 to 1 (v up); null outside the disc */
    private int[] earthColor(double u, double v, double[] sp) {
        double r = Math.hypot(u, v);
        if (r > 1) return null;
        double phi0 = sp[0], lam0 = sp[1];
        double c = Math.asin(r), lat, lon;
        if (r < 1e-9) {
            lat = phi0;
            lon = lam0;
        } else {
            lat = Math.asin(Math.cos(c) * Math.sin(phi0) + v * Math.sin(c) * Math.cos(phi0) / r);
            lon = lam0 + Math.atan2(u * Math.sin(c), r * Math.cos(phi0) * Math.cos(c) - v * Math.sin(phi0) * Math.sin(c));
        }
        int tx = Math.floorMod((int) ((Math.toDegrees(norm(lon)) + 180) / 360 * tex.getWidth()), tex.getWidth());
        int ty = Math.max(0, Math.min(tex.getHeight() - 1, (int) ((90 - Math.toDegrees(lat)) / 180 * tex.getHeight())));
        int rgb = tex.getRGB(tx, ty);
        // the sun: lit where the normal of the Earth faces it
        double lit = Math.cos(lat) * Math.cos(lon) * Math.cos(sp[2]) * Math.cos(sp[3]) + Math.cos(lat) * Math.sin(lon) * Math.cos(sp[2]) * Math.sin(sp[3]) + Math.sin(lat) * Math.sin(sp[2]);
        double t = Math.max(0, Math.min(1, (lit + 0.08) / 0.33));
        double f = 0.07 + 0.93 * t * t * (3 - 2 * t);
        int rr = (int) (((rgb >> 16) & 255) * f), gg = (int) (((rgb >> 8) & 255) * f), bb = (int) ((rgb & 255) * f);
        if (r > 0.9) { // a thin blue air around the edge
            double a = (r - 0.9) / 0.1 * 0.5 * Math.max(0.25, f);
            rr = (int) (rr * (1 - a) + 110 * a * f);
            gg = (int) (gg * (1 - a) + 170 * a * f);
            bb = (int) (bb * (1 - a) + 255 * a * f);
        }
        return new int[]{rr, gg, bb};
    }

    private static BlockData nearest(int r, int g, int b) {
        Pal best = PALETTE[0];
        long bd = Long.MAX_VALUE;
        for (Pal p : PALETTE) {
            long d = (long) (p.r - r) * (p.r - r) + (long) (p.g - g) * (p.g - g) + (long) (p.b - b) * (p.b - b);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best.m.createBlockData();
    }

    private void recolor() {
        double[] sp = subPoints();
        colors = new BlockData[cellIJ.size()];
        for (int n = 0; n < cellIJ.size(); n++) {
            int[] ij = cellIJ.get(n);
            long r = 0, g = 0, b = 0;
            int cnt = 0;
            for (int sy = 0; sy < 3; sy++) {
                for (int sx = 0; sx < 3; sx++) {
                    double u = (ij[0] + (sx - 1) / 3.0) / (HALF + 0.5), v = (ij[1] + (sy - 1) / 3.0) / (HALF + 0.5);
                    int[] c = earthColor(u, v, sp);
                    if (c != null) {
                        r += c[0];
                        g += c[1];
                        b += c[2];
                        cnt++;
                    }
                }
            }
            colors[n] = cnt == 0 ? Material.BLACK_CONCRETE.createBlockData() : nearest((int) (r / cnt), (int) (g / cnt), (int) (b / cnt));
        }
        colorsAt = System.currentTimeMillis();
    }

    // ---- the picture in the sky of each player ----

    private void tick() {
        if (colors == null || System.currentTimeMillis() - colorsAt > 300_000) recolor();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Sky s = skies.get(p.getUniqueId());
            World w = p.getWorld();
            if (w.getGenerator() != moon) {
                if (s != null) drop(p.getUniqueId());
                continue;
            }
            Location l = p.getLocation();
            double phi = Math.toRadians(MoonGen.latOf(l.getZ())), lam = Math.toRadians(MoonGen.lonOf(l.getX()));
            double sinEl = Math.cos(phi) * Math.cos(lam);
            if (sinEl < 0.04) { // the Earth is below the horizon (the far side of the Moon)
                if (s != null) drop(p.getUniqueId());
                continue;
            }
            double el = Math.asin(sinEl), az = Math.atan2(-Math.sin(lam), -Math.sin(phi) * Math.cos(lam));
            double dx = Math.sin(az) * Math.cos(el), dy = sinEl, dz = -Math.cos(az) * Math.cos(el); // east is +x, north is -z
            Location anchor = l.clone().add(dx * DIST, 1.6 + dy * DIST, dz * DIST);
            if (anchor.getY() > 380) anchor.setY(380);
            if (s == null) {
                s = create(p, anchor);
                if (s == null) continue;
                skies.put(p.getUniqueId(), s);
            }
            if (s.anchor == null || s.anchor.distanceSquared(anchor) > 0.6) {
                s.root.teleport(anchor, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
                s.anchor = anchor;
            }
            if (Math.abs(az - s.az) > 0.01 || Math.abs(el - s.el) > 0.01) {
                orient(s, dx, dy, dz);
                s.az = az;
                s.el = el;
            }
            if (s.colorsAt != colorsAt) {
                for (int n = 0; n < s.cells.size(); n++) s.cells.get(n).setBlock(colors[n]);
                s.colorsAt = colorsAt;
            }
        }
    }

    private Sky create(Player p, Location at) {
        try {
            Sky s = new Sky();
            World w = at.getWorld();
            s.root = w.spawn(at, ItemDisplay.class, e -> {
                e.setVisibleByDefault(false);
                e.setPersistent(false);
                e.setTeleportDuration(3);
            });
            p.showEntity(plugin, s.root);
            for (int n = 0; n < cellIJ.size(); n++) {
                final BlockData bd = colors[n];
                BlockDisplay c = w.spawn(at, BlockDisplay.class, e -> {
                    e.setBlock(bd);
                    e.setVisibleByDefault(false);
                    e.setPersistent(false);
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setViewRange(2f);
                });
                p.showEntity(plugin, c);
                s.root.addPassenger(c);
                s.cells.add(c);
            }
            s.colorsAt = colorsAt;
            return s;
        } catch (Exception e) {
            plugin.getLogger().warning("EarthSky for " + p.getName() + ": " + e);
            return null;
        }
    }

    /** the picture stands across the way to the Earth, with its top towards the sky */
    private void orient(Sky s, double fx, double fy, double fz) {
        // up in the picture: the up of the world, made square to the direction
        double ux = 0, uy = 1, uz = 0, dot = fy;
        ux -= dot * fx;
        uy -= dot * fy;
        uz -= dot * fz;
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ul < 1e-6) { // straight up: the top of the picture is the north
            ux = 0;
            uy = 0;
            uz = -1;
            ul = 1;
        }
        ux /= ul;
        uy /= ul;
        uz /= ul;
        // right of the viewer = forward x up
        double rx = fy * uz - fz * uy, ry = fz * ux - fx * uz, rz = fx * uy - fy * ux;
        // a ball, not a disc: every block sits on the front of a sphere, so the middle comes towards the player and the edge curves away
        double R = HALF + 0.5, size = CELL * 1.3; // a bit bigger than the step, so that no gaps show on the curve
        for (int n = 0; n < s.cells.size(); n++) {
            int[] ij = cellIJ.get(n);
            double k = Math.sqrt(Math.max(0, R * R - ij[0] * ij[0] - ij[1] * ij[1]));
            double px = (rx * ij[0] + ux * ij[1] - fx * k) * CELL - size / 2, py = (ry * ij[0] + uy * ij[1] - fy * k) * CELL - size / 2, pz = (rz * ij[0] + uz * ij[1] - fz * k) * CELL - size / 2;
            s.cells.get(n).setTransformation(new Transformation(new Vector3f((float) px, (float) py, (float) pz), new AxisAngle4f(), new Vector3f((float) size, (float) size, (float) size), new AxisAngle4f()));
        }
    }

    private void drop(UUID id) {
        Sky s = skies.remove(id);
        if (s == null) return;
        for (BlockDisplay c : s.cells) c.remove();
        if (s.root != null) s.root.remove();
    }
}
