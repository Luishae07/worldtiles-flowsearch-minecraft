package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * The details of a real street, from OpenStreetMap: street lamps, benches, bins, fire hydrants, bus shelters, street name signs, the dashed
 * line in the middle of the road, zebra crossings, house numbers at the real doors, and church bells at the real hours.
 * Everything is made of display entities, only near players.
 */
public final class StreetLife {
    private static final String API = "https://postpass.geofabrik.de/api/interpreter";
    private static final double CELL = 0.004;
    private static final String TAG = "wt_street";

    private enum Kind { LAMP, BENCH, BIN, HYDRANT, SHELTER, SIGN, CROSS, DASH, NUMBER, CHURCH, PARKED }

    private static final class Item {
        Kind kind;
        double x, z, ang; // ang: the direction of the road here, in radians
        String text;
        final List<Entity> ents = new ArrayList<>();
        boolean made;
        BlockDisplay head; // the lamp of a street lamp
        int lit = -1;      // 1 on, 0 off
    }

    private static final Material[] CAR_COLORS = {Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.WHITE_CONCRETE, Material.BLACK_CONCRETE, Material.LIGHT_GRAY_CONCRETE,
            Material.GRAY_CONCRETE, Material.GREEN_CONCRETE, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE};

    /** is the sun below the horizon at this place now (a little more than that: it is dark enough for lamps) */
    static boolean night(double lat, double lon) {
        java.time.ZonedDateTime t = java.time.ZonedDateTime.now(ZoneOffset.UTC);
        double decl = Math.toRadians(23.44) * Math.sin(2 * Math.PI * (284 + t.getDayOfYear()) / 365.0);
        double solar = t.getHour() + t.getMinute() / 60.0 + lon / 15.0, ha = Math.toRadians(15 * (solar - 12)), la = Math.toRadians(lat);
        double elev = Math.asin(Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(ha));
        return Math.toDegrees(elev) < 1.0;
    }

