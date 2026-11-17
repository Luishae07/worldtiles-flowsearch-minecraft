package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** /tpll, /go, /where, /spawn, /worldmap */
final class Cmds implements CommandExecutor {
    static final double REF_LON = 12.4534, REF_LAT = 41.9029, M_LAT = 110540.0;
    static final double M_LON = 111320 * Math.cos(REF_LAT * Math.PI / 180);

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final TileStore store;
    private final Weather weather;
    private final MoonLife moonLife;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private static final Pattern LAT = Pattern.compile("\"lat\"\\s*:\\s*\"(-?[0-9.]+)\""), LON = Pattern.compile("\"lon\"\\s*:\\s*\"(-?[0-9.]+)\""),
            NAME = Pattern.compile("\"display_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    Cmds(JavaPlugin plugin, TileGen gen, TileStore store, Weather weather, MoonLife moonLife) {
        this.plugin = plugin;
        this.gen = gen;
        this.store = store;
        this.weather = weather;
        this.moonLife = moonLife;
    }

    static int blockX(double lon) { return (int) Math.floor((lon - REF_LON) * M_LON); }
    static int blockZ(double lat) { return (int) Math.floor((REF_LAT - lat) * M_LAT); }
    static double lonOf(double x) { return REF_LON + x / M_LON; }
    static double latOf(double z) { return REF_LAT - z / M_LAT; }

    private static Component say(String s, NamedTextColor c) {
        return Component.text(s, c);
    }

