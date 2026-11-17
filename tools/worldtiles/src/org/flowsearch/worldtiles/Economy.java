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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Money of the players (kept in a small file), and /money. */
public final class Economy implements CommandExecutor {
    private final File file;
    private final Map<UUID, Long> money = new HashMap<>();

    Economy(JavaPlugin plugin) {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "money.txt");
        try {
            if (file.exists()) for (String line : Files.readAllLines(file.toPath())) {
                String[] p = line.split("=");
                if (p.length == 2) money.put(UUID.fromString(p[0]), Long.parseLong(p[1]));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("money.txt could not be read: " + e);
        }
        loadLicences(plugin);
    }

    private final Map<UUID, java.util.Set<String>> licences = new HashMap<>();
    private File licFile;

    private void loadLicences(JavaPlugin plugin) {
        licFile = new File(plugin.getDataFolder(), "licences.txt");
        try {
            if (licFile.exists()) for (String line : Files.readAllLines(licFile.toPath())) {
                String[] p = line.split("=");
                if (p.length == 2) licences.computeIfAbsent(UUID.fromString(p[0]), k -> new java.util.HashSet<>()).addAll(java.util.Arrays.asList(p[1].split(",")));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("licences.txt could not be read: " + e);
        }
    }

    synchronized boolean hasLicence(UUID id, String what) {
        return licences.getOrDefault(id, java.util.Set.of()).contains(what);
    }

    private synchronized void giveLicence(UUID id, String what) {
        licences.computeIfAbsent(id, k -> new java.util.HashSet<>()).add(what);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, java.util.Set<String>> e : licences.entrySet()) sb.append(e.getKey()).append('=').append(String.join(",", e.getValue())).append('\n');
        try {
            Files.writeString(licFile.toPath(), sb.toString());
        } catch (Exception ignored) {
        }
    }

    static final Map<String, Integer> LICENCE_PRICE = Map.of("train", 300);

    synchronized long get(UUID id) {
        return money.getOrDefault(id, 0L);
    }

    synchronized void add(UUID id, long n) {
        money.merge(id, n, Long::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Long> e : money.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            Files.writeString(file.toPath(), sb.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equals("licence")) {
            if (!(s instanceof Player p)) return true;
            if (a.length >= 2 && a[0].equalsIgnoreCase("buy") && LICENCE_PRICE.containsKey(a[1].toLowerCase())) {
                String what = a[1].toLowerCase();
                int price = LICENCE_PRICE.get(what);
                if (hasLicence(p.getUniqueId(), what)) p.sendMessage(Component.text("You already have the " + what + " licence.", NamedTextColor.YELLOW));
                else if (get(p.getUniqueId()) < price) p.sendMessage(Component.text("The " + what + " licence costs " + price + " coins. You have " + get(p.getUniqueId()) + ".", NamedTextColor.RED));
                else {
                    add(p.getUniqueId(), -price);
                    giveLicence(p.getUniqueId(), what);
                    p.sendMessage(Component.text("You bought the " + what + " licence! Now: /train drive next to a train.", NamedTextColor.GOLD));
                }
                return true;
            }
            p.sendMessage(Component.text("Licences: train (" + LICENCE_PRICE.get("train") + " coins, drive a real train: /licence buy train). You have: "
                    + (licences.getOrDefault(p.getUniqueId(), java.util.Set.of()).isEmpty() ? "none" : String.join(", ", licences.get(p.getUniqueId()))), NamedTextColor.GREEN));
            return true;
        }
        if (s instanceof Player p) p.sendMessage(Component.text("You have " + get(p.getUniqueId()) + " coins. Earn more with /car and /job.", NamedTextColor.GOLD));
        return true;
    }
}
