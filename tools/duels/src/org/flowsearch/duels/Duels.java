package org.flowsearch.duels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Berlin Duels: ranked 1v1 fights in the streets of Berlin (the world of the tile service, one block per metre).
 * A lobby at the Brandenburg Gate, a queue per kit, an arena at a real place for every fight, a rating (Elo) and a leaderboard that is also written
 * as a file for the website. The game of the world (jobs, cars, ...) is not on this server: WorldTiles runs here in lite mode.
 */
public final class Duels extends JavaPlugin implements Listener, CommandExecutor {
    static final double REF_LON = 12.4534, REF_LAT = 41.9029, M_LAT = 110540.0, M_LON = 111320 * Math.cos(Math.toRadians(REF_LAT));

    enum Kit {
        SWORD("Sword", "Diamond sword, shield, iron armour"), AXE("Axe", "Diamond axe (breaks shields), shield, diamond helmet and iron armour"), BOW("Bow", "Bow and arrows, stone sword, leather armour");
        final String label, text;

        Kit(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }

    record Arena(String name, double lat, double lon) {
        int x() {
            return (int) Math.floor((lon - REF_LON) * M_LON);
        }

        int z() {
            return (int) Math.floor((REF_LAT - lat) * M_LAT);
        }
    }

    static final Arena LOBBY = new Arena("Pariser Platz", 52.5160, 13.3815);
    static final List<Arena> ARENAS = List.of(new Arena("Brandenburg Gate", 52.5163, 13.3777), new Arena("Alexanderplatz", 52.5219, 13.4132),
            new Arena("Tempelhofer Feld", 52.4731, 13.4018), new Arena("Museum Island", 52.5169, 13.4019), new Arena("Siegessaeule", 52.5145, 13.3501));
    static final int RADIUS = 38, HALF_GAP = 12, COUNTDOWN = 5, LIMIT = 180;

    static final class Stat {
        String name;
        int elo = 1000, wins, losses, streak;
    }

    final class Match {
        final Player a, b;
        final Kit kit;
        final Arena arena;
        boolean live, over;
        int secs = COUNTDOWN, left = LIMIT;
        Location sa, sb;
        BukkitTask task;

        Match(Player a, Player b, Kit kit, Arena arena) {
            this.a = a;
            this.b = b;
            this.kit = kit;
            this.arena = arena;
        }

        Player other(Player p) {
            return p.getUniqueId().equals(a.getUniqueId()) ? b : a;
        }
    }

    final Map<UUID, Stat> stats = new ConcurrentHashMap<>();
    final Map<UUID, Match> inMatch = new HashMap<>();
    final Set<Arena> busy = new HashSet<>();
    final Map<Kit, LinkedList<UUID>> queues = new EnumMap<>(Kit.class);
    final Map<UUID, Kit> queued = new HashMap<>();
    final Set<UUID> frozen = new HashSet<>();
    final List<Match> running = new ArrayList<>();
    NamespacedKey actionKey;
    World world;
    volatile Location lobbyLoc;
    Path statsFile, exportFile;

    // ---- start and stop ----

