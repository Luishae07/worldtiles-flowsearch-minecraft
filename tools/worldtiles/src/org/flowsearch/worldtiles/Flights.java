package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real flights (adsb.fi open data). Two things:
 *  - the sky: around every player the real planes that are above the horizon show as small plane models with a name tag, in the right direction;
 *  - /ride <flight>: flies you beside a real flight at its real speed and heading, with a real-size model of the plane next to you.
 * While riding, the height of the plane is divided by 50 (the game draws the ground only a few hundred blocks down, a flight at 10 km would show only sky).
 */
final class Flights implements CommandExecutor, Listener {
    private static final double ALT_SCALE = 50.0;
    private static final String BASE = "https://opendata.adsb.fi/api/v2";

    private static final class Ac {
        String hex = "", call = "", type = "", desc = "";
        double lat, lon, altM, gs, track, vr;
        boolean ground;
        long at;
    }

    private static final class Region {
        volatile List<Ac> list = new ArrayList<>();
        volatile long at;
        volatile boolean busy;
    }

    private static final class Proxy {
        BlockDisplay body, wings;
        TextDisplay label;
    }

    private static final class Ride {
        Player p;
        Ac ac;
        ItemStack savedChest;
        int savedView;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<float[]> layout = new ArrayList<>();
        long ticks, lostSince;
        boolean polling, cabin, allowFlight, ready;
        int sneak;
        double y; // the smoothed height of the plane in the game
        // walking in the cabin: where the plane is (it flies at a scenic speed, a part of the real one), and the invisible floor under the player
        double cx, cy, cz, hdg, speed = 0.10;
        final Map<Long, int[]> fake = new HashMap<>();
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final TileStore store;
    private final NamespacedKey wingsKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<String, Region> regions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Proxy>> sky = new HashMap<>();
    private final Map<UUID, Ride> rides = new HashMap<>();
    private long lastReq;

