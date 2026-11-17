package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * /work next to a real restaurant, cafe, bar or snack bar: the CEO (a villager) gives you a 4 minute shift. Customers (villagers) walk up:
 * click one to take the order, click the kitchen to make it, click the customer to serve it. Quick service earns tips.
 */
public final class Work implements CommandExecutor, Listener {
    private static final String TAG = "wt_work";
    private static final long SHIFT_MS = 4 * 60 * 1000L, PATIENCE_MS = 90 * 1000L;
    private final Random rnd = new Random();

    private static final class Cust {
        Villager v;
        String order;
        int state; // 0 waits to order, 1 has ordered, 2 is served and leaves
        long deadline, left;
        Location spot;
    }

    private static final class Shift {
        Player p;
        Places.Poi shop;
        ShopInterior.Interior in;
        Location counter;
        Interaction kitchen;
        BlockDisplay stove;
        Villager ceo;
        final List<Cust> custs = new ArrayList<>();
        String carrying;
        long end, nextCust;
        int served, missed;
        long earned;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final Places places;
    private final Economy eco;
    private final Shops shops;
    private final Map<UUID, Shift> shifts = new HashMap<>();

    Work(JavaPlugin plugin, TileGen gen, Places places, Economy eco, Shops shops) {
        this.plugin = plugin;
        this.gen = gen;
        this.places = places;
        this.eco = eco;
        this.shops = shops;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 10L);
    }

    void removeAll() {
        for (Shift s : new ArrayList<>(shifts.values())) cleanup(s);
        shifts.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    private static String[] menu(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "cafe" -> new String[]{"Coffee", "Tea", "Cake", "Croissant"};
            case "restaurant" -> new String[]{"Pizza", "Pasta", "Salad", "Soup", "Steak"};
            case "fast_food" -> new String[]{"Burger", "Fries", "Kebab", "Wrap"};
            case "bar", "pub" -> new String[]{"Beer", "Cola", "Wine"};
            case "ice_cream" -> new String[]{"Ice cream", "Waffle"};
            case "bakery" -> new String[]{"Bread", "Pretzel", "Cake"};
            default -> null;
        };
    }

