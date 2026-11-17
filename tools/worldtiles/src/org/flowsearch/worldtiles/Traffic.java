package org.flowsearch.worldtiles;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Villagers drive cars along the real roads (the asphalt of the map): they stay on the road, turn at crossings, slow down for curves,
 * and stop for other cars and for you. They only exist near players.
 */
public final class Traffic {
    private static final String TAG = "wt_traffic";
    private static final int PER_PLAYER = 6;
    private static final Material[] COLORS = {Material.RED_CONCRETE, Material.BLUE_CONCRETE, Material.WHITE_CONCRETE, Material.GREEN_CONCRETE, Material.YELLOW_CONCRETE,
            Material.LIGHT_GRAY_CONCRETE, Material.ORANGE_CONCRETE, Material.BLACK_CONCRETE};

    private static final class Auto {
        ArmorStand seat;
        Villager driver;
        final List<BlockDisplay> parts = new ArrayList<>();
        final List<float[]> layout = new ArrayList<>();
        double x, y, z, heading, speed;
        int wait; // ticks until the next turn at a crossing is allowed
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final Random rnd = new Random();
    private final List<Auto> cars = new ArrayList<>();
    private int tickN;

    private final Signals signals;

    Traffic(JavaPlugin plugin, TileGen gen, Signals signals) {
        this.plugin = plugin;
        this.gen = gen;
        this.signals = signals;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 1L);
    }

