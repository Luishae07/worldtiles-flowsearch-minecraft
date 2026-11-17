package org.flowsearch.worldtiles;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real traffic lights (OpenStreetMap highway=traffic_signals): a pole with four lamps at each crossing. One street axis has green
 * while the other has red, then they change; the villagers' cars stop at red.
 */
public final class Signals {
    private static final String API = "https://postpass.geofabrik.de/api/interpreter";
    private static final double CELL = 0.004;
    private static final int CYCLE = 30; // seconds: north-south green 0-13, yellow 13-15; east-west green 15-28, yellow 28-30

    static final class Signal {
        double x, z;
        int phase, groundY;
        BlockDisplay pole;
        final BlockDisplay[] lamp = new BlockDisplay[4]; // north, east, south, west
        int shown = -1; // what the lamps show now (ns state * 3 + ew state)
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, List<Signal>> cells = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> failed = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "signals-fetch");
        t.setDaemon(true);
        return t;
    });
    private final List<Signal> shown = new ArrayList<>();

    Signals(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 120L, 10L);
    }

    void removeAll() {
        for (Signal s : shown) drop(s);
        shown.clear();
        worker.shutdownNow();
    }

    // ---- the lights ----

    /** 0 green, 1 yellow, 2 red for the north-south axis (ns = true) or the east-west axis */
    static int state(Signal s, boolean ns) {
        double t = ((System.currentTimeMillis() / 1000.0) + s.phase) % CYCLE;
        if (ns) return t < 13 ? 0 : t < 15 ? 1 : 2;
        return t >= 15 && t < 28 ? 0 : t >= 28 && t < 30 ? 1 : 2;
    }

    /** must a car at (x,z) driving at this heading stop for a light just ahead? */
    boolean mustStop(double x, double z, double heading) {
        double th = Math.toRadians(heading), fx = Math.sin(th), fz = -Math.cos(th);
        boolean ns = Math.abs(Math.cos(th)) >= 0.7071;
        int cy = (int) Math.floor(Cmds.latOf(z) / CELL), cx = (int) Math.floor(Cmds.lonOf(x) / CELL);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                List<Signal> l = cells.get(key(cy + dy, cx + dx));
                if (l == null) continue;
                for (Signal s : l) {
                    double ox = s.x - x, oz = s.z - z, ahead = ox * fx + oz * fz, side = Math.abs(ox * -fz + oz * fx);
                    if (ahead < 3 || ahead > 16 || side > 7) continue;
                    int st = state(s, ns);
                    if (st == 2 || (st == 1 && ahead > 7)) return true;
                }
            }
        }
        return false;
    }

    // ---- the data ----

    private static long key(int cy, int cx) {
        return ((long) cy << 32) ^ (cx & 0xFFFFFFFFL);
    }

    private void want(double lat, double lon) {
        int cy = (int) Math.floor(lat / CELL), cx = (int) Math.floor(lon / CELL);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                long k = key(cy + dy, cx + dx);
                if (cells.containsKey(k)) continue;
                Long f = failed.get(k);
                if (f != null && System.currentTimeMillis() - f < 60000) continue;
                if (!queued.add(k)) continue;
                final int fy = cy + dy, fx = cx + dx;
                worker.submit(() -> {
                    try {
                        fetch(k, fy, fx);
                        Thread.sleep(1200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed.put(k, System.currentTimeMillis());
                        plugin.getLogger().warning("signals: " + e);
                    } finally {
                        queued.remove(k);
                    }
                });
            }
        }
    }

    private static final Pattern PAIR = Pattern.compile("\"coordinates\":\\[(-?[0-9.]+),(-?[0-9.]+)\\]");

    private void fetch(long k, int cy, int cx) throws Exception {
        double la0 = cy * CELL, lo0 = cx * CELL;
        String sql = String.format(Locale.ROOT, "SELECT 'signal' AS kind, geom FROM postpass_point WHERE geom && ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326) AND tags->>'highway' = 'traffic_signals' LIMIT 500",
                lo0, la0, lo0 + CELL, la0 + CELL);
        HttpRequest rq = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "WorldTiles-minecraft-plugin")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(sql, StandardCharsets.UTF_8))).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Postpass status " + r.statusCode());
        List<Signal> list = new ArrayList<>();
        for (String line : r.body().split("\n")) {
            Matcher m = PAIR.matcher(line);
            if (!m.find()) continue;
            double x = Cmds.blockX(Double.parseDouble(m.group(1))) + 0.5, z = Cmds.blockZ(Double.parseDouble(m.group(2))) + 0.5;
            boolean dup = false;
            for (Signal o : list) if (Math.hypot(o.x - x, o.z - z) < 12) dup = true; // several nodes at one crossing: one pole
            if (dup) continue;
            Signal s = new Signal();
            s.x = x;
            s.z = z;
            s.phase = Math.floorMod((int) (Math.round(x) * 31 + Math.round(z) * 17), CYCLE);
            list.add(s);
        }
        cells.put(k, list);
    }

    // ---- the poles ----

    private BlockDisplay box(World w, Location at, Material m, double x, double y, double z, double sx, double sy, double sz) {
        return w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(0.8f);
            e.setTransformation(new Transformation(new Vector3f((float) (x - sx / 2), (float) (y - sy / 2), (float) (z - sz / 2)), new Quaternionf(), new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
    }

    private void drop(Signal s) {
        if (s.pole != null) s.pole.remove();
        for (int i = 0; i < 4; i++) if (s.lamp[i] != null) s.lamp[i].remove();
        s.pole = null;
        java.util.Arrays.fill(s.lamp, null);
        s.shown = -1;
    }

    private static Material colour(int st) {
        return st == 0 ? Material.LIME_CONCRETE : st == 1 ? Material.YELLOW_CONCRETE : Material.RED_CONCRETE;
    }

    private void tick() {
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        List<Player> players = earth.getPlayers();
        for (Player p : players) want(Cmds.latOf(p.getLocation().getZ()), Cmds.lonOf(p.getLocation().getX()));
        for (java.util.Iterator<Signal> it = shown.iterator(); it.hasNext(); ) {
            Signal s = it.next();
            boolean near = false;
            for (Player p : players) if (Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) < 130) near = true;
            if (!near) {
                drop(s);
                it.remove();
            }
        }
        for (List<Signal> l : cells.values()) {
            for (Signal s : l) {
                boolean near = false;
                for (Player p : players) if (Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) < 90) near = true;
                if (!near) continue;
                if (s.pole == null) {
                    int[] c = gen.cell((int) Math.floor(s.x), (int) Math.floor(s.z));
                    if (c == null) continue;
                    s.groundY = c[0] + 1;
                    // the pole stands beside the crossing, not in the middle of it
                    double px = s.x + 3, pz = s.z + 3;
                    Location at = new Location(earth, px, s.groundY, pz);
                    s.pole = box(earth, at, Material.GRAY_CONCRETE, 0, 2.5, 0, 0.25, 5, 0.25);
                    double[][] d = {{0, -0.2, 0.55, 0.15}, {0.2, 0, 0.15, 0.55}, {0, 0.2, 0.55, 0.15}, {-0.2, 0, 0.15, 0.55}};
                    for (int i = 0; i < 4; i++) s.lamp[i] = box(earth, at, Material.RED_CONCRETE, d[i][0] * 1.0, 4.6, d[i][1] * 1.0, d[i][2], 0.55, d[i][3]);
                    shown.add(s);
                }
                int ns = state(s, true), ew = state(s, false), code = ns * 3 + ew;
                if (code != s.shown) {
                    s.shown = code;
                    s.lamp[0].setBlock(colour(ns).createBlockData());
                    s.lamp[2].setBlock(colour(ns).createBlockData());
                    s.lamp[1].setBlock(colour(ew).createBlockData());
                    s.lamp[3].setBlock(colour(ew).createBlockData());
                }
            }
        }
    }
}