    /** a free place to stand (no house, no water) in a ring around a place; roads and paving are preferred */
    private Location open(World w, double cx, double cz, double r0, double r1, boolean preferRoad) {
        Location best = null;
        for (int i = 0; i < 80; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = r0 + rnd.nextDouble() * (r1 - r0);
            int x = (int) Math.floor(cx + Math.cos(a) * r), z = (int) Math.floor(cz + Math.sin(a) * r);
            int[] c = gen.cell(x, z);
            if (c == null || c[1] > 0 || c[3] == 1) continue;
            Location l = new Location(w, x + 0.5, c[0] + 1, z + 0.5);
            if (!preferRoad || c[2] == Tile.S_ASPHALT || c[2] == Tile.S_PLAZA) return l;
            if (best == null) best = l;
        }
        return best;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        Shift cur = shifts.get(p.getUniqueId());
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            if (cur != null) finish(cur, "You ended your shift.");
            return true;
        }
        if (cur != null) {
            p.sendMessage(Component.text("You are on shift (/work stop to end it): click a customer to take the order, the kitchen to make it, the customer to serve it.", NamedTextColor.YELLOW));
            return true;
        }
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("Work is for Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        Places.Poi q = places.nearest(p.getLocation().getX(), p.getLocation().getZ(), 25);
        if (q == null || menu(q.kind) == null) {
            p.sendMessage(Component.text("Stand next to a real restaurant, cafe, bar, snack bar, ice cream shop or bakery (/near shows them), then /work.", NamedTextColor.YELLOW));
            return true;
        }
        World w = p.getWorld();
        ShopInterior si = ShopInterior.instance;
        ShopInterior.Interior inside = si == null ? null : si.get(q);
        Location counter = inside != null ? inside.nearCounter(rnd, 1, 3) : open(w, q.x, q.z, 2, 16, true); // inside the shop, when it has an inside
        if (counter == null) {
            p.sendMessage(Component.text("There is no free place near " + q.name + " yet (the ground is still loading). Try again in a moment.", NamedTextColor.YELLOW));
            return true;
        }
        Shift sh = new Shift();
        sh.p = p;
        sh.shop = q;
        sh.counter = counter;
        sh.in = inside;
        sh.end = System.currentTimeMillis() + SHIFT_MS;
        sh.nextCust = System.currentTimeMillis() + 4000;
        sh.stove = w.spawn(counter.clone().add(0, 0, 0), BlockDisplay.class, e -> {
            e.setBlock(Material.SMOKER.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setTransformation(new Transformation(new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
        });
        sh.kitchen = w.spawn(counter.clone().add(0, 0, 0), Interaction.class, e -> {
            e.setInteractionWidth(1.3f);
            e.setInteractionHeight(1.6f);
            e.setResponsive(true);
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setCustomNameVisible(false);
        });
        Location ceoAt = inside != null ? inside.nearCounter(rnd, 1.5, 3.5) : open(w, counter.getX(), counter.getZ(), 1.5, 2.5, false);
        sh.ceo = w.spawn(ceoAt != null ? ceoAt : counter, Villager.class, v -> {
            v.setAI(false);
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(false);
            v.addScoreboardTag(TAG);
            v.setProfession(Villager.Profession.LIBRARIAN);
            v.customName(Component.text("CEO of " + q.name, NamedTextColor.GOLD));
            v.setCustomNameVisible(true);
        });
        shifts.put(p.getUniqueId(), sh);
        p.sendMessage(Component.text("The CEO of " + q.name + " hires you for a 4 minute shift! The kitchen is at the smoker block. Customers are coming...", NamedTextColor.GOLD));
        return true;
    }

    private void spawnCustomer(Shift s) {
        String[] m = menu(s.shop.kind);
        World w = s.counter.getWorld();
        Location spot = s.in != null ? s.in.nearCounter(rnd, 2, 5) : open(w, s.counter.getX(), s.counter.getZ(), 2.5, 4.5, false);
        Location from = s.in != null ? spot : open(w, s.counter.getX(), s.counter.getZ(), 10, 13, true); // in a shop, the customers come in at the door side
        if (from == null || spot == null) return;
        Cust c = new Cust();
        c.order = m[rnd.nextInt(m.length)];
        c.spot = spot;
        c.deadline = System.currentTimeMillis() + PATIENCE_MS;
        c.v = w.spawn(from, Villager.class, v -> {
            v.setSilent(true);
            v.setInvulnerable(true);
            v.setPersistent(false);
            v.addScoreboardTag(TAG);
            v.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
            v.setCustomNameVisible(true);
        });
        c.v.getPathfinder().moveTo(spot);
        s.custs.add(c);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Shift s : new ArrayList<>(shifts.values())) {
            Player p = s.p;
            if (!p.isOnline() || p.getWorld() != s.counter.getWorld() || p.getLocation().distance(s.counter) > 60) {
                finish(s, "You left your work place: the shift is over.");
                continue;
            }
            if (now >= s.end) {
                finish(s, null);
                continue;
            }
            if (now >= s.nextCust && s.custs.stream().filter(c -> c.state < 2).count() < 3) {
                spawnCustomer(s);
                s.nextCust = now + 12000 + rnd.nextInt(10000);
            }
            for (java.util.Iterator<Cust> it = s.custs.iterator(); it.hasNext(); ) {
                Cust c = it.next();
                if (!c.v.isValid()) {
                    it.remove();
                    continue;
                }
                if (c.state == 2) {
                    if (now > c.left) {
                        c.v.remove();
                        it.remove();
                    }
                    continue;
                }
                long secs = Math.max(0, (c.deadline - now) / 1000);
                if (now > c.deadline) {
                    s.missed++;
                    c.v.customName(Component.text("Too slow!", NamedTextColor.RED));
                    c.v.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, c.v.getLocation().add(0, 2, 0), 5);
                    c.state = 2;
                    c.left = now + 2500;
                    p.sendMessage(Component.text("A customer left angry (" + c.order + ").", NamedTextColor.RED));
                    continue;
                }
                NamedTextColor col = secs > 45 ? NamedTextColor.GREEN : secs > 20 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                c.v.customName(Component.text(c.state == 0 ? "Click me to order (" + secs + "s)" : c.order + " (" + secs + "s)", col));
                if (c.v.getLocation().distance(c.spot) > 1.8) c.v.getPathfinder().moveTo(c.spot);
            }
            p.sendActionBar(Component.text(String.format(Locale.ROOT, "Shift: %d:%02d left  |  served %d, missed %d  |  earned %d coins%s", (s.end - now) / 60000, ((s.end - now) / 1000) % 60,
                    s.served, s.missed, s.earned, s.carrying == null ? "" : "  |  carrying: " + s.carrying), NamedTextColor.WHITE));
        }
    }

    private void finish(Shift s, String reason) {
        shifts.remove(s.p.getUniqueId());
        long bonus = s.served * 15L;
        if (reason == null && s.served > 0) {
            eco.add(s.p.getUniqueId(), bonus);
            s.earned += bonus;
        }
        if (s.p.isOnline()) {
            if (reason != null) s.p.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
            s.p.sendMessage(Component.text(String.format(Locale.ROOT, "Shift report: %d customers served, %d missed, %d coins earned%s. You have %d coins.", s.served, s.missed, s.earned,
                    reason == null && s.served > 0 ? " (with a bonus of " + bonus + " from the CEO)" : "", eco.get(s.p.getUniqueId())), NamedTextColor.GOLD));
        }
        cleanup(s);
    }

    private void cleanup(Shift s) {
        for (Cust c : s.custs) if (c.v != null) c.v.remove();
        s.custs.clear();
        if (s.kitchen != null) s.kitchen.remove();
        if (s.stove != null) s.stove.remove();
        if (s.ceo != null) s.ceo.remove();
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (!ent.getScoreboardTags().contains(TAG)) return;
        e.setCancelled(true); // no trading with the staff and customers
        Shift s = shifts.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        Player p = s.p;
        long now = System.currentTimeMillis();
        if (ent == s.kitchen) {
            Cust next = null;
            for (Cust c : s.custs) if (c.state == 1 && (next == null || c.deadline < next.deadline)) next = c;
            if (next == null) {
                p.sendMessage(Component.text("No order to make. Click a waiting customer to take his order first.", NamedTextColor.YELLOW));
                return;
            }
            s.carrying = next.order;
            p.getWorld().spawnParticle(Particle.FLAME, s.counter.clone().add(0, 1.2, 0), 8, 0.2, 0.1, 0.2, 0.01);
            p.sendMessage(Component.text("You made: " + s.carrying + ". Bring it to the customer.", NamedTextColor.GREEN));
            return;
        }
        if (ent == s.ceo) {
            p.sendMessage(Component.text("CEO: Keep the customers happy! Quick service earns tips, and I pay a bonus of 15 coins for every customer at the end.", NamedTextColor.GOLD));
            return;
        }
        for (Cust c : s.custs) {
            if (c.v != ent || c.state == 2) continue;
            if (c.state == 0) {
                c.state = 1;
                p.sendMessage(Component.text("Order: " + c.order + ". Make it at the kitchen (the smoker).", NamedTextColor.GREEN));
                return;
            }
            if (c.order.equals(s.carrying)) {
                double f = Math.max(0, (c.deadline - now) / (double) PATIENCE_MS);
                int pay = 25 + (int) Math.round(25 * f);
                s.carrying = null;
                s.served++;
                s.earned += pay;
                eco.add(p.getUniqueId(), pay);
                if (shops != null) shops.visit(s.shop);
                c.state = 2;
                c.left = now + 2500;
                c.v.customName(Component.text("Thank you!", NamedTextColor.GREEN));
                c.v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.v.getLocation().add(0, 2, 0), 8, 0.3, 0.3, 0.3);
                p.sendMessage(Component.text("Served " + c.order + ": +" + pay + " coins" + (f > 0.5 ? " (with a tip!)" : "") + ".", NamedTextColor.GOLD));
            } else {
                p.sendMessage(Component.text(s.carrying == null ? "Make his order first at the kitchen (he wants " + c.order + ")." : "That is not his order: he wants " + c.order + ", you carry " + s.carrying + ".", NamedTextColor.YELLOW));
            }
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Shift s = shifts.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, null);
    }
}
