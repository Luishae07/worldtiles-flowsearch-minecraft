package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Shops are inside their buildings, like in real life: when you come near a shop, its house gets a counter, shelves, aisles, tables and chairs
 * (by the kind of shop), lights in the ceiling (only while it is open), a worker behind the counter, and customers who walk around inside.
 */
public final class ShopInterior {
    static volatile ShopInterior instance;
    private static final String TAG = "wt_interior", SHOPPER = "wt_shopper";

    static final class Interior {
        Places.Poi q;
        boolean failed;
        long failedAt;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<Location> free = new ArrayList<>();
        final List<long[]> lights = new ArrayList<>(); // x, y, z
        Location counter, kitchen;
        Villager staff;
        int gy;

        Location browse(Random r) {
            return free.isEmpty() ? counter : free.get(r.nextInt(free.size())).clone();
        }

        /** a free place between a and b blocks from the counter */
        Location nearCounter(Random r, double a, double b) {
            List<Location> ok = new ArrayList<>();
            for (Location l : free) {
                double d = Math.hypot(l.getX() - counter.getX(), l.getZ() - counter.getZ());
                if (d >= a && d <= b) ok.add(l);
            }
            return (ok.isEmpty() ? browse(r) : ok.get(r.nextInt(ok.size()))).clone();
        }
    }

    private static final class Shopper {
        Villager v;
        Interior in;
        long leaveAt, hopAt;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final Places places;
    private final Random rnd = new Random();
    private final Map<String, Interior> interiors = new HashMap<>();
    private final Map<UUID, Set<Long>> lit = new HashMap<>();
    private final List<Shopper> shoppers = new ArrayList<>();
    private static final BlockData LIGHT = Material.LIGHT.createBlockData("[level=14]");

    ShopInterior(JavaPlugin plugin, TileGen gen, Places places) {
        this.plugin = plugin;
        this.gen = gen;
        this.places = places;
        instance = this;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 20L);
    }

