package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /buy: in a real shop or restaurant (open, next to you) you pay coins for what it sells and get the food. The shop's owner is paid. */
public final class Menu implements CommandExecutor {
    private static final class Dish {
        final int price;
        final Material item;

        Dish(int price, Material item) {
            this.price = price;
            this.item = item;
        }
    }

    private final TileGen gen;
    private final Places places;
    private final Economy eco;
    private final Shops shops;

    Menu(TileGen gen, Places places, Economy eco, Shops shops) {
        this.gen = gen;
        this.places = places;
        this.eco = eco;
        this.shops = shops;
    }

    private static Map<String, Dish> menu(String kind) {
        Map<String, Dish> m = new LinkedHashMap<>();
        switch (kind == null ? "" : kind) {
            case "cafe" -> {
                m.put("Coffee", new Dish(6, Material.HONEY_BOTTLE));
                m.put("Tea", new Dish(5, Material.HONEY_BOTTLE));
                m.put("Cake", new Dish(10, Material.PUMPKIN_PIE));
                m.put("Croissant", new Dish(5, Material.BREAD));
            }
            case "restaurant" -> {
                m.put("Pizza", new Dish(14, Material.PUMPKIN_PIE));
                m.put("Pasta", new Dish(12, Material.MUSHROOM_STEW));
                m.put("Salad", new Dish(9, Material.BEETROOT_SOUP));
                m.put("Soup", new Dish(8, Material.RABBIT_STEW));
                m.put("Steak", new Dish(20, Material.COOKED_BEEF));
            }
            case "fast_food" -> {
                m.put("Burger", new Dish(8, Material.COOKED_PORKCHOP));
                m.put("Fries", new Dish(5, Material.BAKED_POTATO));
                m.put("Kebab", new Dish(9, Material.COOKED_MUTTON));
                m.put("Wrap", new Dish(7, Material.COOKED_CHICKEN));
            }
            case "bar", "pub" -> {
                m.put("Beer", new Dish(5, Material.HONEY_BOTTLE));
                m.put("Cola", new Dish(4, Material.HONEY_BOTTLE));
                m.put("Snacks", new Dish(4, Material.BAKED_POTATO));
            }
            case "ice_cream" -> {
                m.put("Ice cream", new Dish(4, Material.MELON_SLICE));
                m.put("Waffle", new Dish(6, Material.COOKIE));
            }
            case "bakery" -> {
                m.put("Bread", new Dish(3, Material.BREAD));
                m.put("Pretzel", new Dish(4, Material.COOKIE));
                m.put("Cake slice", new Dish(6, Material.PUMPKIN_PIE));
            }
            case "supermarket", "mall", "department_store", "convenience", "greengrocer", "kiosk" -> {
                m.put("Apple", new Dish(2, Material.APPLE));
                m.put("Bread", new Dish(3, Material.BREAD));
                m.put("Milk", new Dish(3, Material.MILK_BUCKET));
                m.put("Chips", new Dish(3, Material.BAKED_POTATO));
                m.put("Chocolate", new Dish(4, Material.COOKIE));
            }
            case "pharmacy" -> m.put("Medicine", new Dish(15, Material.GOLDEN_APPLE));
            default -> {
            }
        }
        return m;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("Shops are on Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        Places.Poi q = places.nearest(p.getLocation().getX(), p.getLocation().getZ(), 14);
        if (q == null) {
            p.sendMessage(Component.text("No shop within 14 blocks. Go into one (/near shows them).", NamedTextColor.YELLOW));
            return true;
        }
        Map<String, Dish> menu = menu(q.kind);
        if (menu.isEmpty()) {
            p.sendMessage(Component.text(q.name + " has nothing for sale in the game yet.", NamedTextColor.YELLOW));
            return true;
        }
        if (!Places.openAt(q.oh, q.lon, System.currentTimeMillis())) {
            p.sendMessage(Component.text(q.name + " is closed now.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(Component.text(q.name + " - menu (/buy <name> [how many]):", NamedTextColor.GREEN));
            for (Map.Entry<String, Dish> e : menu.entrySet()) p.sendMessage(Component.text("  " + e.getKey() + " - " + e.getValue().price + " coins", NamedTextColor.WHITE));
            return true;
        }
        int n = 1;
        String name = String.join(" ", args);
        try {
            String last = args[args.length - 1];
            n = Math.max(1, Math.min(8, Integer.parseInt(last)));
            name = String.join(" ", java.util.Arrays.copyOf(args, args.length - 1));
        } catch (NumberFormatException ignored) {
        }
        Map.Entry<String, Dish> found = null;
        for (Map.Entry<String, Dish> e : menu.entrySet()) if (e.getKey().equalsIgnoreCase(name.trim())) found = e;
        if (found == null) {
            p.sendMessage(Component.text("They do not have \"" + name + "\". /buy shows the menu.", NamedTextColor.YELLOW));
            return true;
        }
        long cost = (long) found.getValue().price * n;
        if (eco.get(p.getUniqueId()) < cost) {
            p.sendMessage(Component.text(n + " x " + found.getKey() + " cost " + cost + " coins. You have " + eco.get(p.getUniqueId()) + ".", NamedTextColor.RED));
            return true;
        }
        eco.add(p.getUniqueId(), -cost);
        if (shops != null) shops.earn(q, cost);
        ItemStack it = new ItemStack(found.getValue().item, n);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(found.getKey(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("from " + q.name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        p.getInventory().addItem(it).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
        p.sendMessage(Component.text("You bought " + n + " x " + found.getKey() + " for " + cost + " coins. Enjoy! (eat it by holding right-click)", NamedTextColor.GREEN));
        return true;
    }
}
