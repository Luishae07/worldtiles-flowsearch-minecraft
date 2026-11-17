package org.flowsearch.worldtiles;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Input;
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
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /car: a car on the real streets. Roads (the asphalt of the map) are fast, everything else is slow, houses and water stop you.
 * W/S gas and reverse, A/D steer, Space brakes, Shift gets out.
 */
public final class Car implements CommandExecutor, Listener {
    private final JavaPlugin plugin;
    private final TileGen gen;
    private final TileStore store;
    private final Map<UUID, Drive> drives = new HashMap<>();

    private static final class Drive {
        Player p;
        ArmorStand seat;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<float[]> layout = new ArrayList<>();
        double x, y, z, heading, speed;
        int ticks;
        // taxi job: a candidate place is made first, then a street near it becomes the target
        boolean job, hasTarget;
        double tx, tz, candX, candZ, startX, startZ;
        int candTicks;
        BlockDisplay marker;
    }

    private final Economy eco;
    private final java.util.Random rnd = new java.util.Random();

    Car(JavaPlugin plugin, TileGen gen, TileStore store, Economy eco) {
        this.plugin = plugin;
        this.gen = gen;
        this.store = store;
        this.eco = eco;
    }

    private void newCandidate(Drive d) {
        // from 150 m to 3 km, short trips are more common than long ones
        double ang = rnd.nextDouble() * Math.PI * 2, dist = 150 * Math.pow(20, rnd.nextDouble());
        d.candX = d.x + Math.cos(ang) * dist;
        d.candZ = d.z + Math.sin(ang) * dist;
        d.candTicks = 0;
        store.prefetch(Math.floorDiv((int) Math.floor(d.candX), Tile.TILE), Math.floorDiv((int) Math.floor(d.candZ), Tile.TILE));
    }