    Flights(JavaPlugin plugin, TileGen gen, TileStore store) {
        this.plugin = plugin;
        this.gen = gen;
        this.store = store;
        this.wingsKey = new NamespacedKey(plugin, "pilot_wings");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::skyTick, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::rideTick, 60L, 1L);
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(sky.keySet())) dropSky(id);
        for (Ride r : new ArrayList<>(rides.values())) endRide(r, null);
    }

    private static Component say(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    // ---- the data ----

    private static String str(String o, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":\"([^\"]*)\"").matcher(o);
        return m.find() ? m.group(1).trim() : "";
    }

    private static double num(String o, String k, double def) {
        Matcher m = Pattern.compile("\"" + k + "\":(-?[0-9.]+)").matcher(o);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }

    private List<Ac> fetch(String path) throws Exception {
        synchronized (this) { // the service allows about one question a second
            long d = 1100 - (System.currentTimeMillis() - lastReq);
            if (d > 0) Thread.sleep(d);
            lastReq = System.currentTimeMillis();
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(10)).header("User-Agent", "flowsearch-worldtiles/1.0").build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("status " + r.statusCode());
        List<Ac> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String[] parts = r.body().split("\\{\"hex\"");
        for (int i = 1; i < parts.length; i++) {
            String o = "{\"hex\"" + parts[i];
            Ac a = new Ac();
            a.hex = str(o, "hex");
            a.call = str(o, "flight");
            a.type = str(o, "t");
            a.desc = str(o, "desc");
            a.lat = num(o, "lat", Double.NaN);
            a.lon = num(o, "lon", Double.NaN);
            if (Double.isNaN(a.lat) || Double.isNaN(a.lon) || a.hex.isEmpty()) continue;
            a.ground = o.contains("\"alt_baro\":\"ground\"");
            a.altM = a.ground ? 0 : num(o, "alt_baro", 0) * 0.3048;
            a.gs = num(o, "gs", 0) * 0.514444;
            a.track = num(o, "track", 0);
            a.vr = num(o, "baro_rate", 0) * 0.00508;
            a.at = now - (long) (num(o, "seen_pos", 0) * 1000);
            out.add(a);
        }
        return out;
    }

    private Region region(double lat, double lon) {
        String k = Math.round(lat * 2) + "," + Math.round(lon * 2);
        Region g = regions.computeIfAbsent(k, kk -> new Region());
        if (System.currentTimeMillis() - g.at > 6000 && !g.busy) {
            g.busy = true;
            double clat = Math.round(lat * 2) / 2.0, clon = Math.round(lon * 2) / 2.0;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    g.list = fetch(String.format(Locale.ROOT, "/lat/%.2f/lon/%.2f/dist/70", clat, clon));
                    g.at = System.currentTimeMillis();
                } catch (Exception e) {
                    g.at = System.currentTimeMillis() - 3000; // try again in a few seconds
                } finally {
                    g.busy = false;
                }
            });
        }
        return g;
    }

    /** where a flight is now (blocks), counting from its last report; the height is scaled for the ride and true for the sky */
    private static double[] pos(Ac a, boolean scaled) {
        double dt = Math.max(0, Math.min(30, (System.currentTimeMillis() - a.at) / 1000.0));
        double tr = Math.toRadians(a.track), dx = Math.sin(tr) * a.gs * dt, dz = -Math.cos(tr) * a.gs * dt;
        double x = (a.lon - Cmds.REF_LON) * Cmds.M_LON + dx, z = (Cmds.REF_LAT - a.lat) * Cmds.M_LAT + dz;
        double alt = Math.max(0, a.altM + a.vr * dt);
        return new double[]{x, TileGen.SEA + (scaled ? alt / ALT_SCALE : alt), z};
    }

    // ---- the sky ----

    private void skyTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean earth = p.getWorld().getGenerator() == gen;
            if (!earth || rides.containsKey(p.getUniqueId())) {
                if (sky.containsKey(p.getUniqueId())) dropSky(p.getUniqueId());
                continue;
            }
            Location l = p.getLocation();
            Region g = region(Cmds.latOf(l.getZ()), Cmds.lonOf(l.getX()));
            updateSky(p, g.list);
        }
    }

    private List<Object[]> visible(Player p, List<Ac> list, double maxKm, double minElDeg) {
        Location eye = p.getEyeLocation();
        List<Object[]> out = new ArrayList<>();
        for (Ac a : list) {
            if (a.ground || a.altM < 250) continue;
            double[] w = pos(a, false);
            double rx = w[0] - eye.getX(), ry = w[1] - eye.getY(), rz = w[2] - eye.getZ(), h = Math.hypot(rx, rz);
            if (h > maxKm * 1000) continue;
            double el = Math.toDegrees(Math.atan2(ry, h));
            if (el < minElDeg) continue;
            out.add(new Object[]{a, rx, ry, rz, h, el});
        }
        out.sort(Comparator.comparingDouble(o -> (double) o[4]));
        return out;
    }

    private void updateSky(Player p, List<Ac> list) {
        Map<String, Proxy> mine = sky.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        Location eye = p.getEyeLocation();
        List<Object[]> vis = visible(p, list, 90, 1.5);
        if (vis.size() > 25) vis = vis.subList(0, 25);
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Object[] o : vis) {
            Ac a = (Ac) o[0];
            double rx = (double) o[1], ry = (double) o[2], rz = (double) o[3];
            double d = Math.sqrt(rx * rx + ry * ry + rz * rz);
            Location at = eye.clone().add(rx / d * 90, ry / d * 90, rz / d * 90);
            keep.add(a.hex);
            Proxy x = mine.get(a.hex);
            if (x == null) {
                x = makeProxy(p, at);
                mine.put(a.hex, x);
            }
            orientProxy(x, a);
            x.body.teleport(at);
            x.wings.teleport(at);
            x.label.teleport(at.clone().add(0, 3.2, 0));
            double km = Math.hypot(rx, rz) / 1000;
            x.label.text(Component.text((a.call.isEmpty() ? a.hex : a.call) + "  " + a.type + "\n" + Math.round(a.altM / 100) / 10.0 + " km up, " + String.format(Locale.ROOT, "%.0f", km) + " km away, " + Math.round(a.gs * 3.6) + " km/h", NamedTextColor.WHITE));
        }
        for (String h : new ArrayList<>(mine.keySet())) {
            if (keep.contains(h)) continue;
            Proxy x = mine.remove(h);
            x.body.remove();
            x.wings.remove();
            x.label.remove();
        }
    }

    private Proxy makeProxy(Player p, Location at) {
        World w = at.getWorld();
        Proxy x = new Proxy();
        x.body = w.spawn(at, BlockDisplay.class, e -> init(e, Material.WHITE_CONCRETE, 30));
        x.wings = w.spawn(at, BlockDisplay.class, e -> init(e, Material.LIGHT_GRAY_CONCRETE, 30));
        x.label = w.spawn(at, TextDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setTeleportDuration(40);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(2f);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(3.2f, 3.2f, 3.2f), new Quaternionf()));
        });
        p.showEntity(plugin, x.body);
        p.showEntity(plugin, x.wings);
        p.showEntity(plugin, x.label);
        return x;
    }

    private void init(BlockDisplay e, Material m, int tp) {
        e.setBlock(m.createBlockData());
        e.setVisibleByDefault(false);
        e.setPersistent(false);
        e.setTeleportDuration(tp);
        e.setInterpolationDuration(tp);
        e.setBrightness(new Display.Brightness(15, 15));
        e.setViewRange(2f);
    }

    /** the little plane points the way the real one flies (about 2 blocks long: a real plane would be an invisible dot at this distance) */
    private void orientProxy(Proxy x, Ac a) {
        float yaw = (float) Math.toRadians(a.track + 180), pitch = (float) Math.toRadians(a.gs > 5 ? -Math.toDegrees(Math.atan2(a.vr, a.gs)) : 0);
        Quaternionf q = new Quaternionf().rotateY(-yaw).rotateX(pitch);
        set(x.body, q, 0.22f, 0.22f, 2.2f, 0, 0, 0);
        set(x.wings, q, 2.0f, 0.06f, 0.45f, 0, 0.02f, 0.15f);
    }

    private static void set(BlockDisplay d, Quaternionf q, float sx, float sy, float sz, float ox, float oy, float oz) {
        Vector3f off = new Vector3f(ox - sx / 2, oy - sy / 2, oz - sz / 2).rotate(q);
        d.setTransformation(new Transformation(off, q, new Vector3f(sx, sy, sz), new Quaternionf()));
    }

    private void dropSky(UUID id) {
        Map<String, Proxy> mine = sky.remove(id);
        if (mine == null) return;
        for (Proxy x : mine.values()) {
            x.body.remove();
            x.wings.remove();
            x.label.remove();
        }
    }

    // ---- commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("flights")) {
            if (p.getWorld().getGenerator() != gen) {
                p.sendMessage(say("Real flights are for Earth.", NamedTextColor.YELLOW));
                return true;
            }
            Location l = p.getLocation();
            List<Object[]> v = visible(p, region(Cmds.latOf(l.getZ()), Cmds.lonOf(l.getX())).list, 120, -90);
            if (v.isEmpty()) {
                p.sendMessage(say("No flights around yet (the list is made every few seconds). Try again, or go near a big airport.", NamedTextColor.YELLOW));
                return true;
            }
            p.sendMessage(say("Real flights near you (/ride <name> flies you beside one):", NamedTextColor.GOLD));
            for (int i = 0; i < Math.min(10, v.size()); i++) {
                Ac a = (Ac) v.get(i)[0];
                p.sendMessage(say(String.format(Locale.ROOT, "  %-8s %-5s %5.1f km up  %3.0f km away  %d km/h", a.call.isEmpty() ? a.hex : a.call, a.type, a.altM / 1000, (double) v.get(i)[4] / 1000, Math.round(a.gs * 3.6)), NamedTextColor.WHITE));
            }
            return true;
        }
        // /ride
        String a = args.length > 0 ? String.join(" ", args).trim() : "";
        Ride cur = rides.get(p.getUniqueId());
        if (a.equalsIgnoreCase("exit") || (cur != null && a.isEmpty())) {
            if (cur != null) endRide(cur, "You left the flight.");
            return true;
        }
        if (a.toLowerCase(Locale.ROOT).startsWith("speed")) {
            String[] sp = a.split("\\s+");
            try {
                int v = Math.max(1, Math.min(100, Integer.parseInt(sp[1])));
                if (cur != null) cur.speed = v / 100.0;
                p.sendMessage(say("Cabin speed: " + v + "% of the real speed.", NamedTextColor.GREEN));
            } catch (Exception ex) {
                p.sendMessage(say("/ride speed <1-100>", NamedTextColor.YELLOW));
            }
            return true;
        }
        if (cur != null) {
            p.sendMessage(say("You are riding a flight. /ride exit first.", NamedTextColor.YELLOW));
            return true;
        }
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(say("Real flights are for Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        if (p.getInventory().getChestplate() != null && p.getInventory().getChestplate().getType() == Material.ELYTRA) {
            p.sendMessage(say("Get out of your plane first (/plane).", NamedTextColor.YELLOW));
            return true;
        }
        if (a.isEmpty() || a.equalsIgnoreCase("nearest")) {
            Location l = p.getLocation();
            List<Object[]> v = visible(p, region(Cmds.latOf(l.getZ()), Cmds.lonOf(l.getX())).list, 150, -90);
            if (v.isEmpty()) {
                p.sendMessage(say("No flight is near yet. Try /flights in a few seconds, or /ride <callsign>.", NamedTextColor.YELLOW));
                return true;
            }
            startRide(p, (Ac) v.get(0)[0]);
            return true;
        }
        final String q = a.toUpperCase(Locale.ROOT).replace(" ", "");
        p.sendMessage(say("Looking for flight " + q + " ...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Ac> found = fetch("/callsign/" + q);
                if (found.isEmpty()) found = fetch("/hex/" + q.toLowerCase(Locale.ROOT));
                if (found.isEmpty()) found = fetch("/registration/" + q);
                if (found.isEmpty()) {
                    p.sendMessage(say("No flight called " + q + " is in the air right now.", NamedTextColor.RED));
                    return;
                }
                Ac f = found.get(0);
                Bukkit.getScheduler().runTask(plugin, () -> startRide(p, f));
            } catch (Exception e) {
                p.sendMessage(say("The flight search did not answer (" + e.getMessage() + ").", NamedTextColor.RED));
            }
        });
        return true;
    }

    // ---- riding ----

    /**
     * where the player is, from the middle of the plane, in blocks. In a big plane: in the left window seat over the wing. Small planes are too
     * small to sit in, so there it is beside the left wing.
     */
    private double[] offset(Ac a, boolean cabin) {
        double tr = Math.toRadians(a.track), hx = Math.sin(tr), hz = -Math.cos(tr);
        double lx = -Math.cos(tr), lz = -Math.sin(tr); // to the left of the flight
        if (cabin) {
            double s = typeLen(a) / 8, xl = 0.5 * s - 0.9, zl = 0.0, yl = -0.5 * s + 0.15; // window seat, over the wing, feet on the floor
            return new double[]{lx * xl + hx * zl, yl, lz * xl + hz * zl};
        }
        double len = Math.max(30, Math.min(70, typeLen(a) * 0.9 + 18));
        return new double[]{lx * len, 6, lz * len};
    }

    private static final Map<String, Double> LENGTH = new HashMap<>();
    static {
        String[] t = {"A320", "A20N", "A321", "A21N", "A319", "A19N", "A318", "B738", "B737", "B38M", "B739", "B39M", "B752", "B753", "B744", "B748", "B77W", "B772", "B773", "B788", "B789", "A388", "A359", "A35K", "A333", "A332", "A346",
                "E190", "E170", "E75L", "CRJ9", "CRJ7", "AT72", "AT76", "DH8D", "C172", "C208", "PC12", "BCS1", "BCS3", "A400", "C130", "C17", "GLF5", "C56X"};
        double[] l = {37.6, 37.6, 44.5, 44.5, 33.8, 33.8, 31.4, 39.5, 33.6, 39.5, 42.1, 42.2, 47.3, 54.4, 70.7, 76.3, 73.9, 63.7, 73.9, 56.7, 62.8, 72.7, 66.8, 73.8, 63.7, 58.8, 75.4,
                36.2, 29.9, 31.7, 36.2, 32.5, 27.2, 27.2, 32.8, 8.3, 11.5, 14.4, 35.0, 38.7, 45.1, 29.8, 53.0, 29.4, 19.0};
        for (int i = 0; i < t.length; i++) LENGTH.put(t[i], l[i]);
    }

    private static double typeLen(Ac a) {
        return LENGTH.getOrDefault(a.type.toUpperCase(Locale.ROOT), 37.0);
    }

    private void startRide(Player p, Ac a) {
        if (a.ground || a.gs < 20) {
            p.sendMessage(say(a.call + " is on the ground. Pick one in the air (/flights).", NamedTextColor.YELLOW));
            return;
        }
        Ride r = new Ride();
        r.p = p;
        r.ac = a;
        r.savedChest = p.getInventory().getChestplate();
        r.savedView = p.getViewDistance();
        r.allowFlight = p.getAllowFlight();
        p.setViewDistance(8); // far enough to see the ground below, near enough that the world can be made at this speed
        double len = typeLen(a);
        r.cabin = len >= 25;
        double[] w = pos(a, true);
        int g0 = gen.surfaceY((int) Math.floor(w[0]), (int) Math.floor(w[2]), false); // start well above the ground and the roofs
        w[1] = (g0 != Integer.MIN_VALUE ? g0 : TileGen.SEA + 10) + Math.max(40, Math.min(140, a.altM / 100));
        r.y = w[1];
        plugin.getLogger().info("ride: " + p.getName() + " on " + a.call + " (" + a.type + ") at block " + Math.round(w[0]) + "," + Math.round(w[2]) + ", plane y=" + Math.round(w[1]) + ", ground " + g0 + ", cabin=" + r.cabin);
        p.setFallDistance(0);
        if (r.cabin) {
            // you walk in the cabin: no wings, the plane moves slowly and carries you, an invisible floor is under your feet
            double floorLocal = -0.5 * (len / 8) + 0.12;
            r.cx = w[0];
            r.cz = w[2];
            r.cy = Math.round(w[1] - floorLocal) + floorLocal; // the floor is at a whole block height
            r.hdg = a.track;
            buildCabin(r, len, engines(a));
            rides.put(p.getUniqueId(), r);
            double[] o = offset(a, true);
            p.setAllowFlight(true); // the server would kick somebody who stands on an invisible floor
            p.setGliding(false);
            p.sendMessage(say("Loading the world under the plane…", NamedTextColor.GRAY));
            // the place of the plane has no chunks yet: wait until the ground there exists, then go
            Location dest = new Location(p.getWorld(), r.cx + o[0], r.cy + o[1], r.cz + o[2], (float) (a.track + 180), 0);
            p.teleportAsync(dest);
            Bukkit.getScheduler().runTaskLater(plugin, () -> r.ready = true, 60L); // the floor holds you while the ground is made
        } else {
            r.savedChest = p.getInventory().getChestplate();
            ItemStack wings = new ItemStack(Material.ELYTRA);
            ItemMeta m = wings.getItemMeta();
            m.setUnbreakable(true);
            m.displayName(Component.text("Pilot wings"));
            m.getPersistentDataContainer().set(wingsKey, PersistentDataType.BYTE, (byte) 1);
            wings.setItemMeta(m);
            p.getInventory().setChestplate(wings);
            buildModel(r, len, engines(a));
            rides.put(p.getUniqueId(), r);
            double[] o = offset(a, false);
            p.teleport(new Location(p.getWorld(), w[0] + o[0], w[1] + o[1], w[2] + o[2], (float) (a.track + 180), 0));
            p.setGliding(true);
        }
        dropSky(p.getUniqueId());
        p.sendMessage(say("Riding " + (a.call.isEmpty() ? a.hex : a.call) + " (" + (a.desc.isEmpty() ? a.type : a.desc) + "), " + Math.round(a.gs * 3.6) + " km/h. " + (r.cabin ? "You are in the cabin, at a window over the left wing. Walk around, jump! The plane flies at " + Math.round(r.speed * 100) + "% of its real speed (/ride speed 1-100). To leave: /ride exit, or hold Shift for 3 seconds." : "Look around, the plane is beside you. To leave: hold Shift for a second, or /ride exit."), NamedTextColor.GREEN));
    }

    /** a plane you can sit in: a hollow cabin with a floor, a roof, walls with a long window band on each side, and two seats */
    private void buildCabin(Ride r, double len, int engines) {
        double s = len / 8, W = s, H = s, L = 6.0 * s, t = 0.12;
        Material white = Material.WHITE_CONCRETE, grey = Material.LIGHT_GRAY_CONCRETE;
        part(r, Material.GRAY_CONCRETE, 0, -H / 2 + t / 2, 0, W, t, L);                // the floor
        part(r, white, 0, H / 2 - t / 2, 0, W, t, L);                                  // the roof
        double yb0 = -0.34 * H, yb1 = -0.12 * H;                                         // the window band (at the height of a seated person's eyes)
        for (int side = -1; side <= 1; side += 2) {
            double x = side * (W / 2 - t / 2);
            part(r, white, x, (-H / 2 + yb0) / 2, 0, t, yb0 + H / 2, L);                // under the windows
            part(r, white, x, (yb1 + H / 2) / 2, 0, t, H / 2 - yb1, L);                 // over the windows
            for (double z = -L / 2 + 1.5; z < L / 2; z += 3.0)
                part(r, white, x, (yb0 + yb1) / 2, z, t * 1.2, yb1 - yb0, 0.35);        // a bar between two windows
        }
        part(r, grey, 0, 0, 3.0 * s, W, H, t);                                          // the wall at the front of the cabin
        part(r, grey, 0, 0, -3.0 * s, W, H, t);                                         // and at the back
        for (int n = -1; n <= 1; n += 2) {                                              // two rows of seats at the window seat
            double sz = n * 1.5;
            part(r, Material.BLUE_WOOL, W / 2 - 0.9, -H / 2 + t + 0.3, sz, 0.55, 0.5, 0.55);
            part(r, Material.BLUE_WOOL, W / 2 - 0.9, -H / 2 + t + 0.85, sz - 0.3, 0.55, 0.9, 0.12);
        }
        // the nose, the wings (low, as on a real airliner), the tail and the engines
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, 0, 3.4 * s, 0.8 * s, 0.8 * s, 1.0 * s);
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, -0.38 * s, 0.3 * s, 10.5 * s, 0.16 * s, 1.7 * s);
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, 0.1 * s, -2.8 * s, 4.0 * s, 0.14 * s, 1.0 * s);
        part(r, Material.RED_CONCRETE, 0, 0.9 * s, -2.7 * s, 0.14 * s, 1.8 * s, 1.2 * s);
        part(r, Material.WHITE_CONCRETE, 0, 0, -3.7 * s, 0.6 * s, 0.6 * s, 1.4 * s);
        if (engines >= 2) {
            part(r, Material.GRAY_CONCRETE, 2.6 * s, -0.75 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            part(r, Material.GRAY_CONCRETE, -2.6 * s, -0.75 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
        if (engines >= 4) {
            part(r, Material.GRAY_CONCRETE, 4.6 * s, -0.7 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            part(r, Material.GRAY_CONCRETE, -4.6 * s, -0.7 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
    }

    private static int engines(Ac a) {
        String t = a.type.toUpperCase(Locale.ROOT);
        if (t.startsWith("B74") || t.startsWith("A38") || t.startsWith("A34")) return 4;
        if (t.startsWith("C1") || t.startsWith("PA") || t.startsWith("SR2") || t.startsWith("DA")) return 0;
        return 2;
    }

    private void buildModel(Ride r, double len, int engines) {
        double s = len / 8;
        part(r, Material.WHITE_CONCRETE, 0, 0, 0, 1.0 * s, 1.0 * s, 6.0 * s);
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, 0, 3.4 * s, 0.8 * s, 0.8 * s, 1.0 * s);
        part(r, Material.LIGHT_BLUE_STAINED_GLASS, 0, 0.55 * s, 1.9 * s, 0.8 * s, 0.4 * s, 1.0 * s);
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, 0.15 * s, 0.3 * s, 10.5 * s, 0.16 * s, 1.7 * s);
        part(r, Material.LIGHT_GRAY_CONCRETE, 0, 0.25 * s, -2.8 * s, 4.0 * s, 0.14 * s, 1.0 * s);
        part(r, Material.RED_CONCRETE, 0, 1.0 * s, -2.7 * s, 0.14 * s, 1.8 * s, 1.2 * s);
        part(r, Material.WHITE_CONCRETE, 0, 0.1 * s, -3.7 * s, 0.6 * s, 0.6 * s, 1.4 * s);
        if (engines >= 2) {
            part(r, Material.GRAY_CONCRETE, 2.6 * s, -0.55 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            part(r, Material.GRAY_CONCRETE, -2.6 * s, -0.55 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
        if (engines >= 4) {
            part(r, Material.GRAY_CONCRETE, 4.6 * s, -0.5 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            part(r, Material.GRAY_CONCRETE, -4.6 * s, -0.5 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
    }

    private void part(Ride r, Material m, double x, double y, double z, double sx, double sy, double sz) {
        BlockDisplay d = r.p.getWorld().spawn(r.p.getLocation(), BlockDisplay.class, e -> init(e, m, r.cabin ? 0 : 1)); // in the cabin the walls move at once, together with you
        r.p.showEntity(plugin, d); // the pilot sees it too: it is the plane next to him
        d.setVisibleByDefault(true);
        r.parts.add(d);
        r.layout.add(new float[]{(float) x, (float) y, (float) z, (float) sx, (float) sy, (float) sz});
    }

    private void rideTick() {
        for (Ride r : new ArrayList<>(rides.values())) {
            Player p = r.p;
            if (!p.isOnline() || p.isDead() || p.getWorld().getGenerator() != gen) {
                endRide(r, null);
                continue;
            }
            r.ticks++;
            if (r.cabin) {
                if (r.ready) walkTick(r);
                else if (r.ticks > 1200) endRide(r, "The world under the plane could not be loaded. Try again.");
                continue;
            }
            Ac a = r.ac;
            double[] w = pos(a, true), o = offset(a, r.cabin);
            Location l = p.getLocation();
            double tr = Math.toRadians(a.track);
            // the plane flies 40 to 140 blocks above the ground (the real height divided by 100), and changes height slowly: so that the ground below can always be seen
            int gp = gen.surfaceY((int) Math.floor(w[0]), (int) Math.floor(w[2]), false);
            double base = gp != Integer.MIN_VALUE ? gp : TileGen.SEA + 8;
            double target = base + Math.max(40, Math.min(140, a.altM / 100));
            double prevY = r.y == 0 ? target : r.y;
            r.y = prevY + (target - prevY) * 0.03;
            w[1] = r.y;
            Vector v = new Vector(Math.sin(tr) * a.gs / 20, r.y - prevY, -Math.cos(tr) * a.gs / 20);
            Vector want = new Vector(w[0] + o[0], w[1] + o[1], w[2] + o[2]);
            int gw = gen.surfaceY((int) Math.floor(want.getX()), (int) Math.floor(want.getZ()), false);
            if (!r.cabin && gw != Integer.MIN_VALUE && want.getY() < gw + 14) want.setY(gw + 14);
            // hold Shift for a second to leave, wherever you are
            if (p.getCurrentInput().isSneak()) {
                if (++r.sneak > 20) {
                    endRide(r, "You left the flight.");
                    continue;
                }
            } else r.sneak = 0;
            // inside a block (a roof, a hill)? back to the seat, a little higher
            if (l.getBlock().getType().isSolid() || p.getEyeLocation().getBlock().getType().isSolid()) {
                p.teleport(want.toLocation(l.getWorld(), l.getYaw(), l.getPitch()).add(0, 6, 0));
                p.setVelocity(new Vector());
                continue;
            }
            Vector err = want.clone().subtract(l.toVector());
            if (err.length() > 250) { // lost behind: back beside the plane
                p.teleport(new Location(l.getWorld(), want.getX(), want.getY(), want.getZ(), l.getYaw(), l.getPitch()));
                continue;
            }
            Vector cmd = v.add(err.multiply(r.cabin ? 0.35 : 0.12)); // in the cabin the seat must stay in the same place
            if (cmd.length() > 4) cmd.normalize().multiply(4);
            if (!p.isGliding()) p.setGliding(true);
            p.setVelocity(cmd);
            p.setFallDistance(0);
            // the model of the real plane, at its real size, where the real plane is
            Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(a.track + 180)).rotateX((float) Math.toRadians(a.gs > 5 ? -Math.toDegrees(Math.atan2(a.vr, a.gs)) : 0));
            Location at = new Location(l.getWorld(), w[0], w[1], w[2]);
            for (int n = 0; n < r.parts.size(); n++) {
                float[] f = r.layout.get(n);
                BlockDisplay d = r.parts.get(n);
                Vector3f off = new Vector3f(f[0] - f[3] / 2, f[1] - f[4] / 2, f[2] - f[5] / 2).rotate(q);
                d.teleport(at);
                d.setTransformation(new Transformation(off, q, new Vector3f(f[3], f[4], f[5]), new Quaternionf()));
            }
            if (r.ticks % 5 == 0) {
                p.sendActionBar(Component.text(String.format(Locale.ROOT, "RIDING %s  %s  |  %d km/h  |  %s km up  |  heading %03d  |  /ride exit", a.call.isEmpty() ? a.hex : a.call, a.type,
                        Math.round(a.gs * 3.6), String.format(Locale.ROOT, "%.1f", a.altM / 1000), Math.round(a.track)), NamedTextColor.WHITE));
            }
            if (r.ticks % 10 == 0) ahead(l, v);
            if (r.ticks % 40 == 0 && !r.polling) poll(r);
            if (r.lostSince > 0 && System.currentTimeMillis() - r.lostSince > 20000) endRide(r, "The flight is no longer on the radar (landed or out of range).");
        }
    }

    private static final org.bukkit.block.data.BlockData BARRIER = Material.BARRIER.createBlockData();

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** the invisible floor (only this player sees it, and only he feels it): the blocks under his feet follow the plane */
    private void setFloor(Ride r, World w, double px, double pz, int fy) {
        Player p = r.p;
        Map<Long, int[]> now = new HashMap<>();
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = bx + dx, z = bz + dz;
                long k = key(x, fy, z);
                now.put(k, new int[]{x, fy, z});
                if (!r.fake.containsKey(k)) p.sendBlockChange(new Location(w, x, fy, z), BARRIER);
            }
        }
        for (Map.Entry<Long, int[]> e : r.fake.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            Location l = new Location(w, e.getValue()[0], e.getValue()[1], e.getValue()[2]);
            p.sendBlockChange(l, l.getBlock().getBlockData()); // back to what is really there
        }
        r.fake.clear();
        r.fake.putAll(now);
    }

    private void clearFloor(Ride r) {
        if (!r.p.isOnline()) return;
        World w = r.p.getWorld();
        for (int[] c : r.fake.values()) {
            Location l = new Location(w, c[0], c[1], c[2]);
            r.p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        r.fake.clear();
    }

    /** a ride in a big plane: the plane flies slowly and carries the player, who walks and jumps in the cabin */
    private void walkTick(Ride r) {
        Player p = r.p;
        Ac a = r.ac;
        World w = p.getWorld();
        double s = typeLen(a) / 8, W = s, L = 6.0 * s, floorLocal = -0.5 * s + 0.12;
        // the plane turns slowly to the heading of the real one, and flies at a part of its speed
        double dh = ((a.track - r.hdg + 540) % 360) - 180, nh = r.hdg + Math.max(-0.4, Math.min(0.4, dh * 0.02));
        double sp = a.gs * r.speed / 20, th = Math.toRadians(nh);
        double nx = r.cx + Math.sin(th) * sp, nz = r.cz - Math.cos(th) * sp;
        // 40 to 140 blocks above the ground (the real height divided by 100), one block at a time, so that the floor is always at a whole height
        int gp = gen.surfaceY((int) Math.floor(nx), (int) Math.floor(nz), false);
        double base = gp != Integer.MIN_VALUE ? gp : TileGen.SEA + 8;
        double target = base + Math.max(40, Math.min(140, a.altM / 100));
        double goal = Math.round(target - floorLocal) + floorLocal, ny = r.cy;
        if (r.ticks % 6 == 0) {
            if (goal > r.cy + 0.5) ny = r.cy + 1;
            else if (goal < r.cy - 0.5) ny = r.cy - 1;
        }
        // where the player is in the cabin (from where the game has put him), kept inside the cabin
        Location pl = p.getLocation();
        double ph = Math.toRadians(r.hdg), ox = pl.getX() - r.cx, oz = pl.getZ() - r.cz, oy = pl.getY() - r.cy;
        double xl = ox * -Math.cos(ph) + oz * -Math.sin(ph), zl = ox * Math.sin(ph) + oz * -Math.cos(ph);
        xl = Math.max(-(W / 2 - 0.45), Math.min(W / 2 - 0.45, xl));
        zl = Math.max(-(L / 2 - 0.6), Math.min(L / 2 - 0.6, zl));
        double yMin = floorLocal - 0.02, yMax = Math.max(yMin, 0.5 * s - 0.12 - 1.85);
        oy = Math.max(yMin, Math.min(yMax, oy));
        double px = nx + -Math.cos(th) * xl + Math.sin(th) * zl, pz = nz + -Math.sin(th) * xl + -Math.cos(th) * zl, py = ny + oy;
        // the same move for the player and the cabin; the player keeps his own movement (walking, jumping), only the plane's movement is added
        Location delta = new Location(w, px - pl.getX(), py - pl.getY(), pz - pl.getZ(), 0, 0);
        if (Math.abs(delta.getX()) + Math.abs(delta.getY()) + Math.abs(delta.getZ()) > 0.0005) {
            p.teleport(delta, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN, io.papermc.paper.entity.TeleportFlag.Relative.X, io.papermc.paper.entity.TeleportFlag.Relative.Y,
                    io.papermc.paper.entity.TeleportFlag.Relative.Z, io.papermc.paper.entity.TeleportFlag.Relative.YAW, io.papermc.paper.entity.TeleportFlag.Relative.PITCH,
                    io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_X, io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_Y, io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_Z);
        }
        setFloor(r, w, px, pz, (int) Math.floor(ny + floorLocal) - 1);
        p.setFlying(false);
        p.setFallDistance(0);
        // the cabin
        Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(nh + 180));
        Location at = new Location(w, nx, ny, nz);
        for (int n = 0; n < r.parts.size(); n++) {
            float[] f = r.layout.get(n);
            BlockDisplay d = r.parts.get(n);
            Vector3f off = new Vector3f(f[0] - f[3] / 2, f[1] - f[4] / 2, f[2] - f[5] / 2).rotate(q);
            d.teleport(at);
            d.setTransformation(new Transformation(off, q, new Vector3f(f[3], f[4], f[5]), new Quaternionf()));
        }
        r.cx = nx;
        r.cy = ny;
        r.cz = nz;
        r.hdg = nh;
        if (r.ticks % 5 == 0) {
            p.sendActionBar(Component.text(String.format(Locale.ROOT, "%s  %s  |  %d km/h (real %d)  |  %s km up in real life  |  heading %03d  |  /ride speed 1-100  |  /ride exit", a.call.isEmpty() ? a.hex : a.call, a.type,
                    Math.round(a.gs * r.speed * 3.6), Math.round(a.gs * 3.6), String.format(Locale.ROOT, "%.1f", a.altM / 1000), Math.round(nh)), NamedTextColor.WHITE));
        }
        if (r.ticks % 10 == 0) ahead(new Location(w, nx, ny, nz), new Vector(Math.sin(th) * sp, 0, -Math.cos(th) * sp));
        if (r.ticks % 40 == 0 && !r.polling) poll(r);
        if (r.lostSince > 0 && System.currentTimeMillis() - r.lostSince > 20000) endRide(r, "The flight is no longer on the radar (landed or out of range).");
        if (p.getCurrentInput().isSneak()) {
            if (++r.sneak > 60) endRide(r, "You left the flight.");
        } else r.sneak = 0;
    }

    private void poll(Ride r) {
        r.polling = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Ac> l = fetch("/hex/" + r.ac.hex);
                if (!l.isEmpty()) {
                    Ac n = l.get(0);
                    n.call = r.ac.call.isEmpty() ? n.call : r.ac.call;
                    n.type = r.ac.type.isEmpty() ? n.type : r.ac.type;
                    n.desc = r.ac.desc.isEmpty() ? n.desc : r.ac.desc;
                    r.ac = n;
                    r.lostSince = 0;
                    if (n.ground) r.lostSince = System.currentTimeMillis() - 15000;
                } else if (r.lostSince == 0) {
                    r.lostSince = System.currentTimeMillis();
                }
            } catch (Exception ignored) {
            } finally {
                r.polling = false;
            }
        });
    }

    /** the squares and chunks along the way are made before you get there */
    private void ahead(Location l, Vector v) {
        World w = l.getWorld();
        double speed = v.length() * 20, step = Math.max(150, speed * 2.5);
        Vector dir = new Vector(v.getX(), 0, v.getZ()).normalize();
        for (int k = 1; k <= 5; k++) {
            double x = l.getX() + dir.getX() * step * k, z = l.getZ() + dir.getZ() * step * k;
            store.prefetch(Math.floorDiv((int) Math.floor(x), Tile.TILE), Math.floorDiv((int) Math.floor(z), Tile.TILE));
            if (k <= 3) w.getChunkAtAsync((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }
    }

    private void endRide(Ride r, String message) {
        rides.remove(r.p.getUniqueId());
        for (BlockDisplay d : r.parts) d.remove();
        Player p = r.p;
        if (!p.isOnline()) return;
        clearFloor(r);
        if (r.cabin) {
            p.setFlying(false);
            p.setAllowFlight(r.allowFlight);
        } else p.getInventory().setChestplate(r.savedChest);
        p.setGliding(false);
        p.setViewDistance(r.savedView > 0 ? r.savedView : Bukkit.getViewDistance());
        p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 1200, 0, false, false, false));
        p.sendActionBar(Component.empty());
        // back down to the ground under you
        Location l = p.getLocation();
        int g = gen.surfaceY(l.getBlockX(), l.getBlockZ(), false);
        if (g != Integer.MIN_VALUE && l.getY() > g + 60) p.teleport(new Location(l.getWorld(), l.getX(), g + 40, l.getZ(), l.getYaw(), 0));
        if (message != null) p.sendMessage(say(message, NamedTextColor.GREEN));
    }

    /** A rider takes no damage (falls, walls, suffocation): the floor and the walls are only pretend. */
    @EventHandler
    public void onRiderDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && rides.containsKey(p.getUniqueId())
                && e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID
                && e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.KILL) {
            if (e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.SUFFOCATION) plugin.getLogger().info("ride: suffocation of " + p.getName());
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dropSky(id);
        Ride r = rides.get(id);
        if (r != null) endRide(r, null);
    }
}