    void removeAll() {
        for (Auto a : new ArrayList<>(cars)) remove(a);
        cars.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    private void remove(Auto a) {
        for (BlockDisplay b : a.parts) b.remove();
        if (a.driver != null) a.driver.remove();
        if (a.seat != null) a.seat.remove();
    }

    private void part(Auto a, World w, Location at, Material m, double x, double y, double z, double sx, double sy, double sz) {
        BlockDisplay b = w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setTeleportDuration(1);
            e.setInterpolationDuration(1);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(1.5f);
        });
        a.parts.add(b);
        a.layout.add(new float[]{(float) x, (float) y, (float) z, (float) sx, (float) sy, (float) sz});
    }

    private void build(Auto a, World w, Location at) {
        Material body = COLORS[rnd.nextInt(COLORS.length)];
        part(a, w, at, body, 0, 0.62, 0, 1.8, 0.55, 4.0);
        part(a, w, at, Material.LIGHT_BLUE_STAINED_GLASS, 0, 1.17, -0.25, 1.6, 0.55, 1.9);
        part(a, w, at, body, 0, 1.47, -0.25, 1.7, 0.08, 2.0);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) part(a, w, at, Material.BLACK_CONCRETE, sx * 0.95, 0.32, sz * 1.3, 0.28, 0.64, 0.64);
            part(a, w, at, Material.YELLOW_CONCRETE, sx * 0.6, 0.7, 2.0, 0.35, 0.2, 0.06);
            part(a, w, at, Material.ORANGE_CONCRETE, sx * 0.6, 0.7, -2.0, 0.35, 0.2, 0.06);
        }
    }

    /** a place where a car may be: road, no house, no water */
    private boolean road(double x, double z) {
        int[] c = gen.cell((int) Math.floor(x), (int) Math.floor(z));
        return c != null && c[1] == 0 && c[3] == 0 && c[2] == Tile.S_ASPHALT;
    }

    private boolean roadAhead(double x, double z, double heading, double dist) {
        double th = Math.toRadians(heading);
        return road(x + Math.sin(th) * dist, z - Math.cos(th) * dist) && road(x + Math.sin(th) * dist * 0.5, z - Math.cos(th) * dist * 0.5);
    }

    private void tick() {
        tickN++;
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        List<Player> players = earth.getPlayers();
        // spawn and remove
        if (tickN % 40 == 0) {
            for (java.util.Iterator<Auto> it = cars.iterator(); it.hasNext(); ) {
                Auto a = it.next();
                boolean near = false;
                for (Player p : players) if (Math.hypot(p.getLocation().getX() - a.x, p.getLocation().getZ() - a.z) < 160) near = true;
                if (!near || !a.seat.isValid()) {
                    remove(a);
                    it.remove();
                }
            }
            for (Player p : players) {
                int count = 0;
                for (Auto a : cars) if (Math.hypot(p.getLocation().getX() - a.x, p.getLocation().getZ() - a.z) < 130) count++;
                if (count >= PER_PLAYER) continue;
                for (int tries = 0; tries < 8; tries++) {
                    double ang = rnd.nextDouble() * Math.PI * 2, d = 45 + rnd.nextDouble() * 70;
                    double x = p.getLocation().getX() + Math.cos(ang) * d, z = p.getLocation().getZ() + Math.sin(ang) * d;
                    if (!road(x, z)) continue;
                    double h = -1;
                    for (int k = 0; k < 8; k++) {
                        double hh = rnd.nextDouble() * 360;
                        if (roadAhead(x, z, hh, 10)) {
                            h = hh;
                            break;
                        }
                    }
                    if (h < 0) continue;
                    int[] c = gen.cell((int) Math.floor(x), (int) Math.floor(z));
                    Auto a = new Auto();
                    a.x = x;
                    a.z = z;
                    a.y = c[0] + 1;
                    a.heading = h;
                    a.speed = 6;
                    Location at = new Location(earth, x, a.y, z);
                    a.seat = earth.spawn(at, ArmorStand.class, e -> {
                        e.setInvisible(true);
                        e.setGravity(false);
                        e.setSmall(true);
                        e.setPersistent(false);
                        e.setInvulnerable(true);
                        e.setSilent(true);
                        e.addScoreboardTag(TAG);
                    });
                    a.driver = earth.spawn(at, Villager.class, v -> {
                        v.setAI(false);
                        v.setSilent(true);
                        v.setInvulnerable(true);
                        v.setPersistent(false);
                        v.setRemoveWhenFarAway(true);
                        v.addScoreboardTag(TAG);
                        v.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
                    });
                    a.seat.addPassenger(a.driver);
                    build(a, earth, at);
                    cars.add(a);
                    break;
                }
            }
        }
        for (Auto a : cars) drive(a, earth, players);
    }

    private void drive(Auto a, World w, List<Player> players) {
        double dt = 0.05;
        // look for the road: straight on if possible, a turn at crossings sometimes, a u-turn when the road ends
        if (a.wait > 0) a.wait--;
        double[] offs = {0, 8, -8, 20, -20, 40, -40, 85, -85};
        double want = Double.NaN;
        boolean straight = roadAhead(a.x, a.z, a.heading, 7);
        if (straight) {
            want = a.heading;
            if (a.wait == 0 && rnd.nextInt(60) == 0) { // maybe turn at a crossing
                for (double side : new double[]{85, -85}) {
                    double s = rnd.nextBoolean() ? side : -side;
                    if (roadAhead(a.x, a.z, a.heading + s, 9)) {
                        want = a.heading + s;
                        a.wait = 100;
                        break;
                    }
                }
            }
        } else {
            for (double o : offs) {
                if (roadAhead(a.x, a.z, a.heading + o, 7)) {
                    want = a.heading + o;
                    break;
                }
            }
            if (Double.isNaN(want)) want = a.heading + 180; // dead end
        }
        double dh = ((want - a.heading + 540) % 360) - 180;
        a.heading += Math.max(-5, Math.min(5, dh));
        double th = Math.toRadians(a.heading), fx = Math.sin(th), fz = -Math.cos(th);
        // brake for cars and people in front
        double target = Math.abs(dh) > 10 ? 4 : 11;
        if (signals != null && signals.mustStop(a.x, a.z, a.heading)) target = 0; // a red light
        for (Auto o : cars) {
            if (o == a) continue;
            double ox = o.x - a.x, oz = o.z - a.z, ahead = ox * fx + oz * fz, side = Math.abs(ox * -fz + oz * fx);
            if (ahead > 0 && ahead < 9 && side < 2.4) target = 0;
        }
        for (Player p : players) {
            double ox = p.getLocation().getX() - a.x, oz = p.getLocation().getZ() - a.z, ahead = ox * fx + oz * fz, side = Math.abs(ox * -fz + oz * fx);
            if (ahead > 0 && ahead < 10 && side < 2.6) target = 0;
        }
        if (a.speed < target) a.speed = Math.min(target, a.speed + 4 * dt);
        else a.speed = Math.max(target, a.speed - 12 * dt);
        double nx = a.x + fx * a.speed * dt, nz = a.z + fz * a.speed * dt;
        int[] c = gen.cell((int) Math.floor(nx), (int) Math.floor(nz));
        if (c == null || c[1] > 0 || c[3] == 1) {
            a.speed = 0;
        } else {
            a.x = nx;
            a.z = nz;
            a.y += Math.max(-0.7, Math.min(0.5, (c[0] + 1 - a.y) * 0.6));
        }
        a.seat.teleport(new Location(w, a.x, a.y + 0.2, a.z), PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        a.driver.setRotation((float) (a.heading - 180), 0);
        Quaternionf q = new Quaternionf().rotateY((float) -Math.toRadians(a.heading + 180));
        Location at = new Location(w, a.x, a.y, a.z);
        for (int n = 0; n < a.parts.size(); n++) {
            float[] f = a.layout.get(n);
            BlockDisplay b = a.parts.get(n);
            Vector3f off = new Vector3f(f[0] - f[3] / 2, f[1] - f[4] / 2, f[2] - f[5] / 2).rotate(q);
            b.teleport(at);
            b.setTransformation(new Transformation(off, q, new Vector3f(f[3], f[4], f[5]), new Quaternionf()));
        }
    }
}