    /** looks for a street near the candidate place; true when the target is set */
    private boolean findTarget(Drive d) {
        for (int r = 0; r <= 60; r += 3) {
            for (int dz = -r; dz <= r; dz += 3) {
                for (int dx = -r; dx <= r; dx += 3) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = (int) Math.floor(d.candX) + dx, z = (int) Math.floor(d.candZ) + dz;
                    int[] c = gen.cell(x, z);
                    if (c == null || c[1] > 0 || c[3] == 1 || c[2] != Tile.S_ASPHALT) continue;
                    d.tx = x + 0.5;
                    d.tz = z + 0.5;
                    d.startX = d.x;
                    d.startZ = d.z;
                    World w = d.p.getWorld();
                    d.marker = w.spawn(new Location(w, d.tx, c[0] + 1, d.tz), BlockDisplay.class, e -> {
                        e.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
                        e.setPersistent(false);
                        e.setVisibleByDefault(false);
                        e.setBrightness(new Display.Brightness(15, 15));
                        e.setViewRange(8f);
                        e.setTransformation(new Transformation(new Vector3f(-0.75f, 0, -0.75f), new Quaternionf(), new Vector3f(1.5f, 80, 1.5f), new Quaternionf()));
                    });
                    d.p.showEntity(plugin, d.marker);
                    d.hasTarget = true;
                    long km10 = Math.round(Math.hypot(d.tx - d.x, d.tz - d.z) / 10);
                    d.p.sendMessage(Component.text("A passenger waits " + (km10 * 10) + " m away (the blue pillar). Drive there and stop. Pay: " + Math.max(30, km10 * 3) + " coins. (/job again for another one)", NamedTextColor.GREEN));
                    return true;
                }
            }
        }
        return false;
    }

    private void jobTick(Drive d) {
        Player p = d.p;
        if (!d.job) return;
        if (!d.hasTarget) {
            if (d.ticks % 20 == 0) {
                if (gen.cell((int) Math.floor(d.candX), (int) Math.floor(d.candZ)) != null && findTarget(d)) return;
                if (++d.candTicks > 20 || gen.cell((int) Math.floor(d.candX), (int) Math.floor(d.candZ)) != null) newCandidate(d); // no street here: another place
            }
            return;
        }
        double dist = Math.hypot(d.tx - d.x, d.tz - d.z);
        if (dist < 9 && Math.abs(d.speed) < 5) {
            long pay = Math.max(30, Math.round(Math.hypot(d.tx - d.startX, d.tz - d.startZ) / 10) * 3);
            eco.add(p.getUniqueId(), pay);
            p.sendMessage(Component.text("Passenger delivered: +" + pay + " coins (" + eco.get(p.getUniqueId()) + " in all). /job for the next one.", NamedTextColor.GOLD));
            if (d.marker != null) d.marker.remove();
            d.marker = null;
            d.hasTarget = false;
            d.job = false;
        }
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void removeAll() {
        for (Drive d : new ArrayList<>(drives.values())) end(d, null);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        Drive cur = drives.get(p.getUniqueId());
        if (label.equalsIgnoreCase("job")) {
            if (cur == null) {
                p.sendMessage(Component.text("Get in a car first (/car), then /job for a passenger.", NamedTextColor.YELLOW));
            } else if (cur.job) {
                if (cur.marker != null) cur.marker.remove();
                cur.marker = null;
                cur.hasTarget = false;
                if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
                    cur.job = false;
                    p.sendMessage(Component.text("Job cancelled.", NamedTextColor.YELLOW));
                } else {
                    newCandidate(cur);
                    p.sendMessage(Component.text("Looking for another passenger…", NamedTextColor.GRAY));
                }
            } else {
                cur.job = true;
                cur.hasTarget = false;
                newCandidate(cur);
                p.sendMessage(Component.text("Looking for a passenger…", NamedTextColor.GRAY));
            }
            return true;
        }
        if (cur != null) {
            end(cur, "You got out of the car.");
            return true;
        }
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("Cars are for Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        if (p.isInsideVehicle()) {
            p.sendMessage(Component.text("Get out of what you are in first.", NamedTextColor.YELLOW));
            return true;
        }
        Location l = p.getLocation();
        int[] c = gen.cell(l.getBlockX(), l.getBlockZ());
        if (c == null) {
            p.sendMessage(Component.text("The ground here is not built yet. Wait a moment and try again.", NamedTextColor.YELLOW));
            return true;
        }
        Drive d = new Drive();
        d.p = p;
        d.x = l.getX();
        d.z = l.getZ();
        d.y = c[0] + 1;
        d.heading = l.getYaw() + 180;
        World w = p.getWorld();
        d.seat = w.spawn(new Location(w, d.x, d.y, d.z), ArmorStand.class, e -> {
            e.setInvisible(true);
            e.setGravity(false);
            e.setSmall(true);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setSilent(true);
        });
        build(d);
        drives.put(p.getUniqueId(), d);
        d.seat.addPassenger(p);
        p.sendMessage(Component.text("Your car: W gas, S brake and reverse, A/D steer, Space handbrake, Shift to get out. Roads are fast, grass is slow, houses and water stop you.", NamedTextColor.GREEN));
        return true;
    }

    private void part(Drive d, Material m, double x, double y, double z, double sx, double sy, double sz) {
        BlockDisplay b = d.p.getWorld().spawn(d.p.getLocation(), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.setTeleportDuration(0);
            e.setInterpolationDuration(0);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(1.5f);
        });
        d.parts.add(b);
        d.layout.add(new float[]{(float) x, (float) y, (float) z, (float) sx, (float) sy, (float) sz});
    }

    /** the car: +z is forward, +x is left, the wheels stand on y=0 */
    private void build(Drive d) {
        part(d, Material.RED_CONCRETE, 0, 0.62, 0, 1.8, 0.55, 4.0);
        part(d, Material.LIGHT_BLUE_STAINED_GLASS, 0, 1.17, -0.25, 1.6, 0.55, 1.9);
        part(d, Material.RED_CONCRETE, 0, 1.47, -0.25, 1.7, 0.08, 2.0);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) part(d, Material.BLACK_CONCRETE, sx * 0.95, 0.32, sz * 1.3, 0.28, 0.64, 0.64);
            part(d, Material.YELLOW_CONCRETE, sx * 0.6, 0.7, 2.0, 0.35, 0.2, 0.06);
            part(d, Material.ORANGE_CONCRETE, sx * 0.6, 0.7, -2.0, 0.35, 0.2, 0.06);
        }
    }

    private static double maxSpeed(int surf) {
        if (surf == Tile.S_ASPHALT) return 30;
        if (surf == Tile.S_PLAZA) return 9;
        if (surf == Tile.S_GRAVEL || surf == Tile.S_DIRT || surf == Tile.S_SAND) return 13;
        return 8; // grass
    }

    private void tick() {
        for (Drive d : new ArrayList<>(drives.values())) {
            Player p = d.p;
            if (!p.isOnline() || p.isDead() || !d.seat.isValid() || p.getWorld().getGenerator() != gen) {
                end(d, null);
                continue;
            }
            d.ticks++;
            Input in = p.getCurrentInput();
            int[] here = gen.cell((int) Math.floor(d.x), (int) Math.floor(d.z));
            double top = here != null ? maxSpeed(here[2]) : 0;
            double dt = 0.05;
            if (in.isJump()) d.speed = Math.signum(d.speed) * Math.max(0, Math.abs(d.speed) - 25 * dt);
            else if (in.isForward()) d.speed += (d.speed < 0 ? 16 : 8) * dt;
            else if (in.isBackward()) d.speed -= (d.speed > 0 ? 16 : 5) * dt;
            else d.speed = Math.signum(d.speed) * Math.max(0, Math.abs(d.speed) - 4 * dt);
            d.speed = Math.max(-top * 0.3, Math.min(top, d.speed)); // off the road the car slows down by itself
            if (here != null && Math.abs(d.speed) > top) d.speed = Math.signum(d.speed) * top;
            double steer = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
            d.heading += steer * 95 * Math.min(1, Math.abs(d.speed) / 6) * Math.signum(d.speed) * dt;
            double th = Math.toRadians(d.heading), fx = Math.sin(th), fz = -Math.cos(th);
            double nx = d.x + fx * d.speed * dt, nz = d.z + fz * d.speed * dt;
            // what is at the front (or at the back, when reversing)?
            double reach = d.speed >= 0 ? 2.2 : -2.2;
            int[] front = gen.cell((int) Math.floor(nx + fx * reach), (int) Math.floor(nz + fz * reach));
            int[] at = gen.cell((int) Math.floor(nx), (int) Math.floor(nz));
            boolean blocked = front == null || at == null || front[1] > 0 || at[1] > 0 || front[3] == 1 || at[3] == 1
                    || front[0] > d.y + 1.2 || at[0] > d.y + 1.2;
            if (blocked) {
                d.speed = -d.speed * 0.25;
            } else {
                d.x = nx;
                d.z = nz;
                double ty = at[0] + 1;
                d.y += Math.max(-0.7, Math.min(0.5, (ty - d.y) * 0.6));
            }
            Location loc = new Location(p.getWorld(), d.x, d.y + 0.2, d.z);
            d.seat.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(d.heading + 180));
            Location at0 = new Location(p.getWorld(), d.x, d.y, d.z);
            for (int n = 0; n < d.parts.size(); n++) {
                float[] f = d.layout.get(n);
                BlockDisplay b = d.parts.get(n);
                Vector3f off = new Vector3f(f[0] - f[3] / 2, f[1] - f[4] / 2, f[2] - f[5] / 2).rotate(q);
                b.teleport(at0);
                b.setTransformation(new Transformation(off, q, new Vector3f(f[3], f[4], f[5]), new Quaternionf()));
            }
            // make the ground ahead
            if (d.ticks % 10 == 0) {
                double step = Math.max(60, Math.abs(d.speed) * 4);
                for (int k = 1; k <= 3; k++) {
                    double ax = d.x + fx * step * k, az = d.z + fz * step * k;
                    store.prefetch(Math.floorDiv((int) Math.floor(ax), Tile.TILE), Math.floorDiv((int) Math.floor(az), Tile.TILE));
                    p.getWorld().getChunkAtAsync((int) Math.floor(ax) >> 4, (int) Math.floor(az) >> 4);
                }
            }
            jobTick(d);
            if (d.ticks % 4 == 0) {
                String on = here == null ? "waiting for the ground" : here[2] == Tile.S_ASPHALT ? "road" : "off the road (slow)";
                String job = "";
                if (d.job && d.hasTarget) {
                    double rel = ((Math.toDegrees(Math.atan2(d.tx - d.x, -(d.tz - d.z))) - d.heading) % 360 + 540) % 360 - 180; // + = to the right
                    String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
                    job = "  |  passenger " + arrows[(int) Math.floorMod(Math.round(rel / 45), 8)] + " " + Math.round(Math.hypot(d.tx - d.x, d.tz - d.z)) + " m";
                }
                p.sendActionBar(Component.text(String.format(Locale.ROOT, "%d km/h  |  %s%s  |  /job taxi, Shift out", Math.round(Math.abs(d.speed) * 3.6), on, job), NamedTextColor.WHITE));
            }
        }
    }

    private void end(Drive d, String msg) {
        drives.remove(d.p.getUniqueId());
        for (BlockDisplay b : d.parts) b.remove();
        if (d.marker != null) d.marker.remove();
        Player p = d.p;
        if (d.seat != null && d.seat.isValid()) {
            d.seat.removePassenger(p);
            d.seat.remove();
        }
        if (p.isOnline()) {
            p.setFallDistance(0);
            if (msg != null) p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Drive d = drives.get(p.getUniqueId());
        if (d != null && e.getDismounted() == d.seat) Bukkit.getScheduler().runTask(plugin, () -> {
            if (drives.get(p.getUniqueId()) == d) end(d, "You got out of the car.");
        });
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && drives.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Drive d = drives.get(e.getPlayer().getUniqueId());
        if (d != null) end(d, null);
    }
}
