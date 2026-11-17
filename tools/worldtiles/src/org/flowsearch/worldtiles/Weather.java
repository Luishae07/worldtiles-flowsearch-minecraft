package org.flowsearch.worldtiles;

import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real weather and the real time of day at the place where each player stands. Weather and time are set for each player separately,
 * so somebody in Berlin and somebody in Sydney each see their own sky. The weather comes from Tomorrow.io (the key is in tomorrow.key,
 * 25 questions an hour are free, so every area of 0.5 degrees is asked for once in 30 minutes), and from Open-Meteo (no key) when that is used up.
 */
final class Weather {
    private record Cond(int code, double temp, boolean wet, String name, String source, long at) {}

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<String, Cond> cache = new ConcurrentHashMap<>();
    private final Set<String> asking = ConcurrentHashMap.newKeySet();
    private final Set<UUID> off = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> shownWet = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> tomorrowCalls = new ArrayDeque<>();
    private String key = "";

    private static final long FRESH_MS = 15 * 60_000L;
    private static final int TOMORROW_PER_HOUR = 20;
    private static final Pattern T_CODE = Pattern.compile("\"weatherCode\"\\s*:\\s*(\\d+)"), T_TEMP = Pattern.compile("\"temperature\"\\s*:\\s*(-?[0-9.]+)");
    private static final Pattern M_CODE = Pattern.compile("\"weather_code\"\\s*:\\s*(\\d+)"), M_TEMP = Pattern.compile("\"temperature_2m\"\\s*:\\s*(-?[0-9.]+)");

    Weather(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
    }

    void start() {
        try {
            key = Files.readString(Path.of("tomorrow.key")).trim();
        } catch (Exception e) {
            plugin.getLogger().info("Weather: no tomorrow.key, only Open-Meteo is used");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 20L * 4L);
        plugin.getLogger().info("Weather: the real weather and time for every player" + (key.isEmpty() ? "" : " (Tomorrow.io key found)"));
    }

    /** after the player has been somewhere else (on the Moon), the weather is set again */
    void forget(Player p) {
        shownWet.remove(p.getUniqueId());
    }

    boolean toggle(Player p) {
        boolean nowOn;
        if (off.remove(p.getUniqueId())) {
            nowOn = true;
        } else {
            off.add(p.getUniqueId());
            nowOn = false;
            p.resetPlayerWeather();
            p.resetPlayerTime();
            shownWet.remove(p.getUniqueId());
        }
        return nowOn;
    }

    private static String cellKey(double lat, double lon) {
        return String.format(Locale.ROOT, "%.1f,%.1f", Math.round(lat * 10) / 10.0, Math.round(lon * 10) / 10.0); // 0.1 degrees: about 10 km
    }

    /** a short text about the weather at a place, or null when it is not known yet */
    String describe(double lat, double lon) {
        Cond c = cache.get(cellKey(lat, lon));
        if (c == null) {
            ask(lat, lon);
            return null;
        }
        return c.name + ", " + Math.round(c.temp) + " °C (" + c.source + ")";
    }

