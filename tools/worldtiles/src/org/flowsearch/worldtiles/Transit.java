package org.flowsearch.worldtiles;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The real trains of Berlin (VBB timetable: S-Bahn, U-Bahn, tram, regional trains) run on the real timetable, seen from outside and
 * rideable: /train shows the next departures at the stop near you, /train board puts you into the nearest train, where you can walk around.
 * The timetable file is made by tools/transit (a small Go program).
 */
public final class Transit implements CommandExecutor, Listener {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String[] CAT = {"S-Bahn", "U-Bahn", "tram", "train"};
    private static final double HEIGHT = 2.8, WIDTH = 3.0;
    private static final int RAISE = 1; // the floor is on top of the ground block

    private static final class Trip {
        int route, mask, n, start, end;
        String head;
        int[] stop, arr, dep;
    }

    private static final class Data {
        int base; // yyyymmdd of bit 0
        LocalDate baseDate;
        String[] sname;
        double[] sx, sz;
        String[] rname;
        byte[] rcat;
        int[] rcolor;
        Trip[] trips;
    }

    private static final class Active {
        Trip t;
        int shift;
        double secs; // seconds into this trip's service day, now
    }

    private static final class Train {
        Trip t;
        int shift;
        int key;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<float[]> layout = new ArrayList<>();
        TextDisplay label;
        double x, z, h, y;
        int yi;
        boolean placed;
        double len;
        Material body;
    }

    private static final class Ride {
        Player p;
        Train tr;
        double lx, lz, lh;
        int ly;
        int sneak, ticks;
        boolean allowFlight;
        // the driver's job: open the doors (Space) at each stop
        boolean driver, opened;
        int curStop = -1, served, missed;
        long windowStart, earned;
        final List<BlockDisplay> seats = new ArrayList<>();
        final Map<Long, int[]> fake = new HashMap<>();
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private volatile Data data;
    private volatile String error;
    private List<Active> active = new ArrayList<>();
    private long activeAtMs, midnightMs;
    private double secs0;
    private int dayIdx;
    private final Map<Integer, Train> trains = new HashMap<>();
    private final Map<UUID, Ride> rides = new HashMap<>();
    private int tickN;

    private final Economy eco;

    Transit(JavaPlugin plugin, TileGen gen, Economy eco) {
        this.plugin = plugin;
        this.gen = gen;
        this.eco = eco;
    }

    private long loadedMod;

    private void loadAsync() {
        Thread t = new Thread(() -> {
            File f = new File(System.getProperty("worldtiles.trains", "/media/data/gtfs-berlin/trains.bin"));
            try {
                long mod = f.lastModified();
                Data nd = load(f);
                loadedMod = mod;
                data = nd;
                error = null;
                plugin.getLogger().info("Transit: " + nd.trips.length + " trips, " + nd.sname.length + " stops, timetable from " + nd.baseDate);
            } catch (Exception e) {
                if (data == null) error = e.toString();
                plugin.getLogger().warning("Transit: timetable " + f + " could not be read: " + e);
            }
        }, "transit-load");
        t.setDaemon(true);
        t.start();
    }

