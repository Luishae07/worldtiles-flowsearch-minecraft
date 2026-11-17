package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Rent a room in a real hotel (OpenStreetMap tourism=hotel, hostel, guest_house, motel, apartment): stand next to one and /hotel rent 1|6|24.
 * You get a furnished private room (in its own night world, so the bed works) with a chest that keeps your things. If a player owns the hotel,
 * the rent goes to them. /hotel room goes in, /hotel leave goes back where you were.
 */
public final class Hotel implements CommandExecutor, Listener {
    private static final String WORLD = "hotel";
    private static final String EXIT_TAG = "wt_hotel_exit";

    private static final class Rental {
        String name, key;
        long expires;
    }

    /** an empty world */
    private static final class Void extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 4.5, 64, 3.5);
        }
    }

    private final JavaPlugin plugin;
    private final Places places;
    private final Economy eco;
    private final Shops shops;
    private final File file;
    private final Map<UUID, Integer> slots = new HashMap<>();
    private final Map<UUID, Rental> rentals = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();

    Hotel(JavaPlugin plugin, Places places, Economy eco, Shops shops) {
        this.plugin = plugin;
        this.places = places;
        this.eco = eco;
        this.shops = shops;
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "hotel.txt");
        try {
            if (file.exists()) for (String line : Files.readAllLines(file.toPath())) {
                String[] p = line.split("\t", -1);
                if (p[0].equals("slot") && p.length >= 3) slots.put(UUID.fromString(p[1]), Integer.parseInt(p[2]));
                if (p[0].equals("rent") && p.length >= 5) {
                    Rental r = new Rental();
                    r.name = p[2];
                    r.key = p[3];
                    r.expires = Long.parseLong(p[4]);
                    rentals.put(UUID.fromString(p[1]), r);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("hotel.txt could not be read: " + e);
        }
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 100L);
    }

    private synchronized void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Integer> e : slots.entrySet()) sb.append("slot\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        for (Map.Entry<UUID, Rental> e : rentals.entrySet()) {
            sb.append("rent\t").append(e.getKey()).append('\t').append(e.getValue().name.replace('\t', ' ')).append('\t').append(e.getValue().key).append('\t').append(e.getValue().expires).append('\n');
        }
        try {
            Files.writeString(file.toPath(), sb.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("hotel.txt could not be written: " + e);
        }
    }

    private World world() {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) return w;
        WorldCreator wc = new WorldCreator(WORLD);
        wc.generator(new Void());
        wc.generateStructures(false);
        w = wc.createWorld();
        if (w != null) {
            w.setTime(18000); // always night (it is set again every 5 seconds), so that the bed works and the sky has stars
            w.setStorm(false);
            w.setSpawnLocation(4, 64, 3);
        }
        return w;
    }

    private int slotOf(UUID id) {
        Integer s = slots.get(id);
        if (s == null) {
            s = slots.size();
            slots.put(id, s);
            save();
        }
        return s;
    }

    private static int stars(Places.Poi q) {
        if (q.stars != null) return (int) Math.max(1, Math.min(5, Math.round(Places.parseStars(q.stars))));
        return switch (q.kind == null ? "" : q.kind) {
            case "hostel" -> 1;
            case "guest_house", "motel", "apartment" -> 2;
            default -> 3;
        };
    }

    /** coins per hour */
    private static int price(Places.Poi q) {
        return switch (q.kind == null ? "" : q.kind) {
            case "hostel" -> 30;
            case "guest_house", "motel", "apartment" -> 50;
            default -> 40 * stars(q);
        };
    }

    // ---- the room ----

    private void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void fill(World w, int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) set(w, x, y, z, m);
    }

    /** the room of a slot: 9 x 4 x 7 blocks, interior x0..x0+8, z 0..6, y 64..67 */
    private Location build(World w, int slot, int stars) {
        int x0 = slot * 16;
        Material wall = stars >= 4 ? Material.WHITE_CONCRETE : Material.WHITE_TERRACOTTA;
        fill(w, x0 - 1, 63, -1, x0 + 9, 68, 7, wall); // the box
        fill(w, x0, 64, 0, x0 + 8, 67, 6, Material.AIR);
        fill(w, x0, 63, 0, x0 + 8, 63, 6, stars >= 4 ? Material.POLISHED_DEEPSLATE : Material.DARK_OAK_PLANKS); // floor
        fill(w, x0 + 2, 64, 7, x0 + 6, 67, 7, Material.GLASS); // the window to the night sky
        fill(w, x0 + 3, 64, 2, x0 + 6, 64, 4, stars >= 4 ? Material.RED_CARPET : Material.LIGHT_BLUE_CARPET);
        set(w, x0 + 4, 68, 3, Material.SEA_LANTERN);
        set(w, x0 + 2, 68, 3, Material.SEA_LANTERN);
        set(w, x0 + 6, 68, 3, Material.SEA_LANTERN);
        // the bed, the night stand with a lantern
        Bed foot = (Bed) Material.RED_BED.createBlockData();
        foot.setFacing(BlockFace.EAST);
        foot.setPart(Bed.Part.FOOT);
        w.getBlockAt(x0 + 1, 64, 1).setBlockData(foot, false);
        Bed head = (Bed) Material.RED_BED.createBlockData();
        head.setFacing(BlockFace.EAST);
        head.setPart(Bed.Part.HEAD);
        w.getBlockAt(x0 + 2, 64, 1).setBlockData(head, false);
        set(w, x0 + 3, 64, 1, Material.BARREL);
        set(w, x0 + 3, 65, 1, Material.LANTERN);
        // the desk, a laptop (a lectern), a chair, a plant
        set(w, x0 + 6, 64, 0, Material.SPRUCE_PLANKS);
        set(w, x0 + 7, 64, 0, Material.SPRUCE_PLANKS);
        set(w, x0 + 6, 65, 0, Material.LECTERN);
        set(w, x0 + 7, 65, 0, Material.POTTED_ALLIUM);
        Block chair = w.getBlockAt(x0 + 6, 64, 1);
        chair.setType(Material.SPRUCE_STAIRS, false);
        org.bukkit.block.data.type.Stairs st = (org.bukkit.block.data.type.Stairs) chair.getBlockData();
        st.setFacing(BlockFace.SOUTH);
        chair.setBlockData(st, false);
        // the television and the cabinet under it
        fill(w, x0 + 8, 65, 2, x0 + 8, 66, 4, Material.BLACK_CONCRETE);
        fill(w, x0 + 8, 64, 2, x0 + 8, 64, 4, Material.SMOOTH_STONE_SLAB);
        // the chest that keeps your things (made once)
        Block chest = w.getBlockAt(x0 + 8, 64, 5);
        if (chest.getType() != Material.CHEST) {
            chest.setType(Material.CHEST, false);
            org.bukkit.block.data.type.Chest cd = (org.bukkit.block.data.type.Chest) chest.getBlockData();
            cd.setFacing(BlockFace.WEST);
            chest.setBlockData(cd, false);
        }
        // the door
        fill(w, x0 + 4, 64, -1, x0 + 4, 65, -1, Material.AIR);
        set(w, x0 + 4, 66, -1, Material.OAK_SIGN);
        // the exit: click the door
        boolean has = false;
        for (Entity e : w.getNearbyEntities(new Location(w, x0 + 4.5, 65, -0.5), 3, 3, 3)) if (e.getScoreboardTags().contains(EXIT_TAG)) has = true;
        if (!has) {
            w.spawn(new Location(w, x0 + 4.5, 64, -0.5), Interaction.class, e -> {
                e.setInteractionWidth(1.6f);
                e.setInteractionHeight(2.2f);
                e.setResponsive(true);
                e.setPersistent(false);
                e.addScoreboardTag(EXIT_TAG);
            });
            w.spawn(new Location(w, x0 + 4.5, 66.2, 0.2), TextDisplay.class, e -> {
                e.text(Component.text("EXIT - click the door (or /hotel leave)", NamedTextColor.YELLOW));
                e.setPersistent(false);
                e.addScoreboardTag(EXIT_TAG + "_label");
            });
        }
        return new Location(w, x0 + 4.5, 64, 3.5, 0, 0);
    }

    // ---- the commands ----

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        UUID me = p.getUniqueId();
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        Rental r = rentals.get(me);
        if (r != null && System.currentTimeMillis() >= r.expires) {
            rentals.remove(me);
            save();
            r = null;
        }
        boolean inRoom = p.getWorld().getName().equals(WORLD);
        if (a.equals("leave")) {
            if (!inRoom) {
                p.sendMessage(Component.text("You are not in your room.", NamedTextColor.YELLOW));
                return true;
            }
            leave(p);
            return true;
        }
        if (a.equals("room")) {
            if (r == null) {
                p.sendMessage(Component.text("You have no room. Stand next to a hotel (/near shows them) and /hotel rent 1|6|24.", NamedTextColor.YELLOW));
                return true;
            }
            enter(p, r);
            return true;
        }
        if (a.equals("checkout")) {
            if (r == null) {
                p.sendMessage(Component.text("You have no room.", NamedTextColor.YELLOW));
                return true;
            }
            rentals.remove(me);
            save();
            if (inRoom) leave(p);
            p.sendMessage(Component.text("You checked out of " + r.name + ".", NamedTextColor.GREEN));
            return true;
        }
        Places.Poi q = inRoom ? null : places.nearestHotel(p.getLocation().getX(), p.getLocation().getZ(), 40);
        if (a.equals("rent")) {
            int hours = 1;
            try {
                if (args.length > 1) hours = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
            }
            if (hours != 1 && hours != 6 && hours != 24) {
                p.sendMessage(Component.text("/hotel rent 1, 6 or 24 (hours).", NamedTextColor.YELLOW));
                return true;
            }
            if (q == null) {
                p.sendMessage(Component.text("No hotel within 40 blocks. Hotels have a purple label (/near).", NamedTextColor.YELLOW));
                return true;
            }
            String key = Shops.keyOf(q);
            if (r != null && !r.key.equals(key)) {
                p.sendMessage(Component.text("You already have a room in " + r.name + ". /hotel checkout first.", NamedTextColor.YELLOW));
                return true;
            }
            long cost = (long) price(q) * hours;
            if (eco.get(me) < cost) {
                p.sendMessage(Component.text(hours + " hours in " + q.name + " cost " + cost + " coins. You have " + eco.get(me) + ".", NamedTextColor.RED));
                return true;
            }
            eco.add(me, -cost);
            if (shops != null) shops.earn(q, cost);
            if (r == null) {
                r = new Rental();
                r.name = q.name;
                r.key = key;
                r.expires = System.currentTimeMillis();
                rentals.put(me, r);
            }
            r.expires = Math.max(r.expires, System.currentTimeMillis()) + hours * 3600_000L;
            save();
            p.sendMessage(Component.text("Room rented in " + q.name + " for " + hours + " hours (" + cost + " coins). /hotel room takes you there.", NamedTextColor.GOLD));
            return true;
        }
        if (r != null) {
            long min = Math.max(0, (r.expires - System.currentTimeMillis()) / 60000);
            p.sendMessage(Component.text("Your room in " + r.name + ": " + min / 60 + " h " + min % 60 + " min left. /hotel room, /hotel leave, /hotel checkout. More time: /hotel rent <hours> next to the hotel.", NamedTextColor.GREEN));
        }
        if (q != null) {
            p.sendMessage(Component.text(q.name + " (" + stars(q) + " stars): " + price(q) + " coins per hour. /hotel rent 1 | 6 | 24", NamedTextColor.LIGHT_PURPLE));
        } else if (r == null) {
            p.sendMessage(Component.text("No hotel within 40 blocks. Hotels have a purple label (/near). Then: /hotel rent 1|6|24.", NamedTextColor.YELLOW));
        }
        return true;
    }

    private void enter(Player p, Rental r) {
        World w = world();
        if (w == null) {
            p.sendMessage(Component.text("The hotel could not be opened.", NamedTextColor.RED));
            return;
        }
        if (!p.getWorld().equals(w)) back.put(p.getUniqueId(), p.getLocation());
        int st = 3;
        Location room = build(w, slotOf(p.getUniqueId()), st);
        p.teleportAsync(room).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            p.setGameMode(GameMode.ADVENTURE);
            p.sendMessage(Component.text("Your room in " + r.name + ". Sleep in the bed to set your respawn here, the chest keeps your things. Leave: click the door.", NamedTextColor.GREEN));
        }));
    }

    private void leave(Player p) {
        Location l = back.remove(p.getUniqueId());
        if (l == null || l.getWorld() == null) l = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleportAsync(l);
        p.sendMessage(Component.text("Back outside.", NamedTextColor.GREEN));
    }

    private void tick() {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) return;
        if (!w.getPlayers().isEmpty()) w.setTime(18000);
        long now = System.currentTimeMillis();
        for (Player p : w.getPlayers()) {
            Rental r = rentals.get(p.getUniqueId());
            if (r == null || now >= r.expires) {
                rentals.remove(p.getUniqueId());
                save();
                p.sendMessage(Component.text("Your time in the room is over. Checkout!", NamedTextColor.YELLOW));
                leave(p);
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Interaction i && i.getScoreboardTags().contains(EXIT_TAG) && e.getPlayer().getWorld().getName().equals(WORLD)) {
            e.setCancelled(true);
            leave(e.getPlayer());
        }
    }
}
