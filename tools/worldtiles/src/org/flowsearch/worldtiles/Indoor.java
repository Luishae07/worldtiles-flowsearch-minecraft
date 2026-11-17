package org.flowsearch.worldtiles;

import org.bukkit.Material;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The insides of buildings, from OpenStreetMap indoor mapping (rooms with a level, and doors): the walls of the rooms are put into the houses
 * that the tile service made (a floor every 4 blocks, level 0 on the ground). Only places where somebody mapped rooms get walls.
 */
public final class Indoor {
    private static final String API = "https://postpass.geofabrik.de/api/interpreter";
    private static final double CELL = 0.004;
    private static final Logger LOG = Logger.getLogger("WorldTiles");

    static final Material[] MATS = {Material.WHITE_CONCRETE, Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.MAGENTA_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA, Material.PINK_TERRACOTTA, Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.BLUE_TERRACOTTA,
            Material.GREEN_TERRACOTTA, Material.RED_TERRACOTTA, Material.LIGHT_GRAY_CONCRETE};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Long> state = new ConcurrentHashMap<>();            // cell -> time loaded (>0) or time failed (<0)
    private final Map<Long, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private final Map<Long, int[]> walls = new ConcurrentHashMap<>();           // block -> wall entries ((level + 8) << 6 | material)
    private final Set<Long> doors = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "indoor-fetch");
        t.setDaemon(true);
        return t;
    });

    void stop() {
        worker.shutdownNow();
    }

    private static long cellKey(double lat, double lon) {
        return ((long) (int) Math.floor(lat / CELL) << 32) ^ ((int) Math.floor(lon / CELL) & 0xFFFFFFFFL);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private CompletableFuture<Void> request(int cy, int cx) {
        long k = ((long) cy << 32) ^ (cx & 0xFFFFFFFFL);
        Long st = state.get(k);
        if (st != null && (st > 0 || System.currentTimeMillis() + st < 120000)) return CompletableFuture.completedFuture(null);
        return pending.computeIfAbsent(k, kk -> {
            CompletableFuture<Void> f = new CompletableFuture<>();
            worker.submit(() -> {
                try {
                    fetch(cy, cx);
                    state.put(kk, System.currentTimeMillis());
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    state.put(kk, -System.currentTimeMillis());
                    LOG.warning("Indoor: " + e);
                } finally {
                    pending.remove(kk);
                    f.complete(null);
                }
            });
            return f;
        });
    }

    /** get the data of the cells around a place in the background (so that the chunks made later have the rooms) */
    void want(double lat, double lon) {
        int cy = (int) Math.floor(lat / CELL), cx = (int) Math.floor(lon / CELL);
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) request(cy + dy, cx + dx);
    }

    /** the data of the place of this block must be there: wait a little for it (a chunk is being made) */
    void ensure(int X, int Z) {
        double lat = Cmds.latOf(Z), lon = Cmds.lonOf(X);
        int cy = (int) Math.floor(lat / CELL), cx = (int) Math.floor(lon / CELL);
        Long st = state.get(((long) cy << 32) ^ (cx & 0xFFFFFFFFL));
        if (st != null) return;
        try {
            request(cy, cx).get(4, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    int[] at(int X, int Z) {
        return walls.get(pack(X, Z));
    }

    boolean isDoor(int X, int Z) {
        return doors.contains(pack(X, Z));
    }

    // ---- the data ----

    private String query(String sql) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "WorldTiles-minecraft-plugin")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(sql, StandardCharsets.UTF_8))).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Postpass status " + r.statusCode());
        return r.body();
    }

    private static final Pattern PAIR = Pattern.compile("\\[(-?[0-9]+\\.[0-9]+),(-?[0-9]+\\.[0-9]+)\\]");
    private static final Pattern PROP = Pattern.compile("\"(\\w+)\": (null|\"((?:[^\"\\\\]|\\\\.)*)\")");

    private void fetch(int cy, int cx) throws Exception {
        double la0 = cy * CELL, la1 = la0 + CELL, lo0 = cx * CELL, lo1 = lo0 + CELL;
        String box = String.format(Locale.ROOT, "geom && ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326)", lo0, la0, lo1, la1);
        String doorSql = "SELECT 'door' AS kind, NULL AS lvl, geom FROM postpass_point WHERE " + box + " AND (tags->>'indoor' = 'door' OR (tags ? 'door' AND tags ? 'level')) LIMIT 2000";
        String roomSql = "SELECT COALESCE(tags->>'shop', tags->>'amenity', '') AS kind, tags->>'level' AS lvl, geom FROM postpass_polygon WHERE " + box + " AND tags->>'indoor' = 'room' LIMIT 3000";
        for (String line : query(doorSql).split("\n")) {
            Matcher m = PAIR.matcher(line);
            if (!line.contains("\"Point\"") || !m.find()) continue;
            doors.add(pack(Cmds.blockX(Double.parseDouble(m.group(1))), Cmds.blockZ(Double.parseDouble(m.group(2)))));
        }
        Thread.sleep(1200);
        int rooms = 0;
        for (String line : query(roomSql).split("\n")) {
            int gi = line.indexOf("\"coordinates\":");
            int pi = line.indexOf("\"properties\":");
            if (gi < 0 || pi < 0) continue;
            String kind = "", lvl = null;
            Matcher pm = PROP.matcher(line.substring(pi));
            while (pm.find()) {
                if (pm.group(1).equals("kind")) kind = pm.group(3) == null ? "" : pm.group(3);
                if (pm.group(1).equals("lvl")) lvl = pm.group(3);
            }
            int level = 0;
            if (lvl != null) {
                Matcher lm = Pattern.compile("-?[0-9]+").matcher(lvl);
                if (lm.find()) level = Math.max(-8, Math.min(40, Integer.parseInt(lm.group())));
            }
            int mat = kind.isEmpty() ? 13 : 1 + Math.floorMod(kind.hashCode(), 12);
            int entry = ((level + 8) << 6) | mat;
            String geo = line.substring(gi, pi);
            Matcher m = PAIR.matcher(geo);
            List<int[]> ring = new ArrayList<>();
            while (m.find()) {
                ring.add(new int[]{Cmds.blockX(Double.parseDouble(m.group(1))), Cmds.blockZ(Double.parseDouble(m.group(2)))});
                boolean end = m.end() < geo.length() && geo.charAt(m.end()) == ']';
                if (end) {
                    outline(ring, entry);
                    ring = new ArrayList<>();
                }
            }
            rooms++;
        }
        if (rooms > 0) LOG.info("Indoor: " + rooms + " rooms in the cell " + String.format(Locale.ROOT, "%.3f,%.3f", la0, lo0));
    }

    /** the cells along the outline of a room are walls; a room without a mapped door gets a gap of two blocks */
    private void outline(List<int[]> ring, int entry) {
        List<Long> cells = new ArrayList<>();
        for (int i = 0; i + 1 < ring.size(); i++) {
            int[] a = ring.get(i), b = ring.get(i + 1);
            int n = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) * 2 + 1;
            for (int s = 0; s <= n; s++) {
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * (double) s / n), z = (int) Math.round(a[1] + (b[1] - a[1]) * (double) s / n);
                long k = pack(x, z);
                if (cells.isEmpty() || cells.get(cells.size() - 1) != k) cells.add(k);
            }
        }
        if (cells.isEmpty()) return;
        boolean hasDoor = false;
        for (long k : cells) {
            int x = (int) (k >> 32), z = (int) k;
            for (int dx = -1; dx <= 1 && !hasDoor; dx++) for (int dz = -1; dz <= 1; dz++) if (doors.contains(pack(x + dx, z + dz))) hasDoor = true;
            walls.merge(k, new int[]{entry}, (o, n) -> {
                for (int v : o) if (v == n[0]) return o;
                int[] r = java.util.Arrays.copyOf(o, o.length + 1);
                r[o.length] = n[0];
                return r;
            });
        }
        if (!hasDoor && cells.size() > 6) {
            int mid = cells.size() / 2;
            doors.add(cells.get(mid));
            doors.add(cells.get(mid + 1 < cells.size() ? mid + 1 : mid - 1));
        }
    }
}