    /** the game's day: 0 is 6:00 in the morning, 6000 noon, 12000 sunset, 18000 midnight. Real sun time of the place (from the longitude). */
    private static long ticksAt(double lon) {
        ZonedDateTime utc = ZonedDateTime.now(ZoneOffset.UTC);
        double hours = utc.getHour() + utc.getMinute() / 60.0 + utc.getSecond() / 3600.0 + lon / 15.0;
        hours = ((hours % 24) + 24) % 24;
        return (long) (((hours - 6 + 24) % 24) * 1000);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getGenerator() != gen || off.contains(p.getUniqueId())) continue;
            double lat = Cmds.latOf(p.getLocation().getZ()), lon = Cmds.lonOf(p.getLocation().getX());
            p.setPlayerTime(ticksAt(lon), false);
            Cond c = cache.get(cellKey(lat, lon));
            if (c == null || System.currentTimeMillis() - c.at > FRESH_MS) ask(lat, lon);
            if (c != null) {
                Boolean was = shownWet.get(p.getUniqueId());
                if (was == null || was != c.wet) {
                    p.setPlayerWeather(c.wet ? WeatherType.DOWNFALL : WeatherType.CLEAR);
                    shownWet.put(p.getUniqueId(), c.wet);
                    plugin.getLogger().info("weather for " + p.getName() + " at " + String.format(Locale.ROOT, "%.2f, %.2f", lat, lon) + ": " + c.name + " (" + c.source + ") -> " + (c.wet ? "rain" : "clear"));
                }
            }
        }
    }

    private synchronized boolean tomorrowBudget() {
        long now = System.currentTimeMillis();
        while (!tomorrowCalls.isEmpty() && now - tomorrowCalls.peekFirst() > 3_600_000L) tomorrowCalls.pollFirst();
        if (key.isEmpty() || tomorrowCalls.size() >= TOMORROW_PER_HOUR) return false;
        tomorrowCalls.addLast(now);
        return true;
    }

    private void ask(double lat, double lon) {
        String k = cellKey(lat, lon);
        if (!asking.add(k)) return;
        String[] ll = k.split(",");
        double qlat = Double.parseDouble(ll[0]), qlon = Double.parseDouble(ll[1]);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Cond c = null;
                if (tomorrowBudget()) c = fromTomorrow(qlat, qlon);
                if (c == null) c = fromOpenMeteo(qlat, qlon);
                if (c != null) cache.put(k, c);
            } catch (Exception e) {
                plugin.getLogger().warning("weather for " + k + ": " + e);
            } finally {
                asking.remove(k);
            }
        });
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "flowsearch-worldtiles/1.0").header("accept", "application/json").build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("status " + r.statusCode());
        return r.body();
    }

    private Cond fromTomorrow(double lat, double lon) {
        try {
            String body = get("https://api.tomorrow.io/v4/weather/realtime?units=metric&location=" + lat + "," + lon + "&apikey=" + key);
            Matcher a = T_CODE.matcher(body), b = T_TEMP.matcher(body);
            if (!a.find()) return null;
            int code = Integer.parseInt(a.group(1));
            double temp = b.find() ? Double.parseDouble(b.group(1)) : 0;
            boolean wet = switch (code) {
                case 4000, 4001, 4200, 4201, 5000, 5001, 5100, 5101, 6000, 6001, 6200, 6201, 7000, 7101, 7102, 8000 -> true;
                default -> false;
            };
            return new Cond(code, temp, wet, tomorrowName(code), "Tomorrow.io", System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().warning("Tomorrow.io: " + e.getMessage() + " (Open-Meteo is used)");
            return null;
        }
    }

    private Cond fromOpenMeteo(double lat, double lon) {
        try {
            String body = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude=" + lat + "&longitude=" + lon);
            Matcher a = M_CODE.matcher(body), b = M_TEMP.matcher(body);
            if (!a.find()) return null;
            int code = Integer.parseInt(a.group(1));
            double temp = b.find() ? Double.parseDouble(b.group(1)) : 0;
            boolean wet = (code >= 51 && code <= 67) || (code >= 71 && code <= 77) || (code >= 80 && code <= 86) || code >= 95;
            return new Cond(code, temp, wet, meteoName(code), "Open-Meteo", System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().warning("Open-Meteo: " + e.getMessage());
            return null;
        }
    }

    private static String tomorrowName(int c) {
        return switch (c) {
            case 1000 -> "clear";
            case 1100 -> "mostly clear";
            case 1101 -> "partly cloudy";
            case 1102 -> "mostly cloudy";
            case 1001 -> "cloudy";
            case 2000, 2100 -> "fog";
            case 4000 -> "drizzle";
            case 4001 -> "rain";
            case 4200 -> "light rain";
            case 4201 -> "heavy rain";
            case 5000 -> "snow";
            case 5001 -> "flurries";
            case 5100 -> "light snow";
            case 5101 -> "heavy snow";
            case 6000, 6001, 6200, 6201 -> "freezing rain";
            case 7000, 7101, 7102 -> "ice pellets";
            case 8000 -> "thunderstorm";
            default -> "weather code " + c;
        };
    }

    private static String meteoName(int c) {
        if (c == 0) return "clear";
        if (c <= 2) return "partly cloudy";
        if (c == 3) return "overcast";
        if (c == 45 || c == 48) return "fog";
        if (c >= 51 && c <= 57) return "drizzle";
        if (c >= 61 && c <= 67) return "rain";
        if (c >= 71 && c <= 77) return "snow";
        if (c >= 80 && c <= 82) return "rain showers";
        if (c == 85 || c == 86) return "snow showers";
        if (c >= 95) return "thunderstorm";
        return "weather code " + c;
    }
}
