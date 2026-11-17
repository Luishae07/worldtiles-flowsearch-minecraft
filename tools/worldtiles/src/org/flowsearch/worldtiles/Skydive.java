package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * /skydive: you are taken 1500 m above where you stand and dropped. A landing circle is somewhere near (350 to 700 m away). Steer in
 * freefall with W A S D, open the parachute with the jump key, and land as close to the circle as you can, softly.
 */
final class Skydive implements CommandExecutor, Listener {
    private static final int HEIGHT = 1500;
    private static final double TERMINAL = 55, TERMINAL_DIVE = 78, CANOPY_SINK = 5;

    private static final class Jump {
        Player p;
        double vx, vy, vz;
        boolean canopy;
        long ticks, openedAt;
        double openAgl;
        double tx, tz;
        int tGround;
        Location start;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final TileStore store;
    private final Map<UUID, Jump> jumps = new HashMap<>();
    private final Map<String, Integer> best = new HashMap<>();
    private final Random rnd = new Random();

    Skydive(JavaPlugin plugin, TileGen gen, TileStore store) {
        this.plugin = plugin;
        this.gen = gen;
        this.store = store;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    private static Component say(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can jump.");
            return true;
        }
        String a = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (a.equals("top")) {
            if (best.isEmpty()) {
                p.sendMessage(say("Nobody has landed yet. /skydive to be the first.", NamedTextColor.YELLOW));
                return true;
            }
            p.sendMessage(say("Best skydive scores:", NamedTextColor.GOLD));
            best.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8).forEach(e -> p.sendMessage(say("  " + e.getValue() + "  " + e.getKey(), NamedTextColor.WHITE)));
            return true;
        }
        if (jumps.containsKey(p.getUniqueId())) {
            if (a.equals("stop") || a.isEmpty()) {
                end(jumps.get(p.getUniqueId()), "Jump cancelled.", true);
            }
            return true;
        }
        if (p.getWorld().getName().equals(MoonLife.WORLD)) {
            p.sendMessage(say("There is no air on the Moon for a parachute. /earth first.", NamedTextColor.RED));
            return true;
        }
        if (p.getInventory().getChestplate() != null && p.getInventory().getChestplate().getType() == org.bukkit.Material.ELYTRA) {
            p.sendMessage(say("Get out of the plane first (/plane).", NamedTextColor.YELLOW));
            return true;
        }
        launch(p);
        return true;
    }

