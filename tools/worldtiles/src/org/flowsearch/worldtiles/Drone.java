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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /drone: you fly a camera drone while your body stays where you started (a stand with your head). Fly with the normal flying keys
 * (Space up, Shift down); it is fast, it has a battery (10 minutes) and a range (400 blocks), and the others see a drone with spinning rotors.
 * /drone again brings you back.
 */
public final class Drone implements CommandExecutor, Listener {
    private static final long BATTERY_MS = 10 * 60 * 1000L;
    private static final double RANGE = 400;

    private static final class Fly {
        Player p;
        Location home;
        boolean allowFlight;
        float flySpeed;
        ArmorStand body;
        final List<BlockDisplay> parts = new ArrayList<>();
        BlockDisplay[] rotors = new BlockDisplay[4];
        long end;
        int ticks;
        float speed = 0.2f;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final TileStore store;
    private final Map<UUID, Fly> flights = new HashMap<>();

    Drone(JavaPlugin plugin, TileGen gen, TileStore store) {
        this.plugin = plugin;
        this.gen = gen;
        this.store = store;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
    }

    void removeAll() {
        for (Fly f : new ArrayList<>(flights.values())) end(f, null, false);
    }

    private BlockDisplay part(Fly f, World w, Material m, double ox, double oy, double oz, double sx, double sy, double sz) {
        BlockDisplay b = w.spawn(f.p.getLocation(), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(1);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(2f);
            e.setTransformation(new Transformation(new Vector3f((float) ox, (float) oy, (float) oz), new Quaternionf(), new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
        f.p.hideEntity(plugin, b); // you do not see your own drone (you look out of it)
        f.parts.add(b);
        return b;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        Fly cur = flights.get(p.getUniqueId());
        if (args.length > 0 && args[0].equalsIgnoreCase("speed")) {
            try {
                int n = Math.max(1, Math.min(10, Integer.parseInt(args[1])));
                if (cur != null) {
                    cur.speed = n * 0.05f;
                    p.setFlySpeed(cur.speed);
                }
                p.sendMessage(Component.text("Drone speed " + n + " of 10" + (cur == null ? " (it takes effect when you fly)." : "."), NamedTextColor.GREEN));
                if (cur == null) pendingSpeed.put(p.getUniqueId(), n * 0.05f);
            } catch (Exception e) {
                p.sendMessage(Component.text("/drone speed <1-10>", NamedTextColor.YELLOW));
            }
            return true;
        }
        if (cur != null) {
            end(cur, "Your drone is back. Welcome home!", true);
            return true;
        }
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("The drone flies on Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        if (p.isInsideVehicle() || p.isGliding()) {
            p.sendMessage(Component.text("Get out of what you are in first.", NamedTextColor.YELLOW));
            return true;
        }
        Fly f = new Fly();
        f.p = p;
        f.home = p.getLocation().clone();
        f.allowFlight = p.getAllowFlight();
        f.flySpeed = p.getFlySpeed();
        f.end = System.currentTimeMillis() + BATTERY_MS;
        f.speed = pendingSpeed.getOrDefault(p.getUniqueId(), 0.2f);
        World w = p.getWorld();
        // your body stays: a stand with your head
        f.body = w.spawn(f.home, ArmorStand.class, e -> {
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setArms(true);
            e.setBasePlate(false);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setOwningPlayer(p);
            head.setItemMeta(sm);
            e.getEquipment().setHelmet(head);
            e.getEquipment().setChestplate(new ItemStack(Material.LEATHER_CHESTPLATE));
            e.getEquipment().setLeggings(new ItemStack(Material.LEATHER_LEGGINGS));
            e.customName(Component.text(p.getName() + " (flying the drone)", NamedTextColor.GRAY));
            e.setCustomNameVisible(true);
        });
        // the drone the others see: a body, two arms, four spinning rotors and lights
        part(f, w, Material.GRAY_CONCRETE, -0.25, -0.15, -0.25, 0.5, 0.18, 0.5);
        part(f, w, Material.BLACK_CONCRETE, -0.5, -0.05, -0.04, 1.0, 0.05, 0.08);
        part(f, w, Material.BLACK_CONCRETE, -0.04, -0.05, -0.5, 0.08, 0.05, 1.0);
        part(f, w, Material.LIME_CONCRETE, -0.1, -0.1, -0.3, 0.2, 0.1, 0.05);
        part(f, w, Material.RED_CONCRETE, -0.1, -0.1, 0.25, 0.2, 0.1, 0.05);
        double[][] rp = {{0.5, 0}, {-0.5, 0}, {0, 0.5}, {0, -0.5}};
        for (int i = 0; i < 4; i++) f.rotors[i] = part(f, w, Material.LIGHT_GRAY_STAINED_GLASS, rp[i][0] - 0.2, 0.0, rp[i][1] - 0.2, 0.4, 0.02, 0.4);
        flights.put(p.getUniqueId(), f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        p.setAllowFlight(true);
        p.setFlySpeed(f.speed);
        p.teleport(f.home.clone().add(0, 3, 0));
        p.setFlying(true);
        p.sendMessage(Component.text("You are flying your drone! Space up, Shift down (double-tap Space if you fall). Battery 10 minutes, range " + (int) RANGE + " blocks. /drone speed 1-10. /drone brings you back.", NamedTextColor.GREEN));
        return true;
    }

    private final Map<UUID, Float> pendingSpeed = new HashMap<>();

    private void tick() {
        long now = System.currentTimeMillis();
        for (Fly f : new ArrayList<>(flights.values())) {
            Player p = f.p;
            if (!p.isOnline() || p.isDead() || p.getWorld() != f.home.getWorld()) {
                end(f, null, false);
                continue;
            }
            f.ticks++;
            Location l = p.getLocation();
            if (!p.isFlying()) p.setFlying(true);
            double dist = Math.hypot(l.getX() - f.home.getX(), l.getZ() - f.home.getZ());
            if (now >= f.end) {
                end(f, "The battery of the drone is empty: it flew back to you.", true);
                continue;
            }
            if (dist > RANGE) {
                end(f, "Signal lost: the drone flew out of range and came back.", true);
                continue;
            }
            // the drone model follows you; the rotors spin
            for (BlockDisplay b : f.parts) b.teleport(l);
            float ang = (f.ticks * 1.2f) % 360f;
            for (int i = 0; i < 4; i++) {
                float a = (float) Math.toRadians(i % 2 == 0 ? ang : -ang);
                double[][] rp = {{0.5, 0}, {-0.5, 0}, {0, 0.5}, {0, -0.5}};
                Quaternionf q = new Quaternionf().rotateY(a);
                Vector3f c = new Vector3f((float) rp[i][0], 0.01f, (float) rp[i][1]);
                Vector3f off = new Vector3f(-0.2f, 0, -0.2f).rotate(q).add(c);
                f.rotors[i].setTransformation(new Transformation(off, q, new Vector3f(0.4f, 0.02f, 0.4f), new Quaternionf()));
            }
            // the ground ahead is made while you fly
            if (f.ticks % 10 == 0) {
                Vector dir = l.getDirection().setY(0);
                if (dir.lengthSquared() > 0.01) {
                    dir.normalize();
                    for (int k = 1; k <= 3; k++) {
                        double x = l.getX() + dir.getX() * 90 * k, z = l.getZ() + dir.getZ() * 90 * k;
                        store.prefetch(Math.floorDiv((int) Math.floor(x), Tile.TILE), Math.floorDiv((int) Math.floor(z), Tile.TILE));
                        if (k <= 2) p.getWorld().getChunkAtAsync((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
                    }
                }
            }
            if (f.ticks % 5 == 0) {
                int[] c = gen.cell((int) Math.floor(l.getX()), (int) Math.floor(l.getZ()));
                long left = Math.max(0, (f.end - now) / 1000);
                p.sendActionBar(Component.text(String.format(Locale.ROOT, "DRONE  |  height %d m  |  %d m from you  |  battery %d:%02d  |  /drone to land", Math.round(c != null ? l.getY() - c[0] : l.getY()), Math.round(dist), left / 60, left % 60), NamedTextColor.WHITE));
            }
        }
    }

    private void end(Fly f, String msg, boolean teleportHome) {
        if (flights.remove(f.p.getUniqueId()) == null) return;
        for (BlockDisplay b : f.parts) b.remove();
        if (f.body != null) f.body.remove();
        Player p = f.p;
        if (!p.isOnline()) return;
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.setFlying(false);
        p.setAllowFlight(f.allowFlight);
        p.setFlySpeed(f.flySpeed);
        p.setFallDistance(0);
        if (teleportHome) p.teleportAsync(f.home);
        if (msg != null) p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && flights.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Fly f = flights.get(e.getPlayer().getUniqueId());
        if (f != null) {
            // leave the player where his body is
            e.getPlayer().teleport(f.home);
            end(f, null, false);
        }
    }
}