    @Override
    public void onEnable() {
        actionKey = new NamespacedKey(this, "action");
        for (Kit k : Kit.values()) queues.put(k, new LinkedList<>());
        world = Bukkit.getWorlds().get(0);
        statsFile = getDataFolder().toPath().resolve("stats.tsv");
        exportFile = Path.of(System.getProperty("duels.export", "/srv/uploads/flowsites/minecraft/duels/stats.json"));
        load();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String c : new String[]{"queue", "leave", "stats", "top", "spawn", "kits"}) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Kit k : Kit.values()) tryMatch(k);
        }, 40, 20);
        Bukkit.getScheduler().runTaskTimer(this, () -> { // always day and clear, in the lobby and in the arenas
            world.setTime(6000);
            world.setStorm(false);
        }, 20, 1200);
        Bukkit.getScheduler().runTaskTimer(this, this::export, 100, 200);
        place(LOBBY.x(), LOBBY.z(), l -> lobbyLoc = l);
        getLogger().info("Berlin Duels: " + ARENAS.size() + " arenas, " + stats.size() + " players with a rating");
    }

    @Override
    public void onDisable() {
        for (Match m : new ArrayList<>(running)) finish(m, null, "The server is restarting.");
        save();
        export();
    }

    // ---- places ----

    /** the surface at a place: its chunk is made first (the tile service makes the square), then the callback runs on the main thread */
    void place(int x, int z, Consumer<Location> then) {
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(c -> Bukkit.getScheduler().runTask(this, () ->
                then.accept(new Location(world, x + 0.5, world.getHighestBlockYAt(x, z) + 1, z + 0.5))));
    }

    void toLobby(Player p) {
        reset(p, GameMode.ADVENTURE);
        PlayerInventory in = p.getInventory();
        in.setItem(0, item(Material.DIAMOND_SWORD, "Queue: Sword", "queue:sword"));
        in.setItem(1, item(Material.DIAMOND_AXE, "Queue: Axe", "queue:axe"));
        in.setItem(2, item(Material.BOW, "Queue: Bow", "queue:bow"));
        in.setItem(4, item(Material.BOOK, "Your stats", "stats"));
        in.setItem(8, item(Material.BARRIER, "Leave the queue", "leave"));
        p.getInventory().setHeldItemSlot(0);
        Location l = lobbyLoc;
        if (l != null) p.teleportAsync(l);
        else place(LOBBY.x(), LOBBY.z(), loc -> {
            lobbyLoc = loc;
            if (p.isOnline()) p.teleportAsync(loc);
        });
    }

    ItemStack item(Material m, String name, String action) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GREEN));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        });
        return s;
    }

    void reset(Player p, GameMode gm) {
        p.getInventory().clear();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(gm);
        var maxHealth = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setExp(0);
        p.setLevel(0);
    }

    void giveKit(Player p, Kit k) {
        PlayerInventory in = p.getInventory();
        switch (k) {
            case SWORD -> {
                in.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                in.setItemInOffHand(new ItemStack(Material.SHIELD));
                armour(in, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                in.setItem(1, new ItemStack(Material.GOLDEN_APPLE, 3));
            }
            case AXE -> {
                in.setItem(0, new ItemStack(Material.DIAMOND_AXE));
                in.setItemInOffHand(new ItemStack(Material.SHIELD));
                armour(in, Material.DIAMOND_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
                in.setItem(1, new ItemStack(Material.GOLDEN_APPLE, 3));
            }
            case BOW -> {
                in.setItem(0, new ItemStack(Material.BOW));
                in.setItem(1, new ItemStack(Material.STONE_SWORD));
                in.setItem(2, new ItemStack(Material.GOLDEN_APPLE, 2));
                in.setItem(9, new ItemStack(Material.ARROW, 32));
                armour(in, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
            }
        }
        in.setHeldItemSlot(0);
    }

    void armour(PlayerInventory in, Material h, Material c, Material l, Material b) {
        in.setHelmet(new ItemStack(h));
        in.setChestplate(new ItemStack(c));
        in.setLeggings(new ItemStack(l));
        in.setBoots(new ItemStack(b));
    }

    // ---- the queue and the fights ----

    void join(Player p, Kit k) {
        if (inMatch.containsKey(p.getUniqueId())) {
            say(p, "You are in a duel.", NamedTextColor.RED);
            return;
        }
        Kit old = queued.remove(p.getUniqueId());
        if (old != null) queues.get(old).remove(p.getUniqueId());
        queued.put(p.getUniqueId(), k);
        queues.get(k).add(p.getUniqueId());
        say(p, "In the queue for " + k.label + " (" + queues.get(k).size() + " waiting). /leave to go out.", NamedTextColor.GREEN);
        tryMatch(k);
    }

    void leaveQueue(Player p) {
        Kit k = queued.remove(p.getUniqueId());
        if (k != null) {
            queues.get(k).remove(p.getUniqueId());
            say(p, "You left the queue.", NamedTextColor.YELLOW);
        } else {
            say(p, "You are not in a queue.", NamedTextColor.GRAY);
        }
    }

    Arena freeArena() {
        for (Arena a : ARENAS) if (!busy.contains(a)) return a;
        return null;
    }

    /** two people in the queue of a kit and a free arena: the first one in the queue fights the one with the nearest rating */
    void tryMatch(Kit k) {
        LinkedList<UUID> q = queues.get(k);
        while (q.size() >= 2) {
            Arena arena = freeArena();
            if (arena == null) return;
            Player p1 = Bukkit.getPlayer(q.peekFirst());
            if (p1 == null || !p1.isOnline()) {
                queued.remove(q.pollFirst());
                continue;
            }
            Player best = null;
            int bd = Integer.MAX_VALUE;
            for (UUID id : new ArrayList<>(q)) {
                if (id.equals(p1.getUniqueId())) continue;
                Player o = Bukkit.getPlayer(id);
                if (o == null || !o.isOnline()) {
                    q.remove(id);
                    queued.remove(id);
                    continue;
                }
                int d = Math.abs(stat(o).elo - stat(p1).elo);
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
            if (best == null) return;
            q.remove(p1.getUniqueId());
            q.remove(best.getUniqueId());
            queued.remove(p1.getUniqueId());
            queued.remove(best.getUniqueId());
            start(p1, best, k, arena);
        }
    }

    void start(Player a, Player b, Kit k, Arena arena) {
        Match m = new Match(a, b, k, arena);
        busy.add(arena);
        running.add(m);
        inMatch.put(a.getUniqueId(), m);
        inMatch.put(b.getUniqueId(), m);
        for (Player p : new Player[]{a, b}) {
            reset(p, GameMode.SURVIVAL);
            frozen.add(p.getUniqueId());
            say(p, k.label + " duel against " + m.other(p).getName() + " (" + stat(m.other(p)).elo + ") at " + arena.name() + ".", NamedTextColor.AQUA);
        }
        CompletableFuture<Void> fa = new CompletableFuture<>(), fb = new CompletableFuture<>();
        int cx = arena.x(), cz = arena.z();
        place(cx - HALF_GAP, cz, l -> {
            m.sa = l;
            fa.complete(null);
        });
        place(cx + HALF_GAP, cz, l -> {
            m.sb = l;
            fb.complete(null);
        });
        CompletableFuture.allOf(fa, fb).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
            if (m.over) return;
            m.sa.setYaw(-90);
            m.sb.setYaw(90);
            m.a.teleportAsync(m.sa);
            m.b.teleportAsync(m.sb);
            giveKit(m.a, k);
            giveKit(m.b, k);
            m.task = Bukkit.getScheduler().runTaskTimer(this, () -> tick(m), 20, 20);
        }));
    }

    void tick(Match m) {
        if (m.over) return;
        if (!m.a.isOnline()) {
            finish(m, m.b, m.a.getName() + " left.");
            return;
        }
        if (!m.b.isOnline()) {
            finish(m, m.a, m.b.getName() + " left.");
            return;
        }
        if (!m.live) {
            if (m.secs > 0) {
                for (Player p : new Player[]{m.a, m.b}) p.showTitle(Title.title(Component.text(String.valueOf(m.secs), NamedTextColor.GOLD), Component.text(m.kit.label),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                m.secs--;
                return;
            }
            m.live = true;
            frozen.remove(m.a.getUniqueId());
            frozen.remove(m.b.getUniqueId());
            for (Player p : new Player[]{m.a, m.b}) p.showTitle(Title.title(Component.text("FIGHT", NamedTextColor.RED), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200))));
            return;
        }
        // out of the arena: back to the start
        for (Player p : new Player[]{m.a, m.b}) {
            double dx = p.getLocation().getX() - (m.arena.x() + 0.5), dz = p.getLocation().getZ() - (m.arena.z() + 0.5);
            if (dx * dx + dz * dz > RADIUS * RADIUS) {
                p.teleportAsync(p == m.a ? m.sa : m.sb);
                say(p, "Stay inside the arena.", NamedTextColor.RED);
            }
        }
        if (--m.left <= 0) finish(m, null, "Time is up: a draw.");
    }

    /** the winner (or null for a draw) is known: ratings, messages, and back to the lobby after a moment */
    void finish(Match m, Player winner, String why) {
        if (m.over) return;
        m.over = true;
        if (m.task != null) m.task.cancel();
        running.remove(m);
        frozen.remove(m.a.getUniqueId());
        frozen.remove(m.b.getUniqueId());
        if (winner != null) {
            Player loser = m.other(winner);
            Stat w = stat(winner), l = stat(loser);
            double ew = 1 / (1 + Math.pow(10, (l.elo - w.elo) / 400.0));
            int gain = (int) Math.round(32 * (1 - ew));
            w.elo += gain;
            l.elo = Math.max(0, l.elo - gain);
            w.wins++;
            w.streak++;
            l.losses++;
            l.streak = 0;
            winner.showTitle(Title.title(Component.text("VICTORY", NamedTextColor.GREEN), Component.text("+" + gain + " rating, now " + w.elo + " (" + tier(w.elo) + ")")));
            loser.showTitle(Title.title(Component.text("DEFEAT", NamedTextColor.RED), Component.text("-" + gain + " rating, now " + l.elo + " (" + tier(l.elo) + ")")));
            if (why != null) {
                say(winner, why, NamedTextColor.GRAY);
                say(loser, why, NamedTextColor.GRAY);
            }
            Bukkit.broadcast(Component.text(winner.getName() + " beat " + loser.getName() + " in " + m.kit.label + " at " + m.arena.name() + " (+" + gain + ").", NamedTextColor.GRAY));
            save();
        } else {
            for (Player p : new Player[]{m.a, m.b}) say(p, why == null ? "The duel is over." : why, NamedTextColor.YELLOW);
        }
        inMatch.remove(m.a.getUniqueId());
        inMatch.remove(m.b.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            busy.remove(m.arena);
            for (Player p : new Player[]{m.a, m.b}) if (p.isOnline() && !inMatch.containsKey(p.getUniqueId())) toLobby(p);
        }, 60);
    }

    // ---- ratings ----

    Stat stat(Player p) {
        Stat s = stats.computeIfAbsent(p.getUniqueId(), k -> new Stat());
        s.name = p.getName();
        return s;
    }

    static String tier(int elo) {
        return elo >= 1500 ? "Legend" : elo >= 1300 ? "Champion" : elo >= 1100 ? "Gladiator" : elo >= 900 ? "Fighter" : "Rookie";
    }

    synchronized void save() {
        try {
            Files.createDirectories(statsFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<UUID, Stat> e : stats.entrySet()) {
                Stat s = e.getValue();
                sb.append(e.getKey()).append('\t').append(s.name).append('\t').append(s.elo).append('\t').append(s.wins).append('\t').append(s.losses).append('\t').append(s.streak).append('\n');
            }
            Files.writeString(statsFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("stats not saved: " + e);
        }
    }

    void load() {
        if (!Files.isRegularFile(statsFile)) return;
        try {
            for (String line : Files.readAllLines(statsFile, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 6) continue;
                Stat s = new Stat();
                s.name = p[1];
                s.elo = Integer.parseInt(p[2]);
                s.wins = Integer.parseInt(p[3]);
                s.losses = Integer.parseInt(p[4]);
                s.streak = Integer.parseInt(p[5]);
                stats.put(UUID.fromString(p[0]), s);
            }
        } catch (Exception e) {
            getLogger().warning("stats not read: " + e);
        }
    }

    /** the leaderboard, the fights that are going on and the queues, as a file for the website */
    void export() {
        List<Stat> top = new ArrayList<>(stats.values());
        top.sort(Comparator.comparingInt((Stat s) -> s.elo).reversed());
        StringBuilder sb = new StringBuilder("{\"updated\":").append(System.currentTimeMillis() / 1000).append(",\"online\":").append(Bukkit.getOnlinePlayers().size()).append(",\"players\":[");
        for (int i = 0; i < Math.min(50, top.size()); i++) {
            Stat s = top.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(json(s.name)).append("\",\"elo\":").append(s.elo).append(",\"tier\":\"").append(tier(s.elo)).append("\",\"wins\":").append(s.wins)
                    .append(",\"losses\":").append(s.losses).append(",\"streak\":").append(s.streak).append('}');
        }
        sb.append("],\"matches\":[");
        boolean first = true;
        for (Match m : running) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"a\":\"").append(json(m.a.getName())).append("\",\"b\":\"").append(json(m.b.getName())).append("\",\"kit\":\"").append(m.kit.label).append("\",\"arena\":\"").append(json(m.arena.name())).append("\"}");
        }
        sb.append("],\"queues\":{");
        first = true;
        for (Kit k : Kit.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(k.label).append("\":").append(queues.get(k).size());
        }
        sb.append("}}");
        String out = sb.toString();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(exportFile.getParent());
                Path tmp = exportFile.resolveSibling(exportFile.getFileName() + ".tmp");
                Files.writeString(tmp, out, StandardCharsets.UTF_8);
                Files.move(tmp, exportFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                getLogger().warning("the file for the website could not be written: " + e);
            }
        });
    }

    static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- commands ----

    void say(Player p, String text, NamedTextColor c) {
        p.sendMessage(Component.text(text, c));
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "queue" -> {
                Kit k = null;
                if (args.length > 0) for (Kit x : Kit.values()) if (x.name().equalsIgnoreCase(args[0])) k = x;
                if (k == null) say(p, "Use /queue sword, /queue axe or /queue bow.", NamedTextColor.YELLOW);
                else join(p, k);
            }
            case "leave" -> {
                Match m = inMatch.get(p.getUniqueId());
                if (m != null) finish(m, m.other(p), p.getName() + " gave up.");
                else leaveQueue(p);
            }
            case "stats" -> {
                Stat st = null;
                if (args.length > 0) {
                    for (Stat x : stats.values()) if (x.name != null && x.name.equalsIgnoreCase(args[0])) st = x;
                } else {
                    st = stat(p);
                }
                if (st == null) say(p, "No fights for that name yet.", NamedTextColor.GRAY);
                else say(p, st.name + ": " + st.elo + " (" + tier(st.elo) + "), " + st.wins + " wins, " + st.losses + " losses, streak " + st.streak + ".", NamedTextColor.AQUA);
            }
            case "top" -> {
                List<Stat> top = new ArrayList<>(stats.values());
                top.sort(Comparator.comparingInt((Stat x) -> x.elo).reversed());
                say(p, "Top fighters:", NamedTextColor.GOLD);
                for (int i = 0; i < Math.min(10, top.size()); i++) say(p, (i + 1) + ". " + top.get(i).name + " " + top.get(i).elo + " (" + top.get(i).wins + "-" + top.get(i).losses + ")", NamedTextColor.WHITE);
            }
            case "spawn" -> {
                if (inMatch.containsKey(p.getUniqueId())) say(p, "Not during a duel. /leave gives up.", NamedTextColor.RED);
                else toLobby(p);
            }
            case "kits" -> {
                for (Kit k : Kit.values()) say(p, k.label + ": " + k.text, NamedTextColor.WHITE);
            }
            default -> {
            }
        }
        return true;
    }

    // ---- the rules of the lobby and the arenas ----

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.joinMessage(null);
        stat(e.getPlayer());
        toLobby(e.getPlayer());
        say(e.getPlayer(), "Berlin Duels: ranked 1v1 in the streets of Berlin. Right-click a weapon to queue, or /queue sword|axe|bow.", NamedTextColor.GREEN);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        Player p = e.getPlayer();
        Kit k = queued.remove(p.getUniqueId());
        if (k != null) queues.get(k).remove(p.getUniqueId());
        frozen.remove(p.getUniqueId());
        Match m = inMatch.get(p.getUniqueId());
        if (m != null) finish(m, m.other(p), p.getName() + " left.");
        save();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (it != null && it.hasItemMeta()) {
            String a = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (a != null) {
                e.setCancelled(true);
                if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) return;
                if (a.startsWith("queue:")) join(p, Kit.valueOf(a.substring(6).toUpperCase(Locale.ROOT)));
                else if (a.equals("leave")) leaveQueue(p);
                else if (a.equals("stats")) onCommand(p, getCommand("stats"), "stats", new String[0]);
                return;
            }
        }
        if (e.getClickedBlock() != null && !p.isOp()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = inMatch.get(p.getUniqueId());
        if (m == null || !m.live) {
            e.setCancelled(true);
            return;
        }
        if (p.getHealth() - e.getFinalDamage() <= 0) { // the fight ends before anybody dies
            e.setCancelled(true);
            finish(m, m.other(p), null);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!frozen.contains(e.getPlayer().getUniqueId())) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t != null && (f.getX() != t.getX() || f.getZ() != t.getZ())) e.setTo(f.clone().setDirection(t.getDirection()));
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (!e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (!inMatch.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !inMatch.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && !inMatch.containsKey(p.getUniqueId()) && !p.isOp()) e.setCancelled(true);
    }

    /** only the commands of the duels (and the chat ones) are allowed */
    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        if (e.getPlayer().isOp()) return;
        String c = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = c.indexOf(':');
        if (colon >= 0) c = c.substring(colon + 1);
        if (!Set.of("queue", "leave", "stats", "top", "spawn", "kits", "msg", "tell", "w", "r", "help").contains(c)) {
            e.setCancelled(true);
            say(e.getPlayer(), "Commands here: /queue, /leave, /stats, /top, /spawn, /kits.", NamedTextColor.GRAY);
        }
    }
}