    void removeAll() {
        for (Interior in : interiors.values()) clear(in);
        interiors.clear();
        for (Shopper s : shoppers) s.v.remove();
        shoppers.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(SHOPPER)) e.remove();
    }

    private static String key(Places.Poi q) {
        return Shops.keyOf(q);
    }

    /** the inside of this shop, if it is built now */
    Interior get(Places.Poi q) {
        Interior in = interiors.get(key(q));
        return in == null || in.failed ? null : in;
    }

    static boolean has(Places.Poi q) {
        ShopInterior si = instance;
        return si != null && si.get(q) != null;
    }

    // ---- building the inside ----

    private boolean inside(int x, int z) {
        int[] c = gen.cell(x, z);
        return c != null && c[1] > 0 && (gen.flags(x, z) & 5) == 0;
    }

    private void box(Interior in, World w, double x, double y, double z, Material m, double ox, double oy, double oz, double sx, double sy, double sz) {
        BlockDisplay b = w.spawn(new Location(w, x, y, z), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBrightness(new Display.Brightness(12, 12));
            e.setViewRange(0.5f);
            e.setTransformation(new Transformation(new Vector3f((float) ox, (float) oy, (float) oz), new Quaternionf(), new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
        in.parts.add(b);
    }

    private Interior build(Places.Poi q, World w) {
        Interior in = new Interior();
        in.q = q;
        // the block of the house nearest to the shop
        int sx = Integer.MIN_VALUE, sz = 0;
        double bd = 1e18;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (!inside(q.x + dx, q.z + dz)) continue;
                double d = Math.hypot(dx, dz);
                if (d < bd) {
                    bd = d;
                    sx = q.x + dx;
                    sz = q.z + dz;
                }
            }
        }
        if (sx == Integer.MIN_VALUE) return null;
        int[] sc = gen.cell(sx, sz);
        in.gy = sc[0];
        // the floor of the ground level around it
        Set<Long> region = new HashSet<>();
        ArrayDeque<int[]> bfs = new ArrayDeque<>();
        bfs.add(new int[]{sx, sz});
        region.add(pack(sx, sz));
        while (!bfs.isEmpty() && region.size() < 500) {
            int[] c = bfs.poll();
            int[][] dd = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dd) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) > 8 || !inside(nx, nz) || !region.add(pack(nx, nz))) continue;
                bfs.add(new int[]{nx, nz});
            }
        }
        if (region.size() < 6) return null;
        Set<Long> wallAdj = new HashSet<>(), used = new HashSet<>();
        for (long k : region) {
            int x = (int) (k >> 32), z = (int) k;
            int[][] dd = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dd) if ((gen.flags(x + d[0], z + d[1]) & 1) != 0) wallAdj.add(k);
        }
        double y = in.gy + 1;
        // the counter, and the place behind it (kitchen / till)
        used.add(pack(sx, sz));
        in.counter = new Location(w, sx + 0.5, y, sz + 0.5);
        box(in, w, sx + 0.5, y, sz + 0.5, Material.SPRUCE_PLANKS, -0.9, 0, -0.4, 1.8, 1.0, 0.8);
        box(in, w, sx + 0.5, y, sz + 0.5, Material.SMOOTH_STONE_SLAB, -1.0, 1.0, -0.5, 2.0, 0.1, 1.0);
        int bx = sx, bz = sz;
        int[][] dd = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int[] d : dd) {
            if (region.contains(pack(sx + d[0], sz + d[1])) && !wallAdj.contains(pack(sx + d[0], sz + d[1]))) {
                bx = sx + d[0];
                bz = sz + d[1];
                break;
            }
        }
        if (bx == sx && bz == sz) for (int[] d : dd) if (region.contains(pack(sx + d[0], sz + d[1]))) {
            bx = sx + d[0];
            bz = sz + d[1];
            break;
        }
        used.add(pack(bx, bz));
        in.kitchen = new Location(w, bx + 0.5, y, bz + 0.5);
        String kind = q.kind == null ? "" : q.kind;
        boolean food = kind.equals("cafe") || kind.equals("restaurant") || kind.equals("fast_food") || kind.equals("bar") || kind.equals("pub") || kind.equals("ice_cream") || kind.equals("bakery");
        List<Long> cells = new ArrayList<>(region);
        for (long k : cells) {
            int x = (int) (k >> 32), z = (int) k;
            if (used.contains(k)) continue;
            double dist = Math.hypot(x - sx, z - sz);
            double cx = x + 0.5, cz = z + 0.5;
            boolean adj = wallAdj.contains(k);
            if (food && !adj && dist >= 2 && Math.floorMod(x, 3) == 0 && Math.floorMod(z, 3) == 0) { // a table with two chairs
                box(in, w, cx, y, cz, Material.SPRUCE_FENCE, -0.1, 0, -0.1, 0.2, 0.75, 0.2);
                box(in, w, cx, y, cz, Material.OAK_SLAB, -0.5, 0.75, -0.5, 1.0, 0.1, 1.0);
                box(in, w, cx, y, cz, Material.RED_WOOL, -0.9, 0, -0.2, 0.4, 0.45, 0.4);
                box(in, w, cx, y, cz, Material.RED_WOOL, 0.5, 0, -0.2, 0.4, 0.45, 0.4);
                used.add(k);
            } else if (!food && kind.equals("clothes") && !adj && dist >= 2 && Math.floorMod(x, 3) == 0 && Math.floorMod(z, 2) == 0) { // clothes racks
                box(in, w, cx, y, cz, Material.SPRUCE_FENCE, -0.05, 0, -0.05, 0.1, 1.6, 0.1);
                box(in, w, cx, y, cz, Material.BLUE_WOOL, -0.45, 0.9, -0.1, 0.9, 0.6, 0.2);
                box(in, w, cx, y, cz, Material.YELLOW_WOOL, -0.45, 0.9, 0.0, 0.9, 0.6, 0.15);
                used.add(k);
            } else if (!food && !kind.equals("clothes") && !adj && dist >= 2 && Math.floorMod(x, 3) == 0 && !kind.equals("bank")) { // aisles of shelves with goods
                box(in, w, cx, y, cz, Material.OAK_PLANKS, -0.45, 0, -0.45, 0.9, 1.7, 0.9);
                box(in, w, cx, y, cz, Material.RED_CONCRETE, -0.5, 0.15, -0.3, 1.0, 0.3, 0.6);
                box(in, w, cx, y, cz, Material.YELLOW_CONCRETE, -0.5, 0.65, -0.3, 1.0, 0.3, 0.6);
                box(in, w, cx, y, cz, Material.LIME_CONCRETE, -0.5, 1.15, -0.3, 1.0, 0.3, 0.6);
                used.add(k);
            } else if (adj && !used.contains(k) && dist >= 2 && Math.floorMod(x + z, 2) == 0) { // along the walls
                box(in, w, cx, y, cz, kind.equals("pharmacy") ? Material.WHITE_CONCRETE : Material.BOOKSHELF, -0.45, 0, -0.45, 0.9, 2.0, 0.9);
                if (kind.equals("pharmacy")) box(in, w, cx, y, cz, Material.LIME_CONCRETE, -0.5, 1.0, -0.2, 1.0, 0.3, 0.4);
                if (kind.equals("bank") && Math.floorMod(x, 2) == 0) box(in, w, cx, y, cz, Material.IRON_BLOCK, -0.5, 0, -0.5, 1.0, 2.0, 1.0);
                used.add(k);
            }
        }
        if (kind.equals("bank")) box(in, w, sx + 0.5, y, sz + 0.5, Material.LIGHT_BLUE_STAINED_GLASS, -0.9, 1.1, -0.1, 1.8, 1.0, 0.1);
        if (kind.equals("bakery") || kind.equals("ice_cream") || kind.equals("cafe")) box(in, w, sx + 0.5, y, sz + 0.5, Material.GLASS, -0.8, 1.1, -0.3, 1.6, 0.5, 0.6); // the display case
        // where customers can walk
        for (long k : cells) if (!used.contains(k) && !wallAdj.contains(k)) in.free.add(new Location(w, (int) (k >> 32) + 0.5, y, (int) k + 0.5));
        // the lights in the ceiling (only while the shop is open)
        for (long k : cells) {
            int x = (int) (k >> 32), z = (int) k;
            if (Math.floorMod(x + z, 4) == 0) in.lights.add(new long[]{x, in.gy + 3, z});
        }
        return in;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void clear(Interior in) {
        for (BlockDisplay b : in.parts) b.remove();
        in.parts.clear();
        if (in.staff != null) in.staff.remove();
    }

    // ---- the loop ----

    private void tick() {
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        long now = System.currentTimeMillis();
        List<Player> players = earth.getPlayers();
        Set<String> wanted = new HashSet<>();
        for (Player p : players) {
            int built = 0;
            for (Places.Poi q : places.shopsNear(p.getLocation().getX(), p.getLocation().getZ(), 30)) {
                String k = key(q);
                wanted.add(k);
                Interior in = interiors.get(k);
                if (in == null || (in.failed && now - in.failedAt > 60000)) {
                    if (built++ >= 2) continue; // not more than two a second
                    Interior nin = build(q, earth);
                    if (nin == null) {
                        nin = new Interior();
                        nin.q = q;
                        nin.failed = true;
                        nin.failedAt = now;
                    }
                    interiors.put(k, nin);
                }
            }
        }
        for (java.util.Iterator<Map.Entry<String, Interior>> it = interiors.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Interior> e = it.next();
            Interior in = e.getValue();
            boolean near = false;
            for (Player p : players) if (Math.hypot(p.getLocation().getX() - in.q.x, p.getLocation().getZ() - in.q.z) < 50) near = true;
            if (!near) {
                clear(in);
                it.remove();
                continue;
            }
            if (in.failed) continue;
            // the worker behind the counter, only while it is open
            boolean open = Places.openAt(in.q.oh, in.q.lon, now);
            if (open && (in.staff == null || !in.staff.isValid())) {
                final String nm = People.NAMES[rnd.nextInt(People.NAMES.length)];
                in.staff = earth.spawn(in.kitchen, Villager.class, v -> {
                    v.setAI(false);
                    v.setSilent(true);
                    v.setInvulnerable(true);
                    v.setPersistent(false);
                    v.addScoreboardTag(TAG);
                    v.addScoreboardTag("wt_staff");
                    v.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
                    v.customName(Component.text(nm + " - " + in.q.name, NamedTextColor.GOLD));
                    v.setCustomNameVisible(true);
                });
                People.registerStaff(in.staff, nm + "|" + in.q.name + "|" + (in.q.kind == null ? "shop" : in.q.kind.replace('_', ' ')));
                in.staff.setRotation((float) rnd.nextInt(360), 0);
            } else if (!open && in.staff != null) {
                in.staff.remove();
                in.staff = null;
            }
        }
        // the lights: sent to each player who is near an open shop (the client lights the room)
        for (Player p : players) {
            Set<Long> need = new HashSet<>();
            Map<Long, long[]> pos = new HashMap<>();
            for (Interior in : interiors.values()) {
                if (in.failed || !Places.openAt(in.q.oh, in.q.lon, now)) continue;
                if (Math.hypot(p.getLocation().getX() - in.q.x, p.getLocation().getZ() - in.q.z) > 40) continue;
                for (long[] l : in.lights) {
                    long k = pack((int) l[0], (int) l[2]) ^ (l[1] << 50);
                    need.add(k);
                    pos.put(k, l);
                }
            }
            Set<Long> had = lit.computeIfAbsent(p.getUniqueId(), x -> new HashSet<>());
            for (long k : need) if (!had.contains(k)) {
                long[] l = pos.get(k);
                p.sendBlockChange(new Location(earth, l[0], l[1], l[2]), LIGHT);
            }
            for (long k : new ArrayList<>(had)) if (!need.contains(k)) {
                int x = (int) (k >> 32);
                int z = (int) k;
                // the real block again (the key mixes the height in: look it up from the old position list)
                had.remove(k);
                restore(p, earth, k);
            }
            had.addAll(need);
            posMem.putAll(pos);
        }
        // customers inside
        for (java.util.Iterator<Shopper> it = shoppers.iterator(); it.hasNext(); ) {
            Shopper s = it.next();
            if (!s.v.isValid() || now > s.leaveAt) {
                s.v.remove();
                it.remove();
                continue;
            }
            if (now > s.hopAt) {
                s.hopAt = now + 3000 + rnd.nextInt(4000);
                s.v.getPathfinder().moveTo(s.in.browse(rnd));
            }
        }
        lit.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private final Map<Long, long[]> posMem = new HashMap<>();

    private void restore(Player p, World w, long k) {
        long[] l = posMem.get(k);
        if (l == null) return;
        Location loc = new Location(w, l[0], l[1], l[2]);
        p.sendBlockChange(loc, loc.getBlock().getBlockData());
    }

    /** a villager who came to this shop goes in: he walks around inside for a while and then leaves */
    boolean enter(Villager v, Places.Poi q) {
        Interior in = get(q);
        if (in == null || in.free.isEmpty()) return false;
        v.teleport(in.browse(rnd));
        v.removeScoreboardTag("wt_ped");
        v.addScoreboardTag(SHOPPER);
        Shopper s = new Shopper();
        s.v = v;
        s.in = in;
        s.leaveAt = System.currentTimeMillis() + 20000 + rnd.nextInt(25000);
        s.hopAt = System.currentTimeMillis() + 1500;
        shoppers.add(s);
        return true;
    }
}