    private static final class Cell {
        final List<Item> items = new ArrayList<>();
        boolean done;
        long failedAt;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "street-fetch");
        t.setDaemon(true);
        return t;
    });
    private final List<Item> live = new ArrayList<>();
    private final Map<java.util.UUID, Integer> rungAt = new HashMap<>();

    StreetLife(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 140L, 20L);
    }

    void removeAll() {
        for (Item i : live) for (Entity e : i.ents) e.remove();
        live.clear();
        worker.shutdownNow();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
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
                Cell c = cells.get(k);
                if (c != null && (c.done || System.currentTimeMillis() - c.failedAt < 60000)) continue;
                if (!queued.add(k)) continue;
                final int fy = cy + dy, fx = cx + dx;
                worker.submit(() -> {
                    Cell nc = new Cell();
                    try {
                        fetch(nc, fy, fx);
                        nc.done = true;
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        nc.failedAt = System.currentTimeMillis();
                        plugin.getLogger().warning("street: " + e);
                    } finally {
                        cells.put(k, nc);
                        queued.remove(k);
                    }
                });
            }
        }
    }

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

    /** the features of an answer: {geometry text, properties} per line */
    private static List<String[]> features(String body) {
        List<String[]> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            int gi = line.indexOf("\"coordinates\":"), pi = line.indexOf("\"properties\":");
            if (gi < 0 || pi < 0) continue;
            out.add(new String[]{line.substring(gi, pi), line.substring(pi)});
        }
        return out;
    }

    private static Map<String, String> props(String s) {
        Map<String, String> m = new HashMap<>();
        Matcher pm = PROP.matcher(s);
        while (pm.find()) m.put(pm.group(1), pm.group(3) == null ? null : pm.group(3).replace("\\\"", "\"").replace("\\\\", "\\"));
        return m;
    }

    private static List<double[]> line(String geo) {
        List<double[]> pts = new ArrayList<>();
        Matcher m = PAIR.matcher(geo);
        while (m.find()) pts.add(new double[]{(Double.parseDouble(m.group(1)) - Cmds.REF_LON) * Cmds.M_LON, (Cmds.REF_LAT - Double.parseDouble(m.group(2))) * Cmds.M_LAT});
        return pts;
    }

    private void fetch(Cell c, int cy, int cx) throws Exception {
        double la0 = cy * CELL, lo0 = cx * CELL;
        String box = String.format(Locale.ROOT, "geom && ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326)", lo0, la0, lo0 + CELL, la0 + CELL);
        // the roads first: the benches, the shelters and the crossings turn to them
        List<List<double[]>> roads = new ArrayList<>();
        String roadSql = "SELECT tags->>'name' AS name, tags->>'highway' AS hw, geom FROM postpass_line WHERE " + box
                + " AND tags->>'highway' IN ('residential','primary','secondary','tertiary','living_street','unclassified','trunk','primary_link','secondary_link') LIMIT 500";
        for (String[] f : features(query(roadSql))) {
            Map<String, String> p = props(f[1]);
            List<double[]> pts = line(f[0]);
            if (pts.size() < 2) continue;
            roads.add(pts);
            String hw = p.get("hw") == null ? "" : p.get("hw");
            // the dashed line in the middle of the road (not on small streets)
            if (!hw.equals("living_street") && !hw.equals("unclassified") && !hw.equals("residential") || pts.size() > 1 && length(pts) > 40) {
                double carry = 0;
                for (int i = 0; i + 1 < pts.size(); i++) {
                    double[] a = pts.get(i), b = pts.get(i + 1);
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]), ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                    for (double d = 3 - carry; d < len; d += 8) {
                        Item it = new Item();
                        it.kind = Kind.DASH;
                        it.x = a[0] + Math.cos(ang) * d;
                        it.z = a[1] + Math.sin(ang) * d;
                        it.ang = ang;
                        it.text = hw;
                        c.items.add(it);
                    }
                    carry = (len + carry) % 8;
                }
            }
            // cars parked at the side of the road (not on the motorways), every 20 to 30 blocks, on both sides, not everywhere
            if (!hw.equals("trunk") && !hw.equals("primary_link") && !hw.equals("secondary_link")) {
                double carry2 = 8;
                for (int i = 0; i + 1 < pts.size(); i++) {
                    double[] a = pts.get(i), b = pts.get(i + 1);
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]), ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                    for (double d = carry2; d < len; d += 21) {
                        long h = Math.round(a[0] * 31 + a[1] * 17 + d * 7);
                        if (Math.floorMod(h, 5) >= 2) continue;
                        double side = Math.floorMod(h, 2) == 0 ? 1 : -1;
                        Item it = new Item();
                        it.kind = Kind.PARKED;
                        it.x = a[0] + Math.cos(ang) * d - Math.sin(ang) * 4.0 * side;
                        it.z = a[1] + Math.sin(ang) * d + Math.cos(ang) * 4.0 * side;
                        it.ang = ang;
                        it.text = String.valueOf(Math.floorMod(h, CAR_COLORS.length));
                        c.items.add(it);
                    }
                    carry2 = Math.max(4, 21 - ((len - carry2) % 21));
                }
            }
            if (p.get("name") != null) { // the street name sign in the middle of the way, at its side
                double total = length(pts), half = total / 2, run = 0;
                for (int i = 0; i + 1 < pts.size(); i++) {
                    double[] a = pts.get(i), b = pts.get(i + 1);
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (run + len >= half && len > 0) {
                        double t = (half - run) / len, ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                        Item it = new Item();
                        it.kind = Kind.SIGN;
                        it.x = a[0] + (b[0] - a[0]) * t - Math.sin(ang) * 5.0;
                        it.z = a[1] + (b[1] - a[1]) * t + Math.cos(ang) * 5.0;
                        it.ang = ang;
                        it.text = p.get("name");
                        c.items.add(it);
                        break;
                    }
                    run += len;
                }
            }
        }
        Thread.sleep(1000);
        String nodeSql = "SELECT tags->>'highway' AS hw, tags->>'amenity' AS am, tags->>'emergency' AS em, tags->>'shelter' AS sh, tags->>'crossing' AS cr, geom FROM postpass_point WHERE " + box
                + " AND (tags->>'highway' IN ('street_lamp','crossing','bus_stop') OR tags->>'amenity' IN ('bench','waste_basket') OR tags->>'emergency' = 'fire_hydrant') LIMIT 1200";
        for (String[] f : features(query(nodeSql))) {
            Map<String, String> p = props(f[1]);
            List<double[]> pt = line(f[0]);
            if (pt.isEmpty()) continue;
            Item it = new Item();
            it.x = pt.get(0)[0];
            it.z = pt.get(0)[1];
            it.ang = roadAngle(roads, it.x, it.z);
            String hw = p.get("hw"), am = p.get("am");
            if ("street_lamp".equals(hw)) it.kind = Kind.LAMP;
            else if ("crossing".equals(hw) && p.get("cr") != null && !p.get("cr").equals("no") && !p.get("cr").equals("unmarked")) it.kind = Kind.CROSS;
            else if ("bus_stop".equals(hw) && "yes".equals(p.get("sh"))) it.kind = Kind.SHELTER;
            else if ("bench".equals(am)) it.kind = Kind.BENCH;
            else if ("waste_basket".equals(am)) it.kind = Kind.BIN;
            else if ("fire_hydrant".equals(p.get("em"))) it.kind = Kind.HYDRANT;
            else continue;
            c.items.add(it);
        }
        Thread.sleep(1000);
        String adrSql = "SELECT tags->>'addr:housenumber' AS num, ST_PointOnSurface(geom) AS geom FROM postpass_polygon WHERE " + box + " AND tags ? 'addr:housenumber' AND tags ? 'building' LIMIT 700";
        for (String[] f : features(query(adrSql))) {
            Map<String, String> p = props(f[1]);
            List<double[]> pt = line(f[0]);
            if (pt.isEmpty() || p.get("num") == null) continue;
            Item it = new Item();
            it.kind = Kind.NUMBER;
            it.x = pt.get(0)[0];
            it.z = pt.get(0)[1];
            it.text = p.get("num");
            c.items.add(it);
        }
        Thread.sleep(1000);
        String chSql = "SELECT 'church' AS kind, ST_PointOnSurface(geom) AS geom FROM postpass_polygon WHERE " + box + " AND tags->>'amenity' = 'place_of_worship' AND tags->>'religion' = 'christian' LIMIT 20";
        for (String[] f : features(query(chSql))) {
            List<double[]> pt = line(f[0]);
            if (pt.isEmpty()) continue;
            Item it = new Item();
            it.kind = Kind.CHURCH;
            it.x = pt.get(0)[0];
            it.z = pt.get(0)[1];
            c.items.add(it);
        }
    }

    private static double length(List<double[]> pts) {
        double t = 0;
        for (int i = 0; i + 1 < pts.size(); i++) t += Math.hypot(pts.get(i + 1)[0] - pts.get(i)[0], pts.get(i + 1)[1] - pts.get(i)[1]);
        return t;
    }

    /** the direction of the nearest road at a place */
    private static double roadAngle(List<List<double[]>> roads, double x, double z) {
        double best = 1e18, ang = 0;
        for (List<double[]> r : roads) {
            for (int i = 0; i + 1 < r.size(); i++) {
                double[] a = r.get(i), b = r.get(i + 1);
                double dx = b[0] - a[0], dz = b[1] - a[1], l2 = dx * dx + dz * dz;
                if (l2 < 0.01) continue;
                double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / l2));
                double d = Math.hypot(x - (a[0] + dx * t), z - (a[1] + dz * t));
                if (d < best) {
                    best = d;
                    ang = Math.atan2(dz, dx);
                }
            }
        }
        return ang;
    }

    // ---- making the things ----

    private BlockDisplay box(Item it, World w, double y, Material m, double lx, double ly, double lz, double sx, double sy, double sz) {
        Quaternionf q = new Quaternionf().rotateY((float) -it.ang);
        Vector3f off = new Vector3f((float) (lx - sx / 2), (float) ly, (float) (lz - sz / 2)).rotate(q);
        BlockDisplay b = w.spawn(new Location(w, it.x, y, it.z), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBrightness(new Display.Brightness(13, 13));
            e.setViewRange(0.9f);
            e.setTransformation(new Transformation(off, q, new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
        it.ents.add(b);
        return b;
    }

    private void label(Item it, World w, double x, double y, double z, String text, int bg, float scale) {
        TextDisplay t = w.spawn(new Location(w, x, y, z), TextDisplay.class, e -> {
            e.text(Component.text(text, NamedTextColor.WHITE));
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(220, (bg >> 16) & 255, (bg >> 8) & 255, bg & 255));
            e.setViewRange(0.5f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
        it.ents.add(t);
    }

    /** what a thing is made of; false when it cannot stand there (a house, water, unknown ground) */
    private boolean make(Item it, World w) {
        int[] c = gen.cell((int) Math.floor(it.x), (int) Math.floor(it.z));
        if (c == null) return false;
        double y = c[0] + 1;
        switch (it.kind) {
            case LAMP -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.GRAY_CONCRETE, 0, 0, 0, 0.14, 4.6, 0.14);
                box(it, w, y, Material.GRAY_CONCRETE, 0.45, 4.5, 0, 1.0, 0.1, 0.1);
                it.head = box(it, w, y, Material.GRAY_CONCRETE, 0.9, 4.3, 0, 0.45, 0.2, 0.3); // the lamp: on at night, set by the loop
                it.lit = 0;
            }
            case PARKED -> {
                if (c[1] > 0 || c[3] == 1) return false;
                Material m = CAR_COLORS[Integer.parseInt(it.text)];
                box(it, w, y, m, 0, 0.3, 0, 4.1, 0.55, 1.75);
                box(it, w, y, Material.LIGHT_BLUE_STAINED_GLASS, -0.2, 0.85, 0, 2.1, 0.5, 1.55);
                box(it, w, y, m, -0.2, 1.35, 0, 2.2, 0.06, 1.65);
                for (int sx = -1; sx <= 1; sx += 2) for (int sz = -1; sz <= 1; sz += 2) box(it, w, y, Material.BLACK_CONCRETE, sx * 1.3, 0, sz * 0.8, 0.6, 0.6, 0.22);
            }
            case BENCH -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.OAK_PLANKS, 0, 0.45, 0, 1.6, 0.1, 0.5);
                box(it, w, y, Material.OAK_PLANKS, 0, 0.55, -0.25, 1.6, 0.45, 0.08);
                box(it, w, y, Material.BLACK_CONCRETE, -0.7, 0, 0, 0.08, 0.45, 0.45);
                box(it, w, y, Material.BLACK_CONCRETE, 0.7, 0, 0, 0.08, 0.45, 0.45);
            }
            case BIN -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.GREEN_CONCRETE, 0, 0, 0, 0.45, 0.8, 0.45);
                box(it, w, y, Material.BLACK_CONCRETE, 0, 0.8, 0, 0.52, 0.08, 0.52);
            }
            case HYDRANT -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.RED_CONCRETE, 0, 0, 0, 0.3, 0.7, 0.3);
                box(it, w, y, Material.RED_CONCRETE, 0, 0.45, 0, 0.5, 0.12, 0.14);
            }
            case SHELTER -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.LIGHT_BLUE_STAINED_GLASS, 0, 0, -0.6, 3.0, 2.2, 0.08);
                box(it, w, y, Material.LIGHT_BLUE_STAINED_GLASS, -1.5, 0, 0, 0.08, 2.2, 1.2);
                box(it, w, y, Material.GRAY_CONCRETE, 0, 2.2, 0, 3.3, 0.1, 1.5);
                box(it, w, y, Material.OAK_PLANKS, 0, 0.45, -0.35, 2.2, 0.1, 0.4);
            }
            case SIGN -> {
                if (c[1] > 0 || c[3] == 1) return false;
                box(it, w, y, Material.GRAY_CONCRETE, 0, 0, 0, 0.1, 2.6, 0.1);
                label(it, w, it.x, y + 2.9, it.z, it.text, 0x1a4f9c, 0.8f);
            }
            case DASH -> {
                if (c[1] > 0 || c[3] == 1 || c[2] != Tile.S_ASPHALT) return false;
                box(it, w, y + 0.02, Material.WHITE_CONCRETE, 0, 0, 0, 3.0, 0.02, 0.14);
            }
            case CROSS -> {
                if (c[1] > 0 || c[3] == 1) return false;
                for (int i = -4; i <= 4; i++) box(it, w, y + 0.03, Material.WHITE_CONCRETE, 0, 0, i * 0.9, 3.0, 0.02, 0.45);
            }
            case NUMBER -> {
                // the number is at the real door of the house: the nearest door block
                int bx = (int) Math.floor(it.x), bz = (int) Math.floor(it.z), dx0 = Integer.MIN_VALUE, dz0 = 0;
                double bd = 1e18;
                for (int dx = -12; dx <= 12; dx++) {
                    for (int dz = -12; dz <= 12; dz++) {
                        if ((gen.flags(bx + dx, bz + dz) & 2) == 0) continue;
                        double d = Math.hypot(dx, dz);
                        if (d < bd) {
                            bd = d;
                            dx0 = bx + dx;
                            dz0 = bz + dz;
                        }
                    }
                }
                if (dx0 == Integer.MIN_VALUE) return false;
                int[] dc = gen.cell(dx0, dz0);
                if (dc == null) return false;
                label(it, w, dx0 + 0.5, dc[0] + 3.0, dz0 + 0.5, it.text, 0x1a4f9c, 0.5f);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void tick() {
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        List<Player> players = earth.getPlayers();
        Set<Item> wanted = new HashSet<>();
        int made = 0;
        for (Player p : players) {
            Location pl = p.getLocation();
            want(Cmds.latOf(pl.getZ()), Cmds.lonOf(pl.getX()));
            int cy = (int) Math.floor(Cmds.latOf(pl.getZ()) / CELL), cx = (int) Math.floor(Cmds.lonOf(pl.getX()) / CELL);
            int dashes = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Cell c = cells.get(key(cy + dy, cx + dx));
                    if (c == null) continue;
                    for (Item it : c.items) {
                        if (it.kind == Kind.CHURCH) continue;
                        double d = Math.hypot(it.x - pl.getX(), it.z - pl.getZ());
                        double reach = it.kind == Kind.DASH ? 42 : it.kind == Kind.NUMBER ? 38 : it.kind == Kind.SIGN || it.kind == Kind.LAMP ? 60 : 50;
                        if (d > reach) continue;
                        if (it.kind == Kind.DASH && ++dashes > 110) continue;
                        wanted.add(it);
                        if (!it.made && made < 40) {
                            made++;
                            it.made = true; // tried once: a thing that cannot stand there is not tried again
                            if (make(it, earth)) live.add(it);
                            else it.ents.clear();
                        }
                    }
                }
            }
        }
        for (java.util.Iterator<Item> it = live.iterator(); it.hasNext(); ) {
            Item i = it.next();
            if (wanted.contains(i)) continue;
            double near = 1e18;
            for (Player p : players) near = Math.min(near, Math.hypot(i.x - p.getLocation().getX(), i.z - p.getLocation().getZ()));
            if (near < 75) continue;
            for (Entity e : i.ents) e.remove();
            i.ents.clear();
            i.made = false;
            it.remove();
        }
        // the street lamps burn only when it is dark where they are
        for (Item i : live) {
            if (i.kind != Kind.LAMP || i.head == null || !i.head.isValid()) continue;
            int want = night(Cmds.latOf(i.z), Cmds.lonOf(i.x)) ? 1 : 0;
            if (want != i.lit) {
                i.lit = want;
                i.head.setBlock((want == 1 ? Material.SEA_LANTERN : Material.GRAY_CONCRETE).createBlockData());
            }
        }
        bells(earth, players);
    }

    // ---- the church bells ----

    private void bells(World earth, List<Player> players) {
        if (players.isEmpty()) return;
        for (Player p : players) {
            Location l = p.getLocation();
            ZoneOffset off = ZoneOffset.ofHours(Math.max(-12, Math.min(14, (int) Math.round(Cmds.lonOf(l.getX()) / 15))));
            OffsetDateTime t = OffsetDateTime.now(off);
            if (t.getMinute() != 0 || t.getSecond() > 2) continue;
            int hour = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
            if (t.getHour() < 7 || t.getHour() > 21) continue; // not at night
            int hourKey = t.getDayOfYear() * 24 + t.getHour();
            Integer rung = rungAt.get(p.getUniqueId());
            if (rung != null && rung == hourKey) continue; // once an hour
            rungAt.put(p.getUniqueId(), hourKey);
            int cy = (int) Math.floor(Cmds.latOf(l.getZ()) / CELL), cx = (int) Math.floor(Cmds.lonOf(l.getX()) / CELL);
            Item best = null;
            double bd = 220;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Cell c = cells.get(key(cy + dy, cx + dx));
                    if (c == null) continue;
                    for (Item it : c.items) {
                        if (it.kind != Kind.CHURCH) continue;
                        double d = Math.hypot(it.x - l.getX(), it.z - l.getZ());
                        if (d < bd) {
                            bd = d;
                            best = it;
                        }
                    }
                }
            }
            if (best == null) continue;
            final Item church = best;
            final float vol = (float) (1.5 - bd / 160.0);
            for (int n = 0; n < hour; n++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) p.playSound(new Location(earth, church.x, l.getY() + 20, church.z), Sound.BLOCK_BELL_RESONATE, Math.max(0.3f, vol), 0.6f);
                }, n * 50L);
            }
        }
    }
}
