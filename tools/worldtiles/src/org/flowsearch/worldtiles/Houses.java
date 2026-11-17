package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every real building of the map is a house: tiny, small, big, huge. /house buy buys the one you stand in or next to, /house home takes you in,
 * villagers pay rent, and in a house of yours you can open a shop (/house shop supermarket My Market) that villagers visit.
 */
public final class Houses implements CommandExecutor {
    /** a building found in the map data */
    static final class Info {
        String key, cat;
        int area, floors, minX, maxX, minZ, maxZ, cx, cz, gy, price;
        boolean dc; // a data center (one big building in 25 is one)
    }

    private static final class House {
        UUID owner;
        String ownerName, key, cat;
        int area, floors, price, cx, cz, gy;
        long last, since;
        double bonus;
        int staff;
        final List<String> furn = new ArrayList<>(); // the pieces that were bought (the bed, the table and the book shelf come with the house)
        String shopType, shopName;
        Places.Poi poi;
        boolean dc;          // a data center: the racks earn instead of the tenants
        int racks, cooling;  // the server racks that were bought, and the cooling level (0 to 3)
        double temp = 15;    // the real temperature there (checked every half hour)
        long tempAt;
    }

    private static final int RACK_PRICE = 500, RACK_INCOME = 22;

    /** the electricity of one rack for an hour: more when it is hot outside (the cooling gets better with every level) */
    private double powerCost(House h) {
        if (System.currentTimeMillis() - h.tempAt > 30 * 60000L) {
            h.tempAt = System.currentTimeMillis();
            final double lat = Cmds.latOf(h.cz), lon = Cmds.lonOf(h.cx);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?current=temperature_2m&latitude=%.3f&longitude=%.3f", lat, lon))).timeout(java.time.Duration.ofSeconds(20)).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"temperature_2m\"\\s*:\\s*(-?[0-9.]+)").matcher(r.body());
                    double t = Double.NaN;
                    while (m.find()) t = Double.parseDouble(m.group(1));
                    if (!Double.isNaN(t)) h.temp = t;
                } catch (Exception ignored) {
                }
            });
        }
        return 4 + Math.max(0, h.temp - 15) * 0.5 * (1 - 0.25 * h.cooling);
    }

    /** what a data center earns in an hour (the racks, minus their electricity) */
    private double dcPerHour(House h) {
        return h.racks * (RACK_INCOME - powerCost(h));
    }

    /** what can be bought for the house: name -> price */
    static final Map<String, Integer> CATALOG = new java.util.LinkedHashMap<>();

    static {
        CATALOG.put("sofa", 80);
        CATALOG.put("plant", 30);
        CATALOG.put("lamp", 40);
        CATALOG.put("tv", 150);
        CATALOG.put("fireplace", 250);
        CATALOG.put("aquarium", 300);
        CATALOG.put("piano", 400);
    }

    private static final double[][] SLOTS = {{-3.5, 1.5}, {3.5, 1.5}, {-3.5, -2.0}, {3.5, -2.0}, {0, -3.0}, {-1.5, 3.0}, {1.5, 3.0}};

    private static int capacity(House h) {
        return Math.min(8, 1 + h.area / 40);
    }

    private static int tenants(House h) {
        return (int) Math.max(0, Math.min(capacity(h), (System.currentTimeMillis() - h.since) / 1800000L)); // a new tenant every 30 minutes
    }

    private static String tenantName(House h, int i) {
        return People.NAMES[Math.floorMod(h.key.hashCode() + i * 7919, People.NAMES.length)];
    }

    /** shop type -> {smallest building area} (the price is the one of the real shops of that kind) */
    private static final Map<String, Integer> SHOP_AREA = Map.of("supermarket", 120, "bakery", 25, "cafe", 25, "restaurant", 50, "pharmacy", 40, "clothes", 40, "kiosk", 9, "bank", 120, "cinema", 200);

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final Economy eco;
    private final File file;
    private final Map<String, House> houses = new ConcurrentHashMap<>();
    private final Map<UUID, List<BlockDisplay>> furniture = new HashMap<>();
    private final Map<String, Info> cache = new ConcurrentHashMap<>();

    Houses(JavaPlugin plugin, TileGen gen, Economy eco) {
        this.plugin = plugin;
        this.gen = gen;
        this.eco = eco;
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "houses.txt");
        try {
            if (file.exists()) for (String line : Files.readAllLines(file.toPath())) {
                String[] p = line.split("\t", -1);
                if (p.length < 14) continue;
                House h = new House();
                h.owner = UUID.fromString(p[0]);
                h.ownerName = p[1];
                h.key = p[2];
                h.cat = p[3];
                h.area = Integer.parseInt(p[4]);
                h.floors = Integer.parseInt(p[5]);
                h.price = Integer.parseInt(p[6]);
                h.cx = Integer.parseInt(p[7]);
                h.cz = Integer.parseInt(p[8]);
                h.gy = Integer.parseInt(p[9]);
                h.last = Long.parseLong(p[10]);
                h.bonus = Double.parseDouble(p[11]);
                if (p.length > 14 && !p[14].isEmpty()) h.furn.addAll(java.util.Arrays.asList(p[14].split(",")));
                h.since = p.length > 15 ? Long.parseLong(p[15]) : h.last;
                h.staff = p.length > 16 ? Integer.parseInt(p[16]) : 0;
                h.racks = p.length > 17 ? Integer.parseInt(p[17]) : 0;
                h.cooling = p.length > 18 ? Integer.parseInt(p[18]) : 0;
                h.dc = p.length > 19 && p[19].equals("1");
                if (!p[12].isEmpty()) {
                    h.shopType = p[12];
                    h.shopName = p[13];
                    h.poi = poiOf(h);
                }
                houses.put(h.key, h);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("houses.txt could not be read: " + e);
        }
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 20L);
    }

    void removeAll() {
        for (List<BlockDisplay> l : furniture.values()) for (BlockDisplay b : l) b.remove();
        furniture.clear();
        for (org.bukkit.entity.TextDisplay t : dcLabel.values()) t.remove();
        dcLabel.clear();
    }

    private synchronized void save() {
        StringBuilder sb = new StringBuilder();
        for (House h : houses.values()) {
            sb.append(h.owner).append('\t').append(h.ownerName).append('\t').append(h.key).append('\t').append(h.cat).append('\t').append(h.area).append('\t').append(h.floors).append('\t')
                    .append(h.price).append('\t').append(h.cx).append('\t').append(h.cz).append('\t').append(h.gy).append('\t').append(h.last).append('\t').append(h.bonus).append('\t')
                    .append(h.shopType == null ? "" : h.shopType).append('\t').append(h.shopName == null ? "" : h.shopName.replace('\t', ' ')).append('\t').append(String.join(",", h.furn)).append('\t').append(h.since).append('\t').append(h.staff).append('\t').append(h.racks).append('\t').append(h.cooling).append('\t').append(h.dc ? 1 : 0).append('\n');
        }
        try {
            Files.writeString(file.toPath(), sb.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("houses.txt could not be written: " + e);
        }
    }

    // ---- finding the building ----

    private boolean wall(int x, int z) {
        int[] c = gen.cell(x, z);
        return c != null && c[1] > 0;
    }

    /** the building that a block belongs to (all connected house blocks), or null */
    Info analyse(int x, int z) {
        if (!wall(x, z)) return null;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(new int[]{x, z});
        seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
        int minX = x, maxX = x, minZ = z, maxZ = z, maxH = 0, n = 0;
        while (!q.isEmpty() && n < 9000) {
            int[] c = q.poll();
            n++;
            int[] cell = gen.cell(c[0], c[1]);
            if (cell != null) maxH = Math.max(maxH, cell[1]);
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
            int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] dd : d) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                long k = ((long) nx << 32) ^ (nz & 0xFFFFFFFFL);
                if (!seen.add(k) || !wall(nx, nz)) continue;
                q.add(new int[]{nx, nz});
            }
        }
        Info i = new Info();
        i.area = n;
        i.floors = Math.max(1, maxH / 4);
        i.minX = minX;
        i.maxX = maxX;
        i.minZ = minZ;
        i.maxZ = maxZ;
        i.key = "b|" + minX + "|" + minZ;
        i.cat = i.area < 40 ? "tiny house" : i.area < 120 ? "small house" : i.area < 300 ? "house" : i.area < 800 ? "large building" : "huge building";
        i.price = (int) (Math.round(i.area * (6.0 + 2.0 * i.floors) / 10.0) * 10);
        int[] first = gen.cell(x, z);
        if (first != null && first.length > 4 && first[4] == 40) { // the wall of a data center
            i.dc = true;
            i.cat = "data center";
            i.price = Math.max(20000, i.price * 3);
        }
        // a block inside the building, nearest to the middle of it
        double mx = (minX + maxX) / 2.0, mz = (minZ + maxZ) / 2.0, bd = 1e18;
        i.cx = x;
        i.cz = z;
        for (long k : seen) {
            int sx = (int) (k >> 32), sz = (int) k;
            double d = Math.hypot(sx - mx, sz - mz);
            if (d < bd && wall(sx, sz)) {
                bd = d;
                i.cx = sx;
                i.cz = sz;
            }
        }
        int[] c = gen.cell(i.cx, i.cz);
        i.gy = c != null ? c[0] : TileGen.SEA;
        cache.put(i.key, i);
        return i;
    }

    /** the nearest building within r blocks */
    Info nearest(double x, double z, int r) {
        for (int d = 0; d <= r; d += 2) {
            for (int dz = -d; dz <= d; dz += 2) {
                for (int dx = -d; dx <= d; dx += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    Info i = analyse((int) Math.floor(x) + dx, (int) Math.floor(z) + dz);
                    if (i != null) return i;
                }
            }
        }
        return null;
    }

    // ---- shops of the players ----

    private Places.Poi poiOf(House h) {
        Places.Poi q = new Places.Poi();
        q.name = h.shopName;
        q.kind = h.shopType;
        q.oh = h.shopType.equals("supermarket") ? "Mo-Sa 07:00-22:00" : h.shopType.equals("bank") ? "Mo-Fr 09:00-17:00" : "Mo-Su 08:00-20:00";
        q.x = h.cx;
        q.z = h.cz;
        q.lat = Cmds.latOf(h.cz);
        q.lon = Cmds.lonOf(h.cx);
        return q;
    }

    /** the shops that players opened in their houses (shown and visited like the real ones) */
    List<Places.Poi> pois(double lat, double lon) {
        List<Places.Poi> r = new ArrayList<>();
        for (House h : houses.values()) if (h.poi != null && Math.abs(h.poi.lat - lat) < 0.008 && Math.abs(h.poi.lon - lon) < 0.012) r.add(h.poi);
        return r;
    }

    String ownerOf(Places.Poi q) {
        for (House h : houses.values()) if (h.poi == q) return h.ownerName;
        return null;
    }

    /** somebody paid the shop of a player */
    void earn(Places.Poi q, double amount) {
        for (House h : houses.values()) {
            if (h.poi != q) continue;
            h.bonus += amount;
            save();
            Player p = plugin.getServer().getPlayer(h.owner);
            if (p != null) p.sendActionBar(Component.text(String.format(Locale.ROOT, "A customer paid %s: +%.0f coins (/house collect)", h.shopName, amount), NamedTextColor.GOLD));
        }
    }

    void visit(Places.Poi q) {
        for (House h : houses.values()) {
            if (h.poi != q) continue;
            double v = Shops.priceOf(h.shopType) / 40.0;
            h.bonus += v;
            save();
            Player p = plugin.getServer().getPlayer(h.owner);
            if (p != null) p.sendActionBar(Component.text(String.format(Locale.ROOT, "A customer came into %s: +%.0f coins (/house collect)", h.shopName, v), NamedTextColor.GOLD));
        }
    }

    /** who owns the building a place is in */
    String landlord(Info i) {
        House h = houses.get(i.key);
        return h == null ? null : h.ownerName;
    }

    private double pending(House h) {
        long now = System.currentTimeMillis();
        long from = Math.max(h.last, now - 48L * 3600 * 1000);
        double hours = (now - from) / 3600000.0;
        // the tenants pay: the more of them live there, and the nicer it is inside, the more
        double rent = h.dc ? 0 : hours * h.price / 60.0 * ((double) tenants(h) / capacity(h)) * (1 + 0.1 * h.furn.size());
        double shop = 0;
        if (h.poi != null) {
            int open = 0;
            for (long t = from; t < now; t += 600000) if (Places.openAt(h.poi.oh, h.poi.lon, t)) open++;
            shop = open / 6.0 * Shops.priceOf(h.shopType) / 3.0 * (1 + 0.25 * h.staff); // every worker you hired makes it earn a quarter more
        }
        double dc = h.dc ? hours * dcPerHour(h) : 0; // a data center earns from its racks
        return rent + shop + h.bonus + dc;
    }

    // ---- the furniture of a house you are near ----

    // ---- the data centers lying around: a label on each one near a player (for sale, or whose it is) ----

    private final Map<String, Info> dcKnown = new HashMap<>();
    private final Map<String, org.bukkit.entity.TextDisplay> dcLabel = new HashMap<>();
    private int dcTick;

    private void dcLabels() {
        dcTick++;
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getWorld().getGenerator() == gen) players.add(p);
        if (players.isEmpty()) {
            for (org.bukkit.entity.TextDisplay t : dcLabel.values()) t.remove();
            dcLabel.clear();
            return;
        }
        if (dcTick % 2 == 0) { // looking for new ones around the players
            for (Player p : players) {
                int px = (int) Math.floor(p.getLocation().getX()), pz = (int) Math.floor(p.getLocation().getZ());
                for (int dx = -64; dx <= 64; dx += 8) {
                    for (int dz = -64; dz <= 64; dz += 8) {
                        int[] c = gen.cell(px + dx, pz + dz);
                        if (c == null || c[1] == 0 || c.length < 5 || c[4] != 40) continue;
                        boolean known = false;
                        for (Info k : dcKnown.values()) if (px + dx >= k.minX && px + dx <= k.maxX && pz + dz >= k.minZ && pz + dz <= k.maxZ) known = true;
                        if (known) continue;
                        Info i = analyse(px + dx, pz + dz);
                        if (i != null && i.dc) dcKnown.put(i.key, i);
                    }
                }
            }
        }
        for (Map.Entry<String, Info> e : new ArrayList<>(dcKnown.entrySet())) {
            Info i = e.getValue();
            double near = 1e18;
            for (Player p : players) near = Math.min(near, Math.hypot(p.getLocation().getX() - i.cx, p.getLocation().getZ() - i.cz));
            org.bukkit.entity.TextDisplay t = dcLabel.get(e.getKey());
            if (near > 130) {
                if (t != null) {
                    t.remove();
                    dcLabel.remove(e.getKey());
                }
                continue;
            }
            House h = houses.get(i.key);
            Component text = Component.text("DATA CENTER", NamedTextColor.AQUA).append(Component.newline()).append(h == null
                    ? Component.text("for sale: " + i.price + " coins (/house buy)", NamedTextColor.GREEN)
                    : Component.text("owned by " + h.ownerName + ", " + h.racks + " racks", NamedTextColor.GOLD));
            if (t == null || !t.isValid()) {
                World w = players.get(0).getWorld();
                int[] c = gen.cell(i.cx, i.cz);
                int wh = c == null ? 10 : c[1];
                t = w.spawn(new Location(w, i.cx + 0.5, i.gy + wh + 6, i.cz + 0.5), org.bukkit.entity.TextDisplay.class, ent -> {
                    ent.setPersistent(false);
                    ent.setBillboard(Display.Billboard.CENTER);
                    ent.setBackgroundColor(org.bukkit.Color.fromARGB(150, 0, 0, 0));
                    ent.setViewRange(1.5f);
                });
                dcLabel.put(e.getKey(), t);
            }
            t.text(text);
        }
    }

    private void tick() {
        dcLabels();
        for (Player p : Bukkit.getOnlinePlayers()) {
            List<BlockDisplay> f = furniture.get(p.getUniqueId());
            House near = null;
            for (House h : houses.values()) {
                if (!h.owner.equals(p.getUniqueId()) || p.getWorld().getGenerator() != gen) continue;
                if (Math.abs(h.cx - p.getLocation().getX()) < 25 && Math.abs(h.cz - p.getLocation().getZ()) < 25) near = h;
            }
            if (near == null) {
                if (f != null) {
                    for (BlockDisplay b : f) b.remove();
                    furniture.remove(p.getUniqueId());
                }
                continue;
            }
            if (f == null || f.isEmpty() || !f.get(0).isValid()) {
                List<BlockDisplay> list = new ArrayList<>();
                World w = p.getWorld();
                double x = near.cx + 0.5, z = near.cz + 0.5, y = near.gy + 1;
                if (near.dc) { // a data center: rows of server racks with blinking lights
                    for (int k = 0; k < Math.min(near.racks, 24); k++) {
                        double ox = (k % 6 - 2.5) * 1.5, oz = (k / 6 - 1.5) * 2.2;
                        box(list, w, x, y, z, Material.BLACK_CONCRETE, ox - 0.45, 0, oz - 0.55, 0.9, 2.2, 1.1);
                        box(list, w, x, y, z, Material.LIME_CONCRETE, ox - 0.3, 0.4 + (k % 3) * 0.5, oz - 0.58, 0.6, 0.08, 0.04);
                        box(list, w, x, y, z, Material.LIGHT_BLUE_CONCRETE, ox - 0.3, 1.5 + (k % 2) * 0.3, oz - 0.58, 0.6, 0.08, 0.04);
                    }
                    furniture.put(p.getUniqueId(), list);
                    continue;
                }
                box(list, w, x, y, z, Material.RED_WOOL, -1.5, 0, -0.5, 1, 0.5, 2);       // bed
                box(list, w, x, y, z, Material.WHITE_WOOL, -1.5, 0.5, -0.5, 1, 0.15, 0.5); // pillow
                box(list, w, x, y, z, Material.SPRUCE_PLANKS, 1.0, 0.75, -0.5, 1.4, 0.12, 1); // table
                for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) box(list, w, x, y, z, Material.SPRUCE_FENCE, 1.1 + a * 1.2, 0, -0.4 + b * 0.8, 0.12, 0.75, 0.12);
                box(list, w, x, y, z, Material.BOOKSHELF, 0, 0, 1.5, 2, 2, 1);               // book shelf
                box(list, w, x, y, z, Material.LANTERN, 1.5, 0.87, -0.1, 0.4, 0.5, 0.4);
                for (int k = 0; k < near.furn.size() && k < SLOTS.length; k++) item(list, w, x + SLOTS[k][0], y, z + SLOTS[k][1], near.furn.get(k));
                furniture.put(p.getUniqueId(), list);
            }
        }
    }

    private void dropFurniture(Player p) {
        List<BlockDisplay> f = furniture.remove(p.getUniqueId());
        if (f != null) for (BlockDisplay b : f) b.remove(); // they are made again within a second
    }

    /** one bought piece of furniture at a place */
    private void item(List<BlockDisplay> l, World w, double x, double y, double z, String what) {
        switch (what) {
            case "sofa" -> {
                box(l, w, x, y, z, Material.GRAY_WOOL, -1.1, 0, -0.45, 2.2, 0.5, 0.9);
                box(l, w, x, y, z, Material.LIGHT_GRAY_WOOL, -1.1, 0.5, 0.25, 2.2, 0.5, 0.2);
            }
            case "plant" -> box(l, w, x, y, z, Material.POTTED_FERN, -0.5, 0, -0.5, 1, 1, 1);
            case "lamp" -> {
                box(l, w, x, y, z, Material.SPRUCE_FENCE, -0.1, 0, -0.1, 0.2, 1.6, 0.2);
                box(l, w, x, y, z, Material.LANTERN, -0.25, 1.6, -0.25, 0.5, 0.6, 0.5);
            }
            case "tv" -> {
                box(l, w, x, y, z, Material.SMOOTH_STONE_SLAB, -1.0, 0, -0.3, 2.0, 0.6, 0.6);
                box(l, w, x, y, z, Material.BLACK_CONCRETE, -0.8, 0.6, -0.1, 1.6, 0.9, 0.1);
            }
            case "fireplace" -> {
                box(l, w, x, y, z, Material.BRICKS, -1.0, 0, -0.3, 2.0, 1.4, 0.6);
                box(l, w, x, y, z, Material.MAGMA_BLOCK, -0.5, 0, -0.35, 1.0, 0.4, 0.2);
                box(l, w, x, y, z, Material.ORANGE_STAINED_GLASS, -0.4, 0.4, -0.35, 0.8, 0.5, 0.15);
            }
            case "aquarium" -> {
                box(l, w, x, y, z, Material.OAK_PLANKS, -0.9, 0, -0.4, 1.8, 0.6, 0.8);
                box(l, w, x, y, z, Material.LIGHT_BLUE_STAINED_GLASS, -0.9, 0.6, -0.4, 1.8, 0.9, 0.8);
                box(l, w, x, y, z, Material.SEA_LANTERN, -0.85, 0.62, -0.35, 1.7, 0.1, 0.7);
            }
            case "piano" -> {
                box(l, w, x, y, z, Material.BLACK_CONCRETE, -0.9, 0, -0.4, 1.8, 1.0, 0.8);
                box(l, w, x, y, z, Material.WHITE_CONCRETE, -0.85, 0.7, -0.45, 1.7, 0.1, 0.12);
                box(l, w, x, y, z, Material.SPRUCE_SLAB, -0.3, 0, 0.7, 0.6, 0.4, 0.5);
            }
            default -> {
            }
        }
    }

    private void box(List<BlockDisplay> out, World w, double x, double y, double z, Material m, double ox, double oy, double oz, double sx, double sy, double sz) {
        BlockDisplay b = w.spawn(new Location(w, x, y, z), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(12, 12));
            e.setViewRange(0.6f);
            e.setTransformation(new Transformation(new Vector3f((float) ox, (float) oy, (float) oz), new Quaternionf(), new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
        out.add(b);
    }

    // ---- /house ----

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        UUID me = p.getUniqueId();
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (p.getWorld().getGenerator() != gen && !a.equals("list") && !a.equals("collect")) {
            p.sendMessage(Component.text("Houses are on Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        switch (a) {
            case "list" -> {
                int n = 0;
                double total = 0;
                for (House h : houses.values()) {
                    if (!h.owner.equals(me)) continue;
                    n++;
                    double pe = pending(h);
                    total += pe;
                    p.sendMessage(Component.text(String.format(Locale.ROOT, " %d. %s, %d m2, %d floors, %d/%d rooms rented%s, %.0f coins waiting, at /tpll %.5f %.5f", n, h.cat, h.area, h.floors, tenants(h), capacity(h),
                            h.shopType == null ? "" : ", shop " + h.shopName + " (" + h.shopType + ", " + h.staff + " staff)", pe, Cmds.latOf(h.cz), Cmds.lonOf(h.cx)), NamedTextColor.WHITE));
                }
                p.sendMessage(Component.text(n == 0 ? "You own no house. Stand in a building and /house buy." : String.format(Locale.ROOT, "%d houses, %.0f coins waiting: /house collect. /house home <number> takes you in.", n, total), NamedTextColor.GREEN));
                return true;
            }
            case "collect" -> {
                long sum = 0;
                int n = 0;
                for (House h : houses.values()) {
                    if (!h.owner.equals(me)) continue;
                    sum += Math.round(pending(h));
                    h.last = System.currentTimeMillis();
                    h.bonus = 0;
                    n++;
                }
                if (n == 0) {
                    p.sendMessage(Component.text("You own no house.", NamedTextColor.YELLOW));
                    return true;
                }
                eco.add(me, sum);
                save();
                p.sendMessage(Component.text("Collected " + sum + " coins (rent and shops) from " + n + " houses. You have " + eco.get(me) + " coins.", NamedTextColor.GOLD));
                return true;
            }
            case "home" -> {
                int want = 1;
                try {
                    if (args.length > 1) want = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {
                }
                int n = 0;
                for (House h : houses.values()) {
                    if (!h.owner.equals(me) || ++n != want) continue;
                    World w = p.getWorld().getGenerator() == gen ? p.getWorld() : Bukkit.getWorlds().get(0);
                    p.sendMessage(Component.text("Going home to your " + h.cat + "...", NamedTextColor.GREEN));
                    final House fh = h;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        gen.surfaceY(fh.cx, fh.cz, true); // waits for the square
                        Bukkit.getScheduler().runTask(plugin, () -> p.teleportAsync(new Location(w, fh.cx + 0.5, fh.gy + 1, fh.cz + 0.5, p.getLocation().getYaw(), 0)));
                    });
                    return true;
                }
                p.sendMessage(Component.text("You have no house number " + want + " (/house list).", NamedTextColor.YELLOW));
                return true;
            }
            default -> {
            }
        }
        Info i = nearest(p.getLocation().getX(), p.getLocation().getZ(), 12);
        if (i == null) {
            p.sendMessage(Component.text("No building within 12 blocks. Walk up to one. Other commands: /house list, /house collect, /house home.", NamedTextColor.YELLOW));
            return true;
        }
        House h = houses.get(i.key);
        if (a.equals("buy")) {
            if (h != null) {
                p.sendMessage(Component.text("This building belongs to " + h.ownerName + ".", NamedTextColor.RED));
                return true;
            }
            if (eco.get(me) < i.price) {
                p.sendMessage(Component.text("This " + i.cat + " costs " + i.price + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                return true;
            }
            eco.add(me, -i.price);
            House n = new House();
            n.owner = me;
            n.ownerName = p.getName();
            n.key = i.key;
            n.cat = i.cat;
            n.area = i.area;
            n.floors = i.floors;
            n.price = i.price;
            n.cx = i.cx;
            n.cz = i.cz;
            n.gy = i.gy;
            n.dc = i.dc;
            n.last = System.currentTimeMillis();
            n.since = n.last;
            houses.put(i.key, n);
            save();
            p.sendMessage(Component.text("You bought a " + i.cat + " (" + i.area + " m2, " + i.floors + " floors) for " + i.price + " coins! Villagers will pay rent: about " + i.price / 60 + " coins per hour. /house home takes you in, with furniture.", NamedTextColor.GOLD));
            return true;
        }
        if (a.equals("sell")) {
            if (h == null || !h.owner.equals(me)) {
                p.sendMessage(Component.text("This is not your building.", NamedTextColor.RED));
                return true;
            }
            long back = Math.round(h.price * 0.7 + pending(h));
            eco.add(me, back);
            houses.remove(h.key);
            save();
            p.sendMessage(Component.text("Sold for " + back + " coins (70% of the price plus what was waiting).", NamedTextColor.GOLD));
            return true;
        }
        if (a.equals("furnish") || a.equals("tenants") || a.equals("hire") || a.equals("fire") || a.equals("rack") || a.equals("cooling") || a.equals("datacenter")) {
            if (h == null || !h.owner.equals(me)) {
                p.sendMessage(Component.text("Stand next to a building that is yours.", NamedTextColor.RED));
                return true;
            }
            if (a.equals("rack") || a.equals("cooling") || a.equals("datacenter")) {
                if (!h.dc) {
                    p.sendMessage(Component.text("Only a data center has racks (the dark gray buildings with a label: one big building in 25).", NamedTextColor.YELLOW));
                    return true;
                }
                int maxRacks = Math.max(4, h.area / 8);
                if (a.equals("rack")) {
                    int n = 1;
                    try {
                        if (args.length > 1) n = Math.max(1, Math.min(20, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                    }
                    n = Math.min(n, maxRacks - h.racks);
                    if (n <= 0) {
                        p.sendMessage(Component.text("The building is full: " + maxRacks + " racks.", NamedTextColor.YELLOW));
                        return true;
                    }
                    long cost = (long) n * RACK_PRICE;
                    if (eco.get(me) < cost) {
                        p.sendMessage(Component.text(n + " racks cost " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                        return true;
                    }
                    eco.add(me, Math.round(pending(h))); // what it has earned until now first
                    h.last = System.currentTimeMillis();
                    h.bonus = 0;
                    eco.add(me, -cost);
                    h.racks += n;
                    save();
                    dropFurniture(p);
                    p.sendMessage(Component.text("You bought " + n + " rack(s): " + h.racks + " of " + maxRacks + ".", NamedTextColor.GOLD));
                    return true;
                }
                if (a.equals("cooling")) {
                    if (h.cooling >= 3) {
                        p.sendMessage(Component.text("The cooling is at its best (level 3).", NamedTextColor.YELLOW));
                        return true;
                    }
                    int cost = 2000 * (h.cooling + 1);
                    if (eco.get(me) < cost) {
                        p.sendMessage(Component.text("Cooling level " + (h.cooling + 1) + " costs " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                        return true;
                    }
                    eco.add(me, Math.round(pending(h)));
                    h.last = System.currentTimeMillis();
                    h.bonus = 0;
                    eco.add(me, -cost);
                    h.cooling++;
                    save();
                    p.sendMessage(Component.text("Cooling level " + h.cooling + ": hot days cost you less electricity.", NamedTextColor.GOLD));
                    return true;
                }
                p.sendMessage(Component.text(String.format(Locale.ROOT, "Data center: %d of %d racks, cooling level %d. It is %.1f C there now. Each rack earns %d and costs %.1f of electricity an hour: %.0f coins an hour in all. A rack costs %d, /house rack [n]. Cooling: /house cooling.",
                        h.racks, maxRacks, h.cooling, h.temp, RACK_INCOME, powerCost(h), dcPerHour(h), RACK_PRICE), NamedTextColor.GREEN));
                return true;
            }
            if (a.equals("tenants")) {
                int t = tenants(h), cap = capacity(h);
                p.sendMessage(Component.text(String.format(Locale.ROOT, "%d of %d rooms are rented (a new tenant moves in every 30 minutes). Rent now: %.0f coins per hour (full: %.0f). Nice furniture adds 10%% per piece.", t, cap,
                        h.price / 60.0 * t / cap * (1 + 0.1 * h.furn.size()), h.price / 60.0 * (1 + 0.1 * h.furn.size())), NamedTextColor.GREEN));
                for (int k = 0; k < t; k++) p.sendMessage(Component.text("  " + tenantName(h, k) + " lives in room " + (k + 1), NamedTextColor.GRAY));
                return true;
            }
            if (a.equals("hire") || a.equals("fire")) {
                if (h.poi == null) {
                    p.sendMessage(Component.text("Staff work in shops. Open one first: /house shop <type> <name>.", NamedTextColor.YELLOW));
                    return true;
                }
                if (a.equals("fire")) {
                    if (h.staff > 0) h.staff--;
                    save();
                    p.sendMessage(Component.text("A worker left. Staff: " + h.staff + ".", NamedTextColor.YELLOW));
                    return true;
                }
                int cost = 200 * (h.staff + 1);
                if (h.staff >= 3) {
                    p.sendMessage(Component.text("The shop has the most staff it can have (3).", NamedTextColor.YELLOW));
                } else if (eco.get(me) < cost) {
                    p.sendMessage(Component.text("Hiring the next worker costs " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                } else {
                    eco.add(me, -cost);
                    h.staff++;
                    save();
                    p.sendMessage(Component.text("You hired a worker for " + h.shopName + " (" + cost + " coins). Staff: " + h.staff + ", the shop earns " + (25 * h.staff) + "% more.", NamedTextColor.GOLD));
                }
                return true;
            }
            // furnish
            int room = Math.min(SLOTS.length, h.area / 12);
            String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            if (what.equals("clear")) {
                h.furn.clear();
                save();
                dropFurniture(p);
                p.sendMessage(Component.text("The extra furniture is gone.", NamedTextColor.YELLOW));
                return true;
            }
            if (!CATALOG.containsKey(what)) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : CATALOG.entrySet()) sb.append(e.getKey()).append(" ").append(e.getValue()).append(", ");
                p.sendMessage(Component.text("/house furnish <piece>: " + sb + "(room for " + room + " pieces, you have " + h.furn.size() + "). /house furnish clear removes them.", NamedTextColor.GREEN));
                return true;
            }
            if (h.furn.size() >= room) {
                p.sendMessage(Component.text("There is no room for more: this building holds " + room + " extra pieces. A bigger one holds more.", NamedTextColor.YELLOW));
                return true;
            }
            int cost = CATALOG.get(what);
            if (eco.get(me) < cost) {
                p.sendMessage(Component.text("A " + what + " costs " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                return true;
            }
            eco.add(me, -cost);
            h.furn.add(what);
            save();
            dropFurniture(p);
            p.sendMessage(Component.text("You bought a " + what + " (" + cost + " coins). Tenants pay 10% more for every piece.", NamedTextColor.GOLD));
            return true;
        }
        if (a.equals("shop")) {
            if (h == null || !h.owner.equals(me)) {
                p.sendMessage(Component.text("Stand next to a building that is yours (/house buy first).", NamedTextColor.RED));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("close")) {
                h.shopType = null;
                h.shopName = null;
                h.poi = null;
                save();
                p.sendMessage(Component.text("The shop is closed. The building is a house again.", NamedTextColor.YELLOW));
                return true;
            }
            if (args.length < 3 || !SHOP_AREA.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                p.sendMessage(Component.text("/house shop <type> <name>   types: " + String.join(", ", new java.util.TreeSet<>(SHOP_AREA.keySet())) + "   (or /house shop close)", NamedTextColor.YELLOW));
                return true;
            }
            String type = args[1].toLowerCase(Locale.ROOT);
            if (h.area < SHOP_AREA.get(type)) {
                p.sendMessage(Component.text("A " + type + " needs a building of at least " + SHOP_AREA.get(type) + " m2. Yours has " + h.area + " m2.", NamedTextColor.RED));
                return true;
            }
            int fee = Shops.priceOf(type) / 4;
            if (eco.get(me) < fee) {
                p.sendMessage(Component.text("Opening a " + type + " costs " + fee + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                return true;
            }
            eco.add(me, -fee);
            h.last = System.currentTimeMillis();
            h.shopType = type;
            h.shopName = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            h.poi = poiOf(h);
            save();
            p.sendMessage(Component.text("Your " + type + " \"" + h.shopName + "\" is open (" + h.poi.oh + ")! It has a label, villagers shop there, and it earns about " + Shops.priceOf(type) / 3 + " coins per open hour. /house collect pays it out.", NamedTextColor.GOLD));
            return true;
        }
        // /house: what is this building
        String own = h == null ? "for sale: " + i.price + " coins (/house buy)" : h.owner.equals(me) ? "yours" + (h.shopType != null ? " (shop " + h.shopName + ")" : "") : "owned by " + h.ownerName;
        p.sendMessage(Component.text(String.format(Locale.ROOT, "%s: %d m2, %d floors, %s. Shop in it: /house shop <type> <name>.", i.cat, i.area, i.floors, own), NamedTextColor.GREEN));
        return true;
    }
}
