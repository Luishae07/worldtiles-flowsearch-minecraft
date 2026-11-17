package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /plane [type]: fly a plane over the real world. The wings are an elytra that the plugin gives you; the plugin sets your speed itself
 * (throttle on W and S, pitch from where you look, a stall under the stall speed), shows a plane made of blocks to the others,
 * and gets the map ready ahead of you.
 */
final class Pilot implements CommandExecutor, Listener {
    private record Type(String name, double vmax, double stall, double len, int engines, String label) {}

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("cessna", new Type("cessna", 75, 26, 8.3, 0, "Cessna 172"));
        TYPES.put("a320", new Type("a320", 150, 58, 37.6, 2, "Airbus A320"));
        TYPES.put("b747", new Type("b747", 160, 62, 70.6, 4, "Boeing 747"));
        TYPES.put("learjet", new Type("learjet", 130, 48, 14.5, 2, "Learjet 45"));
    }

    private final class Flight {
        final Player p;
        final Type t;
        double throttle, v, lastYaw, roll;
        long ticks;
        ItemStack savedChest;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<float[]> layout = new ArrayList<>(); // per part: offset x,y,z, size x,y,z (metres, at the real length)

        Flight(Player p, Type t) {
            this.p = p;
            this.t = t;
        }
    }

    private final JavaPlugin plugin;
    private final TileStore store;
    private final NamespacedKey key;
    private final Map<UUID, Flight> flights = new HashMap<>();

    Pilot(JavaPlugin plugin, TileStore store) {
        this.plugin = plugin;
        this.store = store;
        this.key = new NamespacedKey(plugin, "pilot_wings");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(flights.keySet())) end(flights.get(id), null);
    }

    private static Component say(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can fly.");
            return true;
        }
        Flight f = flights.get(p.getUniqueId());
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (f != null && (a.isEmpty() || a.equals("exit") || a.equals("land") || a.equals("off"))) {
            end(f, "You left the plane. A parachute (slow fall) is on for 10 seconds.");
            return true;
        }
        if (f != null) {
            p.sendMessage(say("You are already flying. /plane lands you first.", NamedTextColor.YELLOW));
            return true;
        }
        if (p.getWorld().getName().equals(MoonLife.WORLD)) {
            p.sendMessage(say("There is no air on the Moon. Planes need an atmosphere: /earth.", NamedTextColor.RED));
            return true;
        }
        Type t = a.isEmpty() ? TYPES.get("cessna") : TYPES.get(a);
        if (t == null) {
            p.sendMessage(say("Planes: " + String.join(", ", TYPES.keySet()) + ". Use /plane <type>, and /plane again to get out.", NamedTextColor.YELLOW));
            return true;
        }
        launch(p, t);
        return true;
    }

    private void launch(Player p, Type t) {
        Flight f = new Flight(p, t);
        f.v = t.vmax * 0.65;
        f.throttle = 0.65;
        f.lastYaw = p.getLocation().getYaw();
        f.savedChest = p.getInventory().getChestplate();
        ItemStack wings = new ItemStack(Material.ELYTRA);
        ItemMeta m = wings.getItemMeta();
        m.setUnbreakable(true);
        m.displayName(Component.text("Pilot wings"));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        wings.setItemMeta(m);
        p.getInventory().setChestplate(wings);
        buildModel(f);
        flights.put(p.getUniqueId(), f);
        Location l = p.getLocation().add(0, 3, 0);
        p.teleport(l);
        p.setFallDistance(0);
        p.setGliding(true);
        p.setVelocity(l.getDirection().multiply(f.v / 20));
        p.sendMessage(say(t.label + ": W faster, S slower, the mouse steers, stay above " + Math.round(t.stall * 3.6) + " km/h or you stall. /plane to get out.", NamedTextColor.GREEN));
    }

    /** the plane out of blocks: the layout is drawn for a plane of 8 metres and scaled to the real length */
    private void buildModel(Flight f) {
        double s = f.t.len / 8;
        add(f, Material.WHITE_CONCRETE, 0, 0, 0, 1.0 * s, 1.0 * s, 6.0 * s);
        add(f, Material.LIGHT_GRAY_CONCRETE, 0, 0, 3.4 * s, 0.8 * s, 0.8 * s, 1.0 * s);
        add(f, Material.LIGHT_BLUE_STAINED_GLASS, 0, 0.55 * s, 1.9 * s, 0.8 * s, 0.4 * s, 1.0 * s);
        add(f, Material.LIGHT_GRAY_CONCRETE, 0, 0.15 * s, 0.3 * s, 11.0 * s * (f.t.engines >= 2 ? 0.95 : 1), 0.16 * s, 1.7 * s);
        add(f, Material.LIGHT_GRAY_CONCRETE, 0, 0.25 * s, -2.8 * s, 4.0 * s, 0.14 * s, 1.0 * s);
        add(f, Material.RED_CONCRETE, 0, 1.0 * s, -2.7 * s, 0.14 * s, 1.8 * s, 1.2 * s);
        add(f, Material.WHITE_CONCRETE, 0, 0.1 * s, -3.7 * s, 0.6 * s, 0.6 * s, 1.4 * s);
        if (f.t.engines >= 2) {
            add(f, Material.GRAY_CONCRETE, 2.6 * s, -0.55 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            add(f, Material.GRAY_CONCRETE, -2.6 * s, -0.55 * s, 0.9 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
        if (f.t.engines >= 4) {
            add(f, Material.GRAY_CONCRETE, 4.6 * s, -0.5 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
            add(f, Material.GRAY_CONCRETE, -4.6 * s, -0.5 * s, 0.6 * s, 0.9 * s, 0.9 * s, 1.7 * s);
        }
        if (f.t.engines == 0) add(f, Material.BLACK_CONCRETE, 0, 0, 4.0 * s, 0.1 * s, 2.0 * s, 0.1 * s); // the propeller
    }

    private void add(Flight f, Material m, double x, double y, double z, double sx, double sy, double sz) {
        World w = f.p.getWorld();
        Location at = f.p.getLocation();
        BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(1);
            e.setBrightness(new org.bukkit.entity.Display.Brightness(14, 14));
            e.setViewRange(8f);
        });
        f.p.hideEntity(plugin, d); // the pilot looks out of the plane, the others see it
        f.parts.add(d);
        f.layout.add(new float[]{(float) x, (float) y, (float) z, (float) sx, (float) sy, (float) sz});
    }

    private void tick() {
        for (Flight f : new ArrayList<>(flights.values())) {
            Player p = f.p;
            if (!p.isOnline() || p.isDead()) {
                end(f, null);
                continue;
            }
            f.ticks++;
            Input in = p.getCurrentInput();
            if (in.isForward()) f.throttle = Math.min(1, f.throttle + 0.012);
            if (in.isBackward()) f.throttle = Math.max(0, f.throttle - 0.012);
            Location l = p.getLocation();
            double pitchUp = -Math.toRadians(l.getPitch()); // up is positive
            // the engine pulls towards the speed of the throttle; climbing costs speed and diving gives it
            f.v += (f.throttle * f.t.vmax - f.v) * 0.03 - Math.sin(pitchUp) * 0.28;
            f.v = Math.max(0, Math.min(f.t.vmax * 1.3, f.v));
            Vector dir = l.getDirection().normalize();
            if (f.v < f.t.stall) { // stall: the nose falls
                double k = Math.min(1.5, (f.t.stall - f.v) / f.t.stall * 1.8);
                dir = dir.add(new Vector(0, -k, 0)).normalize();
            }
            if (!p.isGliding()) {
                if (f.ticks < 40) p.setGliding(true);
                else {
                    end(f, f.v > f.t.stall * 1.6 ? "Hard landing at " + Math.round(f.v * 3.6) + " km/h. Everything is fine, it is only a game." : "You landed. Welcome to " + place(l));
                    continue;
                }
            }
            p.setVelocity(dir.multiply(f.v / 20.0));
            // the bank of the plane follows how fast you turn
            double dyaw = ((l.getYaw() - f.lastYaw + 540) % 360) - 180;
            f.lastYaw = l.getYaw();
            f.roll += (Math.max(-40, Math.min(40, dyaw * 9)) - f.roll) * 0.2;
            if (f.ticks % 4 == 0) hud(f, l);
            if (f.ticks % 10 == 0) ahead(f, l, dir);
            show(f, l);
        }
    }

    private static String place(Location l) {
        return String.format(Locale.ROOT, "%.4f, %.4f", Cmds.latOf(l.getZ()), Cmds.lonOf(l.getX()));
    }

    private void hud(Flight f, Location l) {
        int hdg = (int) Math.floorMod(Math.round(l.getYaw() + 180), 360);
        String msg = String.format(Locale.ROOT, "%s  |  %d km/h  |  %s m  |  heading %03d  |  throttle %d%%%s", f.t.label, Math.round(f.v * 3.6),
                Math.round(l.getY() - TileGen.SEA), hdg, Math.round(f.throttle * 100), f.v < f.t.stall ? "  |  STALL!" : "");
        f.p.sendActionBar(Component.text(msg, f.v < f.t.stall ? NamedTextColor.RED : NamedTextColor.WHITE));
    }

    /** the map ahead is made while you fly towards it: the squares and the chunks along the way */
    private void ahead(Flight f, Location l, Vector dir) {
        World w = l.getWorld();
        double step = Math.max(64, f.v * 1.5);
        for (int k = 1; k <= 5; k++) {
            double x = l.getX() + dir.getX() * step * k, z = l.getZ() + dir.getZ() * step * k;
            store.prefetch(Math.floorDiv((int) Math.floor(x), Tile.TILE), Math.floorDiv((int) Math.floor(z), Tile.TILE));
            if (k <= 4) w.getChunkAtAsync((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }
    }

    /** the blocks of the plane, turned the way the player looks and banked */
    private void show(Flight f, Location l) {
        Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(l.getYaw())).rotateX((float) Math.toRadians(l.getPitch())).rotateZ((float) Math.toRadians(f.roll));
        Location at = l.clone().add(0, 0.9, 0);
        for (int n = 0; n < f.parts.size(); n++) {
            float[] a = f.layout.get(n);
            BlockDisplay d = f.parts.get(n);
            Vector3f off = new Vector3f(a[0] - a[3] / 2, a[1] - a[4] / 2, a[2] - a[5] / 2).rotate(q); // the middle of the part, turned around the middle of the plane
            d.teleport(at);
            d.setTransformation(new Transformation(off, q, new Vector3f(a[3], a[4], a[5]), new Quaternionf()));
        }
    }

    private void end(Flight f, String message) {
        flights.remove(f.p.getUniqueId());
        for (BlockDisplay d : f.parts) d.remove();
        Player p = f.p;
        if (p.isOnline()) {
            p.getInventory().setChestplate(f.savedChest);
            p.setGliding(false);
            p.setFallDistance(0);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0, false, false, false));
            p.sendActionBar(Component.empty());
            if (message != null) p.sendMessage(say(message, NamedTextColor.GREEN));
        }
    }

    private boolean isWings(ItemStack s) {
        return s != null && s.getType() == Material.ELYTRA && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { // wings left over from before a restart are taken away
        Player p = e.getPlayer();
        if (!flights.containsKey(p.getUniqueId()) && isWings(p.getInventory().getChestplate())) p.getInventory().setChestplate(null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Flight f = flights.get(e.getPlayer().getUniqueId());
        if (f != null) end(f, null);
    }
}