    void start() {
        loadAsync();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
        // the timetable file is made again every week (tools/transit/update.sh): read it again when it changes
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            File f = new File(System.getProperty("worldtiles.trains", "/media/data/gtfs-berlin/trains.bin"));
            if (f.lastModified() > loadedMod && data != null) loadAsync();
        }, 72000L, 72000L);
    }

    void removeAll() {
        for (Ride r : new ArrayList<>(rides.values())) endRide(r, null);
        for (Train t : trains.values()) drop(t);
        trains.clear();
    }

    private static String str(DataInputStream in) throws Exception {
        byte[] b = new byte[in.readUnsignedShort()];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Data load(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("TRN1")) throw new IllegalStateException("not a timetable file");
            Data d = new Data();
            d.base = in.readInt();
            d.baseDate = LocalDate.of(d.base / 10000, d.base / 100 % 100, d.base % 100);
            int ns = in.readInt();
            d.sname = new String[ns];
            d.sx = new double[ns];
            d.sz = new double[ns];
            for (int i = 0; i < ns; i++) {
                double lat = in.readDouble(), lon = in.readDouble();
                d.sname[i] = str(in);
                d.sx[i] = Cmds.blockX(lon) + 0.5;
                d.sz[i] = Cmds.blockZ(lat) + 0.5;
            }
            int nr = in.readInt();
            d.rname = new String[nr];
            d.rcat = new byte[nr];
            d.rcolor = new int[nr];
            for (int i = 0; i < nr; i++) {
                d.rname[i] = str(in);
                d.rcat[i] = in.readByte();
                d.rcolor[i] = in.readInt();
            }
            int nt = in.readInt();
            d.trips = new Trip[nt];
            for (int i = 0; i < nt; i++) {
                Trip t = new Trip();
                t.route = in.readUnsignedShort();
                t.mask = in.readInt();
                t.head = str(in);
                t.n = in.readUnsignedShort();
                t.stop = new int[t.n];
                t.arr = new int[t.n];
                t.dep = new int[t.n];
                for (int k = 0; k < t.n; k++) {
                    t.stop[k] = in.readInt();
                    t.arr[k] = in.readInt();
                    t.dep[k] = in.readInt();
                }
                t.start = t.dep[0];
                t.end = t.arr[t.n - 1];
                d.trips[i] = t;
            }
            return d;
        }
    }

    // ---- where is a train now ----

    /** {x, z, heading, dwelling (1) or moving (0), index of the next stop, seconds to it} or null when the trip is not running at s */
    private double[] where(Data d, Trip t, double s) {
        if (s < t.dep[0] || s > t.arr[t.n - 1]) return null;
        for (int i = 0; i < t.n; i++) {
            if (s <= t.dep[i]) { // waiting at stop i (or arriving)
                if (s >= t.arr[i] || i == 0) {
                    int j = i == 0 ? 1 : i;
                    double h = heading(d, t, j - 1, j);
                    return new double[]{d.sx[t.stop[i]], d.sz[t.stop[i]], h, 1, i, t.arr[i] - s};
                }
            }
            if (i + 1 < t.n && s > t.dep[i] && s < t.arr[i + 1]) {
                double f = (s - t.dep[i]) / Math.max(1, t.arr[i + 1] - t.dep[i]);
                f = f * f * (3 - 2 * f); // it speeds up and brakes
                int a = t.stop[i], b = t.stop[i + 1];
                return new double[]{d.sx[a] + (d.sx[b] - d.sx[a]) * f, d.sz[a] + (d.sz[b] - d.sz[a]) * f, heading(d, t, i, i + 1), 0, i + 1, t.arr[i + 1] - s};
            }
        }
        return null;
    }

    private static double heading(Data d, Trip t, int a, int b) {
        double dx = d.sx[t.stop[b]] - d.sx[t.stop[a]], dz = d.sz[t.stop[b]] - d.sz[t.stop[a]];
        if (Math.abs(dx) + Math.abs(dz) < 0.01) return 0;
        return Math.toDegrees(Math.atan2(dx, -dz));
    }

    private void refreshActive(Data d) {
        ZonedDateTime now = ZonedDateTime.now(BERLIN);
        ZonedDateTime mid = now.toLocalDate().atStartOfDay(BERLIN);
        midnightMs = mid.toInstant().toEpochMilli();
        activeAtMs = System.currentTimeMillis();
        secs0 = (activeAtMs - midnightMs) / 1000.0;
        dayIdx = (int) ChronoUnit.DAYS.between(d.baseDate, now.toLocalDate());
        List<Active> list = new ArrayList<>();
        if (dayIdx >= 1 && dayIdx < 32) {
            for (int shift = 0; shift <= 86400; shift += 86400) {
                int di = shift == 0 ? dayIdx : dayIdx - 1;
                double s = secs0 + shift;
                for (Trip t : d.trips) {
                    if ((t.mask & (1 << di)) == 0 || s < t.start || s > t.end) continue;
                    Active a = new Active();
                    a.t = t;
                    a.shift = shift;
                    list.add(a);
                }
            }
        }
        active = list;
    }

    private double nowSecs() {
        return (System.currentTimeMillis() - midnightMs) / 1000.0;
    }

    // ---- the trains in the world ----

    private Material bodyOf(Data d, Trip t) {
        return switch (d.rcat[t.route]) {
            case 0 -> Material.RED_CONCRETE;
            case 1 -> Material.YELLOW_CONCRETE;
            case 2 -> Material.ORANGE_CONCRETE;
            default -> Material.WHITE_CONCRETE;
        };
    }

    private void part(Train tr, Material m, double x, double y, double z, double sx, double sy, double sz) {
        World w = Bukkit.getWorlds().get(0);
        BlockDisplay b = w.spawn(new Location(w, tr.x, tr.yi, tr.z), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setTeleportDuration(0);
            e.setInterpolationDuration(0);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(1.6f);
        });
        tr.parts.add(b);
        tr.layout.add(new float[]{(float) x, (float) y, (float) z, (float) sx, (float) sy, (float) sz});
    }

    private void build(Train tr, Data d, World w) {
        double L = tr.len, W = WIDTH, H = HEIGHT;
        Material body = tr.body;
        part(tr, Material.GRAY_CONCRETE, 0, -0.1, 0, W, 0.2, L);
        part(tr, Material.LIGHT_GRAY_CONCRETE, 0, H + 0.1, 0, W + 0.1, 0.2, L);
        for (int s = -1; s <= 1; s += 2) {
            part(tr, body, s * (W / 2 - 0.05), 0.5, 0, 0.1, 1.0, L);
            part(tr, Material.LIGHT_BLUE_STAINED_GLASS, s * (W / 2 - 0.05), 1.7, 0, 0.06, 1.4, L);
            part(tr, body, s * (W / 2 - 0.05), 2.6, 0, 0.1, 0.4, L);
        }
        for (int e = -1; e <= 1; e += 2) part(tr, Material.LIGHT_GRAY_CONCRETE, 0, H / 2, e * (L / 2 - 0.05), W, H, 0.1);
        tr.label = w.spawn(new Location(w, tr.x, tr.yi + H + 1.6, tr.z), TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            e.setViewRange(2f);
        });
        String line = d.rname[tr.t.route];
        tr.label.text(Component.text(line + " ", NamedTextColor.WHITE).append(Component.text("→ " + tr.t.head, NamedTextColor.GRAY)));
    }

    private void drop(Train tr) {
        for (BlockDisplay b : tr.parts) b.remove();
        if (tr.label != null) tr.label.remove();
        tr.parts.clear();
    }

    private void place(Train tr) {
        Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(tr.h + 180));
        Location at = new Location(tr.parts.get(0).getWorld(), tr.x, tr.yi, tr.z);
        for (int n = 0; n < tr.parts.size(); n++) {
            float[] f = tr.layout.get(n);
            BlockDisplay b = tr.parts.get(n);
            Vector3f off = new Vector3f(f[0] - f[3] / 2, f[1] - f[4] / 2, f[2] - f[5] / 2).rotate(q);
            b.teleport(at);
            b.setTransformation(new Transformation(off, q, new Vector3f(f[3], f[4], f[5]), new Quaternionf()));
        }
        if (tr.label != null) tr.label.teleport(new Location(at.getWorld(), tr.x, tr.yi + HEIGHT + 1.6, tr.z));
    }

    private void tick() {
        Data d = data;
        if (d == null) return;
        tickN++;
        if (tickN % 40 == 1 || active == null) refreshActive(d);
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        List<Player> players = earth.getPlayers();
        // which trains are near a player: made, moved, and removed when far
        Map<Integer, Boolean> near = new HashMap<>();
        if (!players.isEmpty()) {
            for (int i = 0; i < active.size(); i++) {
                Active a = active.get(i);
                double[] w = where(d, a.t, nowSecs() + a.shift);
                if (w == null) continue;
                boolean close = false;
                for (Player p : players) {
                    Location l = p.getLocation();
                    if (Math.abs(l.getX() - w[0]) < 170 && Math.abs(l.getZ() - w[1]) < 170) close = true;
                }
                int key = System.identityHashCode(a.t) ^ a.shift;
                if (!close) continue;
                near.put(key, true);
                Train tr = trains.get(key);
                if (tr == null) {
                    tr = new Train();
                    tr.t = a.t;
                    tr.shift = a.shift;
                    tr.key = key;
                    tr.x = w[0];
                    tr.z = w[1];
                    tr.h = w[2];
                    tr.len = switch (d.rcat[a.t.route]) {
                        case 0 -> 36;
                        case 1 -> 30;
                        case 2 -> 26;
                        default -> 40;
                    };
                    tr.body = bodyOf(d, a.t);
                    int[] c = gen.cell((int) Math.floor(w[0]), (int) Math.floor(w[1]));
                    if (c == null) continue; // the ground is not made here yet
                    tr.y = c[0] + RAISE;
                    tr.yi = (int) Math.round(tr.y);
                    build(tr, d, earth);
                    trains.put(key, tr);
                }
                tr.x = w[0];
                tr.z = w[1];
                if (Math.abs(((w[2] - tr.h + 540) % 360) - 180) > 0.01) tr.h = w[2];
                int[] c = gen.cell((int) Math.floor(w[0]), (int) Math.floor(w[1]));
                if (c != null) tr.y += Math.max(-0.2, Math.min(0.2, c[0] + RAISE - tr.y));
                tr.yi = (int) Math.round(tr.y);
                place(tr);
            }
        }
        for (java.util.Iterator<Map.Entry<Integer, Train>> it = trains.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Train> e = it.next();
            if (near.containsKey(e.getKey())) continue;
            boolean ridden = false;
            for (Ride r : rides.values()) if (r.tr == e.getValue()) ridden = true;
            if (ridden) continue;
            drop(e.getValue());
            it.remove();
        }
        for (Ride r : new ArrayList<>(rides.values())) rideTick(r, d);
    }

    // ---- riding ----

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static final BlockData BARRIER = Material.BARRIER.createBlockData();

    private void floor(Ride r, World w, double px, double pz, int fy) {
        Player p = r.p;
        Map<Long, int[]> now = new HashMap<>();
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                long k = key(bx + dx, fy, bz + dz);
                now.put(k, new int[]{bx + dx, fy, bz + dz});
                if (!r.fake.containsKey(k)) p.sendBlockChange(new Location(w, bx + dx, fy, bz + dz), BARRIER);
            }
        }
        for (Map.Entry<Long, int[]> e : r.fake.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            Location l = new Location(w, e.getValue()[0], e.getValue()[1], e.getValue()[2]);
            p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        r.fake.clear();
        r.fake.putAll(now);
    }

    private void rideTick(Ride r, Data d) {
        Player p = r.p;
        Train tr = r.tr;
        if (!p.isOnline() || p.isDead() || p.getWorld().getGenerator() != gen || !trains.containsValue(tr)) {
            endRide(r, null);
            return;
        }
        r.ticks++;
        double[] w = where(d, tr.t, nowSecs() + tr.shift);
        if (w == null) {
            endRide(r, "Last stop: " + d.sname[tr.t.stop[tr.t.n - 1]] + ". Everybody out!");
            return;
        }
        World world = p.getWorld();
        double L = tr.len;
        Location pl = p.getLocation();
        double ph = Math.toRadians(r.lh), ox = pl.getX() - r.lx, oz = pl.getZ() - r.lz, oy = pl.getY() - r.ly;
        double xl = ox * -Math.cos(ph) + oz * -Math.sin(ph), zl = ox * Math.sin(ph) + oz * -Math.cos(ph);
        xl = Math.max(-(WIDTH / 2 - 0.45), Math.min(WIDTH / 2 - 0.45, xl));
        zl = Math.max(-(L / 2 - 0.6), Math.min(L / 2 - 0.6, zl));
        oy = Math.max(0, Math.min(HEIGHT - 1.9, oy));
        double th = Math.toRadians(tr.h);
        double px = tr.x - Math.cos(th) * xl + Math.sin(th) * zl, pz = tr.z - Math.sin(th) * xl - Math.cos(th) * zl, py = tr.yi + oy;
        double dx = px - pl.getX(), dy = py - pl.getY(), dz = pz - pl.getZ();
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 0.0005) {
            p.teleport(new Location(world, dx, dy, dz, 0, 0), PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.Relative.X, TeleportFlag.Relative.Y, TeleportFlag.Relative.Z,
                    TeleportFlag.Relative.YAW, TeleportFlag.Relative.PITCH, TeleportFlag.Relative.VELOCITY_X, TeleportFlag.Relative.VELOCITY_Y, TeleportFlag.Relative.VELOCITY_Z);
        }
        floor(r, world, px, pz, tr.yi - 1);
        p.setFlying(false);
        p.setFallDistance(0);
        r.lx = tr.x;
        r.lz = tr.z;
        r.lh = tr.h;
        r.ly = tr.yi;
        if (r.driver) driverTick(r, d, w);
        if (r.ticks % 5 == 0) {
            String next = d.sname[tr.t.stop[(int) w[4]]];
            long s = Math.max(0, Math.round(w[5]));
            String what = w[3] == 1 ? "stopped at " + next : "next stop " + next + " in " + (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
            p.sendActionBar(Component.text(d.rname[tr.t.route] + " → " + tr.t.head + "  |  " + what + "  |  /train exit, or hold Shift", NamedTextColor.WHITE));
        }
        if (p.getCurrentInput().isSneak()) {
            if (++r.sneak > 60) endRide(r, "You left the train.");
        } else r.sneak = 0;
    }

    /** at each stop the train waits: the driver has to press Space to open the doors, the sooner the more it pays */
    private void driverTick(Ride r, Data d, double[] w) {
        Player p = r.p;
        int idx = (int) w[4];
        boolean dwell = w[3] == 1;
        if (dwell && r.curStop != idx) {
            r.curStop = idx;
            r.opened = false;
            r.windowStart = System.currentTimeMillis();
            p.sendMessage(Component.text("Stop: " + d.sname[r.tr.t.stop[idx]] + " - press SPACE to open the doors!", NamedTextColor.AQUA));
        }
        if (dwell && !r.opened && r.curStop == idx && p.getCurrentInput().isJump()) {
            r.opened = true;
            long late = System.currentTimeMillis() - r.windowStart;
            int pay = late < 4000 ? 150 : late < 9000 ? 100 : 60;
            r.served++;
            r.earned += pay;
            eco.add(p.getUniqueId(), pay);
            p.sendMessage(Component.text("Doors open: +" + pay + " coins" + (pay == 150 ? " (perfect)" : "") + ".", NamedTextColor.GOLD));
        }
        if (!dwell && r.curStop >= 0) {
            if (!r.opened) {
                r.missed++;
                p.sendMessage(Component.text("You missed the doors at " + d.sname[r.tr.t.stop[r.curStop]] + "! The train leaves without letting anybody out.", NamedTextColor.RED));
            }
            r.curStop = -2;
        }
    }

    private void endRide(Ride r, String msg) {
        rides.remove(r.p.getUniqueId());
        if (r.driver && r.p.isOnline() && (r.served > 0 || r.missed > 0)) {
            long bonus = r.served * 25L + (r.missed == 0 && r.served > 0 ? 500 : 0);
            eco.add(r.p.getUniqueId(), bonus);
            r.p.sendMessage(Component.text(String.format(Locale.ROOT, "Driver report: %d stops done, %d missed, %d coins for the stops%s. Bonus: %d coins. You have %d coins.", r.served, r.missed, r.earned,
                    r.missed == 0 && r.served > 0 ? " (clean run!)" : "", bonus, eco.get(r.p.getUniqueId())), NamedTextColor.GOLD));
        }
        for (BlockDisplay b : r.seats) b.remove();
        Player p = r.p;
        if (!p.isOnline()) return;
        World w = p.getWorld();
        for (int[] c : r.fake.values()) {
            Location l = new Location(w, c[0], c[1], c[2]);
            p.sendBlockChange(l, l.getBlock().getBlockData());
        }
        r.fake.clear();
        p.setFlying(false);
        p.setAllowFlight(r.allowFlight);
        // down to the street
        Location l = p.getLocation();
        int g = gen.surfaceY(l.getBlockX(), l.getBlockZ(), false);
        if (g != Integer.MIN_VALUE) {
            p.setFallDistance(0);
            p.teleport(new Location(w, l.getX(), g + 1, l.getZ(), l.getYaw(), l.getPitch()));
        }
        if (msg != null) p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    // ---- /train ----

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        Data d = data;
        if (d == null) {
            p.sendMessage(Component.text(error != null ? "The timetable is not available: " + error : "The timetable is still loading, try again in a moment.", NamedTextColor.YELLOW));
            return true;
        }
        Ride cur = rides.get(p.getUniqueId());
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (a.equals("exit") || (cur != null && a.isEmpty())) {
            if (cur != null) endRide(cur, "You left the train.");
            return true;
        }
        if (cur != null) {
            p.sendMessage(Component.text("You are in a train. /train exit first.", NamedTextColor.YELLOW));
            return true;
        }
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("The trains are for Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        if (dayIdx < 1 || dayIdx >= 32) {
            p.sendMessage(Component.text("The timetable file is out of date (made on " + d.baseDate + "). It has to be made again (tools/transit).", NamedTextColor.RED));
            return true;
        }
        Location l = p.getLocation();
        if (a.equals("board") || a.equals("drive")) {
            if (a.equals("drive") && !eco.hasLicence(p.getUniqueId(), "train")) {
                p.sendMessage(Component.text("You need the train licence: /licence buy train (" + Economy.LICENCE_PRICE.get("train") + " coins).", NamedTextColor.YELLOW));
                return true;
            }
            String line = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
            Train best = null;
            double bd = 60;
            for (Train t : trains.values()) {
                if (line != null && !d.rname[t.t.route].toLowerCase(Locale.ROOT).equals(line)) continue;
                double dist = Math.hypot(t.x - l.getX(), t.z - l.getZ());
                if (dist < bd) {
                    bd = dist;
                    best = t;
                }
            }
            if (best == null) {
                p.sendMessage(Component.text("No train within 60 blocks" + (line != null ? " on line " + args[1] : "") + ". Go to a stop (/near stops) and wait for one, then /train board.", NamedTextColor.YELLOW));
                return true;
            }
            Ride r = new Ride();
            r.p = p;
            r.tr = best;
            r.allowFlight = p.getAllowFlight();
            r.driver = a.equals("drive");
            r.lx = best.x;
            r.lz = best.z;
            r.lh = best.h;
            r.ly = best.yi;
            rides.put(p.getUniqueId(), r);
            p.setAllowFlight(true);
            p.setGliding(false);
            p.teleport(new Location(p.getWorld(), best.x, best.yi, best.z, l.getYaw(), 0));
            p.sendMessage(Component.text(r.driver ? "You are the driver of " + d.rname[best.t.route] + " → " + best.t.head + "! At every stop press SPACE within a few seconds to open the doors: 150 coins if it is quick, and a 500 bonus for a clean run to the last stop. /train exit gives up."
                    : "You are in " + d.rname[best.t.route] + " → " + best.t.head + ". Walk around, jump. Leave with /train exit or hold Shift 3 s.", NamedTextColor.GREEN));
            return true;
        }
        // the next departures at the nearest stop
        int bs = -1;
        double bd = 400;
        for (int i = 0; i < d.sname.length; i++) {
            double dist = Math.hypot(d.sx[i] - l.getX(), d.sz[i] - l.getZ());
            if (dist < bd) {
                bd = dist;
                bs = i;
            }
        }
        if (bs < 0) {
            p.sendMessage(Component.text("No train stop within 400 blocks. Berlin and Brandenburg have them (/go Alexanderplatz).", NamedTextColor.YELLOW));
            return true;
        }
        String name = d.sname[bs];
        double now = nowSecs();
        List<Object[]> dep = new ArrayList<>();
        for (Active ac : active) {
            Trip t = ac.t;
            double sNow = now + ac.shift;
            for (int k = 0; k < t.n - 1; k++) {
                if (d.sname[t.stop[k]].equals(name) && t.dep[k] >= sNow && t.dep[k] < sNow + 1800) dep.add(new Object[]{t.dep[k] - sNow, d.rname[t.route], t.head, d.rcat[t.route]});
            }
        }
        // trips that have not started yet today
        for (Trip t : d.trips) {
            if ((t.mask & (1 << dayIdx)) == 0 || t.start < now + 0) continue;
            if (t.start > now + 1800) continue;
            for (int k = 0; k < t.n - 1; k++) {
                if (d.sname[t.stop[k]].equals(name) && t.dep[k] >= now && t.dep[k] < now + 1800) dep.add(new Object[]{t.dep[k] - now, d.rname[t.route], t.head, d.rcat[t.route]});
            }
        }
        dep.sort(Comparator.comparingDouble(o -> (double) o[0]));
        p.sendMessage(Component.text(name + " (" + Math.round(bd) + " m away), next departures:", NamedTextColor.GREEN));
        if (dep.isEmpty()) p.sendMessage(Component.text("  none in the next 30 minutes", NamedTextColor.GRAY));
        for (int i = 0; i < Math.min(8, dep.size()); i++) {
            Object[] o = dep.get(i);
            long sec = Math.round((double) o[0]);
            p.sendMessage(Component.text(String.format(Locale.ROOT, "  %2d:%02d  %-5s → %s  (%s)", sec / 60, sec % 60, o[1], o[2], CAT[(byte) o[3]]), NamedTextColor.WHITE));
        }
        p.sendMessage(Component.text("When a train is next to you: /train board [line]. The time shown is minutes:seconds from now.", NamedTextColor.GRAY));
        return true;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && rides.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Ride r = rides.get(e.getPlayer().getUniqueId());
        if (r != null) endRide(r, null);
    }
}
