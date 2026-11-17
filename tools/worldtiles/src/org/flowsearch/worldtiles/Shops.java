package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players buy the real shops (OpenStreetMap): /shop shows the one next to you, /shop buy buys it, and it earns coins for the hours that it is
 * really open (its real opening hours). /shop collect pays the coins out, /shop upgrade makes it earn more, /shop list shows yours.
 */
public final class Shops implements CommandExecutor {
    private static final double REACH = 25;

    private static final class Owned {
        UUID owner;
        String ownerName, key, name, kind, oh;
        double lat, lon;
        int price, level;
        long last;
        double bonus; // coins from customers who came in
        int staff;    // workers that were hired: each one makes the shop earn a quarter more
    }

    /** somebody paid this shop (a room was rented): the owner, if there is one, gets it */
    void earn(Places.Poi q, double amount) {
        Owned o = owned.get(keyOf(q));
        if (o == null) {
            Houses h = Places.houses; // a shop in a house of a player
            if (h != null) h.earn(q, amount);
            return;
        }
        o.bonus += amount;
        save();
        Player p = plugin.getServer().getPlayer(o.owner);
        if (p != null) p.sendActionBar(Component.text(String.format(Locale.ROOT, "A customer paid %s: +%.0f coins (/shop collect)", o.name, amount), NamedTextColor.GOLD));
    }

    /** a customer (a villager) came into a shop: if somebody owns it, it earns something extra */
    void visit(Places.Poi q) {
        Owned o = owned.get(keyOf(q));
        if (o == null) {
            Houses h = Places.houses; // a shop in a house of a player
            if (h != null) h.visit(q);
            return;
        }
        double v = o.price / 40.0 * (1 + 0.5 * o.level);
        o.bonus += v;
        save();
        Player p = plugin.getServer().getPlayer(o.owner);
        if (p != null) p.sendActionBar(Component.text(String.format(Locale.ROOT, "A customer came into %s: +%.0f coins (/shop collect)", o.name, v), NamedTextColor.GOLD));
    }

    private final JavaPlugin plugin;
    private final Places places;
    private final Economy eco;
    private final File file;
    private final Map<String, Owned> owned = new ConcurrentHashMap<>();