    /** one teleport every 6 seconds for each player: every new square is made from OpenStreetMap data, and that is shared with everybody */
    private boolean cooling(Player p) {
        long now = System.currentTimeMillis();
        Long t = last.get(p.getUniqueId());
        if (t != null && now - t < 6000) {
            p.sendMessage(say("Wait a few seconds before the next teleport.", NamedTextColor.GRAY));
            return true;
        }
        last.put(p.getUniqueId(), now);
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            if (cmd.getName().equalsIgnoreCase("tp")) { // the normal /tp stays for the console
                Bukkit.dispatchCommand(sender, "minecraft:tp " + String.join(" ", args));
                return true;
            }
            sender.sendMessage("Only players can use this.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "datacenter" -> datacenter(p);
            case "tp" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("datacenter")) datacenter(p);
                else Bukkit.dispatchCommand(sender, "minecraft:tp " + String.join(" ", args)); // anything else is the normal /tp
            }
            case "tpll" -> tpll(p, args);
            case "go" -> go(p, args);
            case "where" -> where(p);
            case "spawn" -> {
                if (moonLife.isMoon(p.getWorld())) moonLife.goEarth(p);
                else {
                    p.teleportAsync(p.getWorld().getSpawnLocation());
                    p.sendMessage(say("Back at St. Peter's Square.", NamedTextColor.GREEN));
                }
            }
            case "moon" -> moonLife.goMoon(p, args);
            case "earth" -> moonLife.goEarth(p);
            case "worldmap" -> worldmap(p, args);
            case "fly" -> fly(p, args);
            case "realweather" -> p.sendMessage(say(weather.toggle(p) ? "Real weather and time are on." : "Real weather and time are off (you see the normal sky).", NamedTextColor.GREEN));
            default -> { return false; }
        }
        return true;
    }

    /** /tp datacenter: to the nearest data center (the tile service looks for it in the areas that are ready) */
    private void datacenter(Player p) {
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(say("Data centers are on Earth (/earth).", NamedTextColor.RED));
            return;
        }
        final double lat = latOf(p.getLocation().getZ()), lon = lonOf(p.getLocation().getX());
        p.sendMessage(say("Looking for the nearest data center...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String base = System.getProperty("worldtiles.url", "http://127.0.0.1:8858/tiles/3d/load").replace("/load", "/datacenter");
                HttpRequest rq = HttpRequest.newBuilder(URI.create(base)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"lat\":" + lat + ",\"lon\":" + lon + "}")).build();
                String body = http.send(rq, HttpResponse.BodyHandlers.ofString()).body();
                Matcher a = Pattern.compile("\"lat\":(-?[0-9.]+)").matcher(body), b = Pattern.compile("\"lon\":(-?[0-9.]+)").matcher(body), m2 = Pattern.compile("\"area\":([0-9.]+)").matcher(body);
                if (!body.contains("\"found\":true") || !a.find() || !b.find()) {
                    Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(say("No data center within about 15 km of you, in the areas that are ready. Go to a big city or an industrial area (/tpll 50.1109 8.6821 is Frankfurt) and try again.", NamedTextColor.YELLOW)));
                    return;
                }
                final double dlat = Double.parseDouble(a.group(1)), dlon = Double.parseDouble(b.group(1));
                final long area = m2.find() ? Math.round(Double.parseDouble(m2.group(1))) : 0;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    p.sendMessage(say("A data center of " + area + " m2 at /tpll " + String.format(Locale.ROOT, "%.5f %.5f", dlat, dlon) + ". Going there (you land on its roof). /house buy next to it.", NamedTextColor.GREEN));
                    teleportTo(p, dlat, dlon, null);
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(say("The search did not work (" + e.getClass().getSimpleName() + ").", NamedTextColor.RED)));
            }
        });
    }

    private void tpll(Player p, String[] args) {
        if (moonLife.isMoon(p.getWorld())) { // on the Moon the same idea works with the lunar map
            moonLife.goMoon(p, args);
            return;
        }
        String joined = String.join(" ", args).replace(',', ' ').trim();
        String[] parts = joined.isEmpty() ? new String[0] : joined.split("\\s+");
        double lat, lon;
        try {
            if (parts.length != 2) throw new NumberFormatException();
            lat = Double.parseDouble(parts[0]);
            lon = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            p.sendMessage(say("Use /tpll <latitude> <longitude>, for example /tpll 52.5163 13.3777 (the Brandenburg Gate in Berlin).", NamedTextColor.YELLOW));
            return;
        }
        if (cooling(p)) return;
        teleportTo(p, lat, lon, null);
    }

    private void go(Player p, String[] args) {
        String q = String.join(" ", args).trim();
        if (q.isEmpty()) {
            p.sendMessage(say("Use /go <place>, for example /go Eiffel Tower or /go Berlin Brandenburg Gate.", NamedTextColor.YELLOW));
            return;
        }
        if (cooling(p)) return;
        p.sendMessage(say("Looking for " + q + " ...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(12)).header("User-Agent", "flowsearch-worldtiles/1.0").build();
                String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
                Matcher a = LAT.matcher(body), b = LON.matcher(body), n = NAME.matcher(body);
                if (!a.find() || !b.find()) {
                    plugin.getLogger().info("/go " + q + ": the search found nothing");
                    p.sendMessage(say("I could not find \"" + q + "\".", NamedTextColor.RED));
                    return;
                }
                String name = n.find() ? n.group(1).replace("\\\"", "\"") : q;
                plugin.getLogger().info("/go " + q + " -> " + a.group(1) + ", " + b.group(1) + " (" + name + ")");
                teleportTo(p, Double.parseDouble(a.group(1)), Double.parseDouble(b.group(1)), name);
            } catch (Exception e) {
                plugin.getLogger().warning("/go " + q + " failed: " + e);
                p.sendMessage(say("The place search did not answer (" + e.getClass().getSimpleName() + "). Try /tpll with a latitude and longitude.", NamedTextColor.RED));
            }
        });
    }

    /** the square is made first when needed (this can take some seconds), then the player is moved to the top of the ground */
    private void teleportTo(Player p, double lat, double lon, String name) {
        if (lat < -84 || lat > 84 || lon < -180 || lon > 180) {
            p.sendMessage(say("That place is outside of the map (latitude -84 to 84).", NamedTextColor.RED));
            return;
        }
        p.sendMessage(say("Going to " + (name != null ? name : String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)) + " ... (a new square is built from OpenStreetMap the first time, a few seconds)", NamedTextColor.GRAY));
        int X = blockX(lon), Z = blockZ(lat);
        World w = p.getWorld();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long t0 = System.currentTimeMillis();
                // the squares around the place are asked for all at once; the game needs the ones next to it too
                int ctx = Math.floorDiv(X, Tile.TILE), ctz = Math.floorDiv(Z, Tile.TILE);
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) store.prefetch(ctx + dx, ctz + dz);
                int y = gen.surfaceY(X, Z, true);
                plugin.getLogger().info("teleport " + p.getName() + " to block " + X + "," + Z + ": ground at y=" + y + " after " + (System.currentTimeMillis() - t0) + " ms");
                if (y == Integer.MIN_VALUE) {
                    p.sendMessage(say("That square could not be made right now. Try again in a minute.", NamedTextColor.RED));
                    return;
                }
                Location to = new Location(w, X + 0.5, y + 1, Z + 0.5, p.getLocation().getYaw(), 0);
                p.setFallDistance(0);
                p.teleportAsync(to).whenComplete((ok, err) -> {
                    plugin.getLogger().info("teleport of " + p.getName() + " finished: " + (err != null ? err.toString() : ok) + " after " + (System.currentTimeMillis() - t0) + " ms");
                    p.sendMessage(say(err == null && Boolean.TRUE.equals(ok) ? "You are there." : "The teleport did not work.", err == null && Boolean.TRUE.equals(ok) ? NamedTextColor.GREEN : NamedTextColor.RED));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("teleport failed: " + e);
                p.sendMessage(say("The teleport failed (" + e.getClass().getSimpleName() + ").", NamedTextColor.RED));
            }
        });
    }

    /** /fly turns flying on or off; /fly 1 to 10 turns it on and sets the speed (the normal speed is about 2) */
    private void fly(Player p, String[] args) {
        if (args.length > 0) {
            try {
                int n = Math.max(1, Math.min(10, Integer.parseInt(args[0])));
                p.setFlySpeed(n / 10f);
                p.setAllowFlight(true);
                p.setFlying(true);
                p.sendMessage(say("Flying on, speed " + n + " (of 10). /fly turns it off.", NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                p.sendMessage(say("Use /fly to turn flying on or off, or /fly 1 to /fly 10 for the speed.", NamedTextColor.YELLOW));
            }
            return;
        }
        boolean on = !p.getAllowFlight();
        p.setAllowFlight(on);
        if (on) {
            p.setFlying(true);
        } else {
            p.setFlying(false);
        }
        p.sendMessage(say(on ? "Flying on. Double-tap jump to fly, /fly 1-10 sets the speed." : "Flying off.", on ? NamedTextColor.GREEN : NamedTextColor.GRAY));
    }

    private void where(Player p) {
        if (moonLife.isMoon(p.getWorld())) {
            p.sendMessage(say(moonLife.whereText(p), NamedTextColor.GREEN));
            return;
        }
        Location l = p.getLocation();
        double lat = latOf(l.getZ()), lon = lonOf(l.getX());
        String ll = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon);
        String url = String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=18/%.5f/%.5f", lat, lon, lat, lon);
        p.sendMessage(Component.text("You are at " + ll + "  ", NamedTextColor.GREEN)
                .append(Component.text("[open on the real map]", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))));
        String wx = weather.describe(lat, lon);
        p.sendMessage(say("Weather here: " + (wx != null ? wx : "asking ... try /where again in a few seconds"), NamedTextColor.AQUA));
    }

    private void worldmap(Player p, String[] args) {
        int zoom = 3; // 1..4: 2 m, 4 m, 8 m, 16 m per pixel -> 256 m, 512 m, 1 km, 2 km across
        if (args.length > 0) {
            try {
                zoom = Math.max(1, Math.min(4, Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
            }
        }
        MapView view = Bukkit.createMap(p.getWorld());
        view.getRenderers().forEach(view::removeRenderer);
        view.setScale(MapView.Scale.valueOf(new String[]{"CLOSE", "NORMAL", "FAR", "FARTHEST"}[zoom - 1]));
        view.setCenterX(p.getLocation().getBlockX());
        view.setCenterZ(p.getLocation().getBlockZ());
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);
        view.addRenderer(new MapR(store));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        p.getInventory().addItem(item);
        p.sendMessage(say("Here is a map of the real area around you. /worldmap 1 to 4 for closer or wider (3 shows about 1 km).", NamedTextColor.GREEN));
    }
}
