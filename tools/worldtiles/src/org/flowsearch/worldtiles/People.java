package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Random;

/** People on the streets: a few villagers walk around near each player, on paving and roads, and are gone when nobody is near. */
public final class People implements Listener {
    private static final String TAG = "wt_ped";
    private final JavaPlugin plugin;
    private final TileGen gen;
    private final Random rnd = new Random();

    static volatile People instance;

    People(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
        instance = this;
    }

    /** a worker that another class made: so that a click on him tells what he does */
    static void registerStaff(Villager v, String info) {
        People p = instance;
        if (p != null) p.names.put(v.getUniqueId(), info);
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 40L);
    }

    void removeAll() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(STAFF)) e.remove();
    }

    static volatile Houses houses;
    private static final String STAFF = "wt_staff";
    static final String[] NAMES = {"Anna", "Ben", "Clara", "David", "Emma", "Felix", "Greta", "Hans", "Ida", "Jonas", "Klara", "Leon", "Mia", "Noah", "Olga", "Paul", "Rosa", "Sven", "Tina", "Uwe",
            "Vera", "Willi", "Yara", "Zoe", "Ahmed", "Bella", "Carlos", "Dilara", "Elif", "Farid", "Giulia", "Hiro", "Ivan", "Jana", "Kemal", "Lena", "Marco", "Nina", "Omar", "Petra", "Rafael", "Sofia", "Tomas", "Ute", "Viktor", "Wanda", "Yusuf", "Zeynep"};
    private final java.util.Map<java.util.UUID, String> names = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Houses.Info> homes = new java.util.HashMap<>();
    private final java.util.Map<String, Villager> staffAt = new java.util.HashMap<>();

    /** an open shop has a villager at its door who works there */
    private void staff(World w, List<Player> players) {
        Places pl = places;
        if (pl == null) return;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        long now = System.currentTimeMillis();
        for (Player p : players) {
            int n = 0;
            for (Places.Poi q : pl.shopsNear(p.getLocation().getX(), p.getLocation().getZ(), 40)) {
                if (n >= 5 || !Places.openAt(q.oh, q.lon, now) || ShopInterior.has(q)) continue; // with an inside, the worker is behind the counter
                n++;
                String key = Shops.keyOf(q);
                wanted.add(key);
                Villager cur = staffAt.get(key);
                if (cur != null && cur.isValid()) continue;
                Location spot = null;
                for (int t = 0; t < 30 && spot == null; t++) { // a free place 2 to 5 blocks from the shop
                    double a = rnd.nextDouble() * Math.PI * 2, d = 2 + rnd.nextDouble() * 3;
                    int x = (int) Math.floor(q.x + Math.cos(a) * d), z = (int) Math.floor(q.z + Math.sin(a) * d);
                    int[] c = gen.cell(x, z);
                    if (c != null && c[1] == 0 && c[3] == 0) spot = new Location(w, x + 0.5, c[0] + 1, z + 0.5, (float) Math.toDegrees(Math.atan2(-(q.x - x), q.z - z)), 0);
                }
                if (spot == null) continue;
                final String nm = NAMES[rnd.nextInt(NAMES.length)];
                Villager v = w.spawn(spot, Villager.class, e -> {
                    e.setAI(false);
                    e.setSilent(true);
                    e.setInvulnerable(true);
                    e.setPersistent(false);
                    e.addScoreboardTag(STAFF);
                    e.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
                    e.customName(Component.text(nm + " - " + q.name, NamedTextColor.GOLD));
                    e.setCustomNameVisible(true);
                });
                names.put(v.getUniqueId(), nm + "|" + q.name + "|" + (q.kind == null ? "shop" : q.kind.replace('_', ' ')));
                staffAt.put(key, v);
            }
        }
        for (java.util.Iterator<java.util.Map.Entry<String, Villager>> it = staffAt.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, Villager> e = it.next();
            if (wanted.contains(e.getKey()) && e.getValue().isValid()) continue;
            e.getValue().remove();
            names.remove(e.getValue().getUniqueId());
            it.remove();
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        boolean ped = ent.getScoreboardTags().contains(TAG), st = ent.getScoreboardTags().contains(STAFF);
        if (!ped && !st) return;
        e.setCancelled(true); // no trading
        Player p = e.getPlayer();
        String nm = names.get(ent.getUniqueId());
        if (nm == null) return;
        if (st) {
            String[] d = nm.split("\\|");
            p.sendMessage(Component.text(d[0] + ": \"I work at " + d[1] + " (" + d[2] + "). We are open now!\"", NamedTextColor.GOLD));
            return;
        }
        Houses.Info h = homes.get(ent.getUniqueId());
        String home = "somewhere nearby";
        if (h != null) {
            Houses hs = houses;
            String land = hs == null ? null : hs.landlord(h);
            home = "in a " + h.cat + " (" + h.area + " m2, " + h.floors + (h.floors == 1 ? " floor)" : " floors)") + (land != null ? " that belongs to " + land : "");
        }
        p.sendMessage(Component.text(nm + ": \"Hello! I live " + home + ". I am on my way to the shops.\"", NamedTextColor.GREEN));
    }

    static volatile Places places;
    private final java.util.Map<java.util.UUID, Object[]> visiting = new java.util.HashMap<>(); // villager -> {the shop, give up at}

    /** now and then a villager walks to an open shop nearby, goes in (is gone) and pays: a shop of a player earns something */
    private void visit(Villager v, World w) {
        Places pl = places;
        if (pl == null) return;
        Location at = v.getLocation();
        Object[] vis = visiting.get(v.getUniqueId());
        if (vis == null) {
            if (rnd.nextInt(6) != 0) return;
            Places.Poi q = pl.randomOpenShop(at.getX(), at.getZ(), 45, rnd);
            if (q == null) return;
            visiting.put(v.getUniqueId(), new Object[]{q, System.currentTimeMillis() + 40000});
            vis = visiting.get(v.getUniqueId());
        }
        Places.Poi q = (Places.Poi) vis[0];
        if (Math.hypot(q.x - at.getX(), q.z - at.getZ()) < 6) {
            visiting.remove(v.getUniqueId());
            w.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1.8, 0), 6, 0.3, 0.3, 0.3);
            Shops sh = Places.shops;
            if (sh != null) sh.visit(q);
            ShopInterior si = ShopInterior.instance;
            if (si != null && si.enter(v, q)) return; // he goes into the shop and walks around in it
            v.remove();
            return;
        }
        if (System.currentTimeMillis() > (long) vis[1]) {
            visiting.remove(v.getUniqueId());
            return;
        }
        int[] c = gen.cell(q.x, q.z);
        if (c != null) v.getPathfinder().moveTo(new Location(w, q.x + 0.5, c[0] + 1, q.z + 0.5));
    }

    private void tick() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getGenerator() != gen) continue;
            List<Player> players = w.getPlayers();
            staff(w, players);
            for (Entity e : w.getEntitiesByClass(Villager.class)) {
                if (!e.getScoreboardTags().contains(TAG)) continue;
                boolean near = false;
                for (Player p : players) if (p.getLocation().distanceSquared(e.getLocation()) < 70 * 70) near = true;
                if (!near) {
                    visiting.remove(e.getUniqueId());
                    e.remove();
                    continue;
                }
                visit((Villager) e, w);
            }
            for (Player p : players) {
                Location pl = p.getLocation();
                int count = 0;
                for (Entity e : p.getNearbyEntities(45, 30, 45)) if (e instanceof Villager && e.getScoreboardTags().contains(TAG)) count++;
                if (count >= 8) continue;
                for (int tries = 0; tries < 6; tries++) { // a place 15 to 40 blocks away that is a street or a square
                    double ang = rnd.nextDouble() * Math.PI * 2, d = 15 + rnd.nextDouble() * 25;
                    int x = (int) Math.floor(pl.getX() + Math.cos(ang) * d), z = (int) Math.floor(pl.getZ() + Math.sin(ang) * d);
                    int[] c = gen.cell(x, z);
                    if (c == null || c[1] > 0 || c[3] == 1) continue;
                    if (c[2] != Tile.S_ASPHALT && c[2] != Tile.S_PLAZA) continue;
                    Location at = new Location(w, x + 0.5, c[0] + 1, z + 0.5, rnd.nextFloat() * 360, 0);
                    final String nm = NAMES[rnd.nextInt(NAMES.length)];
                    Villager nv = w.spawn(at, Villager.class, v -> {
                        v.addScoreboardTag(TAG);
                        v.setPersistent(false);
                        v.setSilent(true);
                        v.setInvulnerable(true);
                        v.setRemoveWhenFarAway(true);
                        v.customName(Component.text(nm));
                        v.setCustomNameVisible(false);
                        v.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
                    });
                    names.put(nv.getUniqueId(), nm);
                    Houses hh = houses;
                    if (hh != null) { // the house this villager lives in: the nearest building
                        Houses.Info home = hh.nearest(x, z, 30);
                        if (home != null) homes.put(nv.getUniqueId(), home);
                    }
                    break;
                }
            }
        }
    }
}