    Shops(JavaPlugin plugin, Places places, Economy eco) {
        this.plugin = plugin;
        this.places = places;
        this.eco = eco;
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "shops.txt");
        try {
            if (file.exists()) {
                for (String line : Files.readAllLines(file.toPath())) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 12) continue;
                    Owned o = new Owned();
                    o.owner = UUID.fromString(p[0]);
                    o.ownerName = p[1];
                    o.key = p[2];
                    o.name = p[3];
                    o.kind = p[4];
                    o.oh = p[5].isEmpty() ? null : p[5];
                    o.lat = Double.parseDouble(p[6]);
                    o.lon = Double.parseDouble(p[7]);
                    o.price = Integer.parseInt(p[8]);
                    o.level = Integer.parseInt(p[9]);
                    o.last = Long.parseLong(p[10]);
                    try {
                        o.bonus = Double.parseDouble(p[11]);
                    } catch (NumberFormatException ignored) {
                    }
                    try {
                        o.staff = Integer.parseInt(p[12]);
                    } catch (Exception ignored) {
                    }
                    owned.put(o.key, o);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("shops.txt could not be read: " + e);
        }
    }

    private synchronized void save() {
        StringBuilder sb = new StringBuilder();
        for (Owned o : owned.values()) {
            sb.append(o.owner).append('\t').append(o.ownerName).append('\t').append(o.key).append('\t').append(clean(o.name)).append('\t').append(clean(o.kind)).append('\t')
                    .append(o.oh == null ? "" : clean(o.oh)).append('\t').append(o.lat).append('\t').append(o.lon).append('\t').append(o.price).append('\t').append(o.level)
                    .append('\t').append(o.last).append('\t').append(o.bonus).append('\t').append(o.staff).append('\n');
        }
        try {
            Files.writeString(file.toPath(), sb.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("shops.txt could not be written: " + e);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    static String keyOf(Places.Poi q) {
        return clean(q.realName != null ? q.realName : q.name) + "|" + Math.round(q.lat * 100000) + "|" + Math.round(q.lon * 100000); // the key stays what it was, so what was bought stays bought
    }

    /** the name of the owner of this shop, or null */
    String ownerOf(Places.Poi q) {
        Owned o = owned.get(keyOf(q));
        if (o != null) return o.ownerName;
        Houses h = Places.houses;
        return h == null ? null : h.ownerOf(q);
    }

    static int priceOf(String kind) {
        if (kind == null) return 500;
        return switch (kind) {
            case "hotel" -> 3000;
            case "hostel" -> 1200;
            case "guest_house", "motel", "apartment" -> 1500;
            case "bank" -> 2000;
            case "cinema", "theatre" -> 1500;
            case "pharmacy", "doctors", "dentist" -> 800;
            case "restaurant", "bar", "pub" -> 600;
            case "cafe", "fast_food", "ice_cream", "bakery" -> 400;
            case "marketplace", "supermarket", "mall", "department_store" -> 1200;
            default -> 500;
        };
    }

    /** coins per hour that it is open */
    private static double rate(Owned o) {
        return o.price / 3.0 * (1 + 0.5 * o.level) * (1 + 0.25 * o.staff);
    }

    private double pending(Owned o) {
        long now = System.currentTimeMillis();
        long from = Math.max(o.last, now - 48L * 3600 * 1000); // not more than two days are saved up
        int open = 0;
        for (long t = from; t < now; t += 600000) if (Places.openAt(o.oh, o.lon, t)) open++;
        return open / 6.0 * rate(o) + o.bonus;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        UUID me = p.getUniqueId();
        if (a.equals("list")) {
            int n = 0;
            double total = 0;
            for (Owned o : owned.values()) {
                if (!o.owner.equals(me)) continue;
                double pe = pending(o);
                total += pe;
                n++;
                p.sendMessage(Component.text(String.format(Locale.ROOT, " %s (%s) level %d, %.0f coins waiting, at /tpll %.5f %.5f", o.name, o.kind.replace('_', ' '), o.level, pe, o.lat, o.lon), NamedTextColor.WHITE));
            }
            p.sendMessage(Component.text(n == 0 ? "You own no shop yet. Stand next to one and use /shop buy." : String.format(Locale.ROOT, "%d shops, %.0f coins waiting: /shop collect", n, total), NamedTextColor.GREEN));
            return true;
        }
        if (a.equals("collect")) {
            long sum = 0;
            int n = 0;
            for (Owned o : owned.values()) {
                if (!o.owner.equals(me)) continue;
                sum += Math.round(pending(o));
                o.last = System.currentTimeMillis();
                o.bonus = 0;
                n++;
            }
            if (n == 0) {
                p.sendMessage(Component.text("You own no shop yet.", NamedTextColor.YELLOW));
                return true;
            }
            eco.add(me, sum);
            save();
            p.sendMessage(Component.text("Collected " + sum + " coins from " + n + " shops. You have " + eco.get(me) + " coins.", NamedTextColor.GOLD));
            return true;
        }
        if (p.getWorld().getGenerator() == null || places == null) return true;
        Places.Poi q = places.nearest(p.getLocation().getX(), p.getLocation().getZ(), REACH);
        if (q == null) {
            p.sendMessage(Component.text("No shop within " + (int) REACH + " blocks. Walk up to one (/near shows them). Other commands: /shop list, /shop collect.", NamedTextColor.YELLOW));
            return true;
        }
        String key = keyOf(q);
        Houses hs = Places.houses;
        String houseOwner = hs == null ? null : hs.ownerOf(q);
        if (houseOwner != null) {
            p.sendMessage(Component.text(q.name + " is a shop in a house of " + houseOwner + " (/house).", NamedTextColor.YELLOW));
            return true;
        }
        Owned o = owned.get(key);
        int price = priceOf(q.kind);
        if (a.equals("hire") || a.equals("fire")) {
            if (o == null || !o.owner.equals(me)) {
                p.sendMessage(Component.text("This shop is not yours.", NamedTextColor.RED));
                return true;
            }
            if (a.equals("fire")) {
                if (o.staff > 0) o.staff--;
                save();
                p.sendMessage(Component.text("A worker left " + o.name + ". Staff: " + o.staff + ".", NamedTextColor.YELLOW));
                return true;
            }
            int cost = 200 * (o.staff + 1);
            if (o.staff >= 3) {
                p.sendMessage(Component.text(o.name + " has the most staff it can have (3).", NamedTextColor.YELLOW));
            } else if (eco.get(me) < cost) {
                p.sendMessage(Component.text("Hiring the next worker costs " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
            } else {
                eco.add(me, Math.round(pending(o)));
                o.last = System.currentTimeMillis();
                o.bonus = 0;
                eco.add(me, -cost);
                o.staff++;
                save();
                p.sendMessage(Component.text("You hired a worker for " + o.name + " (" + cost + " coins). Staff: " + o.staff + ", the shop earns " + (25 * o.staff) + "% more.", NamedTextColor.GOLD));
            }
            return true;
        }
        if (a.equals("buy")) {
            if (o != null) {
                p.sendMessage(Component.text(q.name + " already belongs to " + o.ownerName + ".", NamedTextColor.RED));
                return true;
            }
            if (eco.get(me) < price) {
                p.sendMessage(Component.text(q.name + " costs " + price + " coins. You have " + eco.get(me) + ". (/car and /job earn coins)", NamedTextColor.RED));
                return true;
            }
            eco.add(me, -price);
            Owned n = new Owned();
            n.owner = me;
            n.ownerName = p.getName();
            n.key = key;
            n.name = q.name;
            n.kind = q.kind == null ? "shop" : q.kind;
            n.oh = q.oh;
            n.lat = q.lat;
            n.lon = q.lon;
            n.price = price;
            n.last = System.currentTimeMillis();
            owned.put(key, n);
            save();
            p.sendMessage(Component.text("You bought " + q.name + " for " + price + " coins! It earns about " + Math.round(rate(n)) + " coins for every hour it is really open. /shop collect pays them out.", NamedTextColor.GOLD));
            return true;
        }
        if (a.equals("upgrade")) {
            if (o == null || !o.owner.equals(me)) {
                p.sendMessage(Component.text("This shop is not yours.", NamedTextColor.RED));
                return true;
            }
            if (o.level >= 3) {
                p.sendMessage(Component.text("It is at the highest level.", NamedTextColor.YELLOW));
                return true;
            }
            int cost = o.price * (o.level + 1) / 2;
            if (eco.get(me) < cost) {
                p.sendMessage(Component.text("Upgrading costs " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                return true;
            }
            eco.add(me, Math.round(pending(o))); // what it earned until now is paid out first
            o.last = System.currentTimeMillis();
            o.bonus = 0;
            eco.add(me, -cost);
            o.level++;
            save();
            p.sendMessage(Component.text(o.name + " is now level " + o.level + ": " + Math.round(rate(o)) + " coins per open hour.", NamedTextColor.GOLD));
            return true;
        }
        // /shop: what is this one
        String[] st = Places.hoursOf(q);
        String hours = st == null ? "hours unknown (counted as 10:00-18:00)" : st[1];
        if (o == null) {
            p.sendMessage(Component.text(q.name + " (" + (q.kind == null ? "shop" : q.kind.replace('_', ' ')) + "), " + hours + ". For sale: " + price + " coins. /shop buy", NamedTextColor.GREEN));
        } else if (o.owner.equals(me)) {
            p.sendMessage(Component.text(String.format(Locale.ROOT, "%s is yours (level %d), %s. %.0f coins are waiting: /shop collect. /shop upgrade costs %d.", o.name, o.level, hours, pending(o), o.price * (o.level + 1) / 2), NamedTextColor.GREEN));
        } else {
            p.sendMessage(Component.text(o.name + " belongs to " + o.ownerName + ".", NamedTextColor.YELLOW));
        }
        return true;
    }
}
