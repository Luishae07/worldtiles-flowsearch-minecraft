package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Everything about the Moon: going there and back, the low gravity, the black night sky. */
final class MoonLife implements Listener {
    static final String WORLD = "moon";
    private static final double EARTH_GRAVITY = 0.08, MOON_GRAVITY = 0.08 / 6;

    /** name -> latitude, east longitude */
    static final Map<String, double[]> PLACES = new LinkedHashMap<>();
    static {
        PLACES.put("apollo 11", new double[]{0.6741, 23.4730});
        PLACES.put("tranquility", new double[]{0.6741, 23.4730});
        PLACES.put("apollo 12", new double[]{-3.0128, -23.4219});
        PLACES.put("apollo 14", new double[]{-3.6453, -17.4714});
        PLACES.put("apollo 15", new double[]{26.1322, 3.6339});
        PLACES.put("apollo 16", new double[]{-8.9734, 15.5011});
        PLACES.put("apollo 17", new double[]{20.1908, 30.7717});
        PLACES.put("tycho", new double[]{-43.31, -11.36});
        PLACES.put("copernicus", new double[]{9.62, -20.08});
        PLACES.put("kepler", new double[]{8.1, -38.0});
        PLACES.put("aristarchus", new double[]{23.7, -47.4});
        PLACES.put("plato", new double[]{51.62, -9.38});
        PLACES.put("mare imbrium", new double[]{32.8, -15.6});
        PLACES.put("mare serenitatis", new double[]{28.0, 17.5});
        PLACES.put("south pole", new double[]{-89.9, 0});
        PLACES.put("shackleton", new double[]{-89.54, 129.78});
        PLACES.put("north pole", new double[]{89.9, 0});
        PLACES.put("tsiolkovskiy", new double[]{-20.4, 129.1});
        PLACES.put("mons huygens", new double[]{20.0, -2.9});
        PLACES.put("far side", new double[]{0, 180});
        PLACES.put("chang'e 4", new double[]{-45.457, 177.589});
    }

    private final JavaPlugin plugin;
    private final MoonGen moon;
    private final Weather weather;

    MoonLife(JavaPlugin plugin, MoonGen moon, Weather weather) {
        this.plugin = plugin;
        this.moon = moon;
        this.weather = weather;
    }

    boolean isMoon(World w) {
        return w != null && w.getGenerator() == moon;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (isMoon(p.getWorld())) apply(p);
        }, 40L, 100L);
    }

    /** low gravity (a sixth, so jumps go six times as high and a fall does not hurt), night vision for the sunlit ground, the black sky */
    void apply(Player p) {
        set(p, Attribute.GRAVITY, MOON_GRAVITY);
        set(p, Attribute.SAFE_FALL_DISTANCE, 80);
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        p.setPlayerTime(18000, false);
        p.setPlayerWeather(WeatherType.CLEAR);
    }

    void reset(Player p) {
        set(p, Attribute.GRAVITY, EARTH_GRAVITY);
        set(p, Attribute.SAFE_FALL_DISTANCE, 3);
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        p.resetPlayerTime();
        p.resetPlayerWeather();
        weather.forget(p);
    }

    private static void set(Player p, Attribute a, double v) {
        AttributeInstance i = p.getAttribute(a);
        if (i != null && i.getBaseValue() != v) i.setBaseValue(v);
    }

    @EventHandler
    public void onChanged(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (isMoon(p.getWorld())) apply(p);
        else reset(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (isMoon(p.getWorld())) apply(p);
        else reset(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = e.getPlayer();
            if (isMoon(p.getWorld())) apply(p);
            else reset(p);
        }, 5L);
    }

    private static Component say(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    /** /moon, /moon apollo 17, /moon -43.3 -11.4 */
    void goMoon(Player p, String[] args) {
        World w = Bukkit.getWorld(WORLD);
        if (w == null) {
            p.sendMessage(say("The Moon is not ready yet. Try again in a moment.", NamedTextColor.RED));
            return;
        }
        double lat, lon;
        String what;
        String q = String.join(" ", args).trim().toLowerCase(Locale.ROOT).replace(',', ' ');
        String[] parts = q.isEmpty() ? new String[0] : q.split("\\s+");
        if (parts.length == 2 && isNum(parts[0]) && isNum(parts[1])) {
            lat = Double.parseDouble(parts[0]);
            lon = Double.parseDouble(parts[1]);
            what = String.format(Locale.ROOT, "%.3f, %.3f", lat, lon);
        } else {
            String key = q.isEmpty() ? "apollo 11" : q;
            double[] ll = PLACES.get(key);
            if (ll == null) {
                for (Map.Entry<String, double[]> e : PLACES.entrySet()) {
                    if (e.getKey().contains(key)) {
                        ll = e.getValue();
                        key = e.getKey();
                        break;
                    }
                }
            }
            if (ll == null) {
                p.sendMessage(say("I do not know \"" + q + "\". Try: " + String.join(", ", new java.util.TreeSet<>(PLACES.keySet())) + " - or /moon <latitude> <longitude>.", NamedTextColor.YELLOW));
                return;
            }
            lat = ll[0];
            lon = ll[1];
            what = key;
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 360) {
            p.sendMessage(say("Latitude -90 to 90, longitude -180 to 180 (east is positive).", NamedTextColor.RED));
            return;
        }
        if (lon > 180) lon -= 360;
        int X = MoonGen.blockX(lon), Z = MoonGen.blockZ(lat);
        Location to = new Location(w, X + 0.5, moon.heightY(X, Z) + 1, Z + 0.5, p.getLocation().getYaw(), 0);
        p.setFallDistance(0);
        p.teleportAsync(to).thenAccept(ok -> p.sendMessage(say(ok ? "On the Moon: " + what + ". Low gravity: jump! /earth takes you home." : "The teleport did not work.", ok ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    void goEarth(Player p) {
        World w = Bukkit.getWorld("world");
        if (w == null) return;
        p.setFallDistance(0);
        p.teleportAsync(w.getSpawnLocation()).thenAccept(ok -> p.sendMessage(say("Back on Earth, at St. Peter's Square.", NamedTextColor.GREEN)));
    }

    String whereText(Player p) {
        Location l = p.getLocation();
        double lat = MoonGen.latOf(l.getZ()), lon = MoonGen.lonOf(l.getX());
        return String.format(Locale.ROOT, "You are on the Moon at %.3f%s, %.3f%s, %d m above the reference.", Math.abs(lat), lat >= 0 ? " N" : " S", Math.abs(lon), lon >= 0 ? " E" : " W",
                Math.round((l.getY() - MoonGen.BASE) * MoonGen.V));
    }

    private static boolean isNum(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