    private void launch(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        int X = l.getBlockX(), Z = l.getBlockZ();
        p.sendMessage(say("Getting ready for the jump ...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int ground = gen.surfaceY(X, Z, true);
                double ang = rnd.nextDouble() * Math.PI * 2, dist = 350 + rnd.nextDouble() * 350;
                int tx = (int) Math.round(X + Math.cos(ang) * dist), tz = (int) Math.round(Z + Math.sin(ang) * dist);
                int tg = gen.surfaceY(tx, tz, true);
                if (ground == Integer.MIN_VALUE || tg == Integer.MIN_VALUE) {
                    p.sendMessage(say("The map here could not be made right now. Try again in a moment.", NamedTextColor.RED));
                    return;
                }
                // the squares and the chunks around the circle are made while you fall
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) store.prefetch(Math.floorDiv(tx, Tile.TILE) + dx, Math.floorDiv(tz, Tile.TILE) + dz);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    Jump j = new Jump();
                    j.p = p;
                    j.tx = tx + 0.5;
                    j.tz = tz + 0.5;
                    j.tGround = tg;
                    j.start = l.clone();
                    Location up = new Location(w, X + 0.5, ground + HEIGHT, Z + 0.5, l.getYaw(), 20);
                    p.setFallDistance(0);
                    p.teleport(up);
                    jumps.put(p.getUniqueId(), j);
                    for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) w.getChunkAtAsync((tx >> 4) + dx, (tz >> 4) + dz);
                    p.sendMessage(say("JUMP! W A S D steer, look down to fall faster, SPACE opens the parachute. Open it between 300 and 100 m, never under 100 m. Land on the circle!", NamedTextColor.GREEN));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("skydive: " + e);
                p.sendMessage(say("The jump could not start (" + e.getClass().getSimpleName() + ").", NamedTextColor.RED));
            }
        });
    }

    private void tick() {
        for (Jump j : new ArrayList<>(jumps.values())) {
            Player p = j.p;
            if (!p.isOnline() || p.isDead()) {
                end(j, null, false);
                continue;
            }
            j.ticks++;
            Location l = p.getLocation();
            int g = gen.surfaceY(l.getBlockX(), l.getBlockZ(), false);
            if (g == Integer.MIN_VALUE) g = j.tGround; // the square under you is not in memory: the circle's ground is close enough
            double agl = l.getY() - g;
            Input in = p.getCurrentInput();
            if (in.isJump() && !j.canopy && j.ticks > 20) {
                j.canopy = true;
                j.openedAt = j.ticks;
                j.openAgl = agl;
                p.sendMessage(say("Parachute!", NamedTextColor.AQUA));
            }
            double yaw = Math.toRadians(l.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            double f = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0), r = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
            double max = j.canopy ? 10 : 22, k = j.canopy ? 0.05 : 0.08;
            double ml = Math.hypot(f, r);
            double dvx = ml > 0 ? (fx * f + rx * r) / ml * max : 0, dvz = ml > 0 ? (fz * f + rz * r) / ml * max : 0;
            j.vx += (dvx - j.vx) * k;
            j.vz += (dvz - j.vz) * k;
            if (j.canopy) {
                j.vy += (-CANOPY_SINK - j.vy) * 0.06;
            } else {
                double term = l.getPitch() > 55 ? TERMINAL_DIVE : TERMINAL;
                j.vy = Math.max(-term, j.vy - 9.8 / 20);
            }
            p.setVelocity(new Vector(j.vx / 20, j.vy / 20, j.vz / 20));
            p.setFallDistance(0);
            if (j.ticks > 20 && (p.isOnGround() || agl < 0.8 || p.isInWater())) {
                land(j, l);
                continue;
            }
            if (j.ticks % 2 == 0) hud(j, l, agl);
            if (j.ticks % 8 == 0) ring(j);
        }
    }

    private void hud(Jump j, Location l, double agl) {
        double dx = j.tx - l.getX(), dz = j.tz - l.getZ(), dist = Math.hypot(dx, dz);
        double yawT = Math.toDegrees(Math.atan2(-dx, dz)), rel = ((yawT - l.getYaw() + 540) % 360) - 180;
        String arrow = Math.abs(rel) < 22 ? "↑" : Math.abs(rel) < 67 ? (rel > 0 ? "↗" : "↖") : Math.abs(rel) < 112 ? (rel > 0 ? "→" : "←") : Math.abs(rel) < 157 ? (rel > 0 ? "↘" : "↙") : "↓";
        String msg = String.format(Locale.ROOT, "%s  |  %d m above the ground  |  %d km/h  |  circle %d m %s", j.canopy ? "PARACHUTE" : "FREEFALL (SPACE = parachute)", Math.max(0, Math.round(agl)),
                Math.round(Math.abs(j.vy) * 3.6), Math.round(dist), arrow);
        NamedTextColor c = !j.canopy && agl < 300 ? NamedTextColor.RED : NamedTextColor.WHITE;
        j.p.sendActionBar(Component.text(msg, c));
    }

    /** the circle on the ground, drawn with light when you come near */
    private void ring(Jump j) {
        Player p = j.p;
        double y = j.tGround + 1.2;
        if (Math.hypot(p.getLocation().getX() - j.tx, p.getLocation().getZ() - j.tz) > 140) return;
        for (int i = 0; i < 24; i++) {
            double a = i / 24.0 * Math.PI * 2;
            p.spawnParticle(Particle.END_ROD, j.tx + Math.cos(a) * 10, y, j.tz + Math.sin(a) * 10, 1, 0, 0, 0, 0);
        }
        for (int h = 0; h < 40; h += 3) p.spawnParticle(Particle.END_ROD, j.tx, y + h, j.tz, 1, 0.1, 0, 0.1, 0);
    }

    private void land(Jump j, Location l) {
        Player p = j.p;
        double dist = Math.hypot(l.getX() - j.tx, l.getZ() - j.tz), speed = Math.abs(j.vy);
        boolean opened = j.canopy, tooLow = opened && j.openAgl < 100, hard = speed > 9;
        String msg;
        int score = 0;
        if (!opened) msg = "Splat! You never opened the parachute. No points. (Do not worry, nothing hurts in this game.)";
        else if (tooLow) msg = "You opened it too low (" + Math.round(j.openAgl) + " m). The canopy had no time to fill. No points.";
        else if (hard) msg = "A hard landing at " + Math.round(speed * 3.6) + " km/h. No points.";
        else {
            score = (int) Math.max(0, Math.round(100 - dist * 1.2)) + (dist < 5 ? 25 : 0);
            msg = String.format(Locale.ROOT, "Landed %d m from the circle: %d points%s.", Math.round(dist), score, dist < 5 ? " (bullseye!)" : "");
        }
        if (score > 0 && score > best.getOrDefault(p.getName(), 0)) {
            best.put(p.getName(), score);
            msg += " New best!";
        }
        end(j, msg + " /skydive jumps again, /skydive top shows the best scores.", false);
    }

    private void end(Jump j, String message, boolean backHome) {
        jumps.remove(j.p.getUniqueId());
        Player p = j.p;
        if (p.isOnline()) {
            p.setFallDistance(0);
            p.sendActionBar(Component.empty());
            if (backHome && j.start != null) p.teleport(j.start);
            if (message != null) p.sendMessage(say(message, NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Jump j = jumps.get(e.getPlayer().getUniqueId());
        if (j != null) {
            jumps.remove(e.getPlayer().getUniqueId());
            if (j.start != null) e.getPlayer().teleport(j.start); // nobody should log in at 1500 m
        }
    }
}
