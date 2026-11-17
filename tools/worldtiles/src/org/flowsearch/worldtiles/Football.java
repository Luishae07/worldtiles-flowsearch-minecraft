package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real football: the fixtures of the big leagues come from TheSportsDB, the stadium is found on the map, and at the real kick-off time
 * villagers play the match on the real pitch (found in OpenStreetMap) while villagers watch. The goals are made up (the same for everybody,
 * from the match number) because there is no free live score - the time, the teams and the stadium are real.
 */
public final class Football implements CommandExecutor {
    private static final String KEY = "123";
    private static final String[] LEAGUES = {"4331", "4328", "4335", "4332", "4334", "4480"}; // Bundesliga, Premier League, La Liga, Serie A, Ligue 1, Champions League
    private static final String TAG = "wt_football";
    private static final long MATCH_MS = 105 * 60 * 1000L; // 2 x 45 minutes and 15 minutes break

    private static final class Pitch {
        double cx, cz, hl, hw, ang; // centre, half length (along ang), half width
    }

    private static final class Fixture {
        String id, home, away, venue, league;
        long kickoff;
        boolean located, failed;
        double lat, lon;
        Pitch pitch;
        boolean permanent; // a match that is always played: when it ends, the next one starts
        final List<int[]> goals = new ArrayList<>(); // {minute, 0 home | 1 away}

        int minute(long now) { // the minute on the match clock
            long m = (now - kickoff) / 60000;
            return (int) (m < 45 ? m : m < 60 ? 45 : m - 15);
        }

        int[] score(int minute) {
            int h = 0, a = 0;
            for (int[] g : goals) if (g[0] <= minute) { if (g[1] == 0) h++; else a++; }
            return new int[]{h, a};
        }
    }

    private static final class Sim {
        Fixture f;
        World w;
        final Villager[] pl = new Villager[22];
        final double[] px = new double[22], pz = new double[22];
        final List<Villager> crowd = new ArrayList<>();
        BlockDisplay ball;
        TextDisplay board;
        double bx, bz, tx, tz, flightLeft; // ball and where it flies to
        int carrier = 10;
        long decideAt, kickAt;
        int announced; // goals announced
        int cheer;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Fixture> fixtures = new ConcurrentHashMap<>();
    private final Map<String, Long> venueFail = new ConcurrentHashMap<>();
    private final Map<String, Sim> sims = new HashMap<>();
    private final Random rnd = new Random();

    Football(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
    }

    // the famous stadiums, where a match is always being played: {home, away, stadium, latitude, longitude}
    private static final Object[][] ALWAYS = {
            {"Hertha BSC", "Union Berlin", "Olympiastadion Berlin", 52.5147, 13.2395},
            {"AS Roma", "Lazio", "Stadio Olimpico", 41.9341, 12.4547},
            {"Bayern Munich", "Borussia Dortmund", "Allianz Arena", 48.2188, 11.6247},
            {"Borussia Dortmund", "Schalke 04", "Signal Iduna Park", 51.4926, 7.4519},
            {"England", "Germany", "Wembley Stadium", 51.5560, -0.2795},
            {"Barcelona", "Real Madrid", "Camp Nou", 41.3809, 2.1228},
            {"Real Madrid", "Atletico Madrid", "Santiago Bernabeu", 40.4531, -3.6883},
            {"Inter Milan", "AC Milan", "San Siro", 45.4781, 9.1240},
            {"Manchester United", "Manchester City", "Old Trafford", 53.4631, -2.2913},
            {"Liverpool", "Everton", "Anfield", 53.4308, -2.9608},
            {"Paris Saint-Germain", "Marseille", "Parc des Princes", 48.8414, 2.2530},
            {"Flamengo", "Fluminense", "Maracana", -22.9122, -43.2302},
    };

    private static void makeGoals(Fixture f, long seed) {
        Random g = new Random(seed);
        int n = 0;
        double l = Math.exp(-2.7), p = 1;
        do {
            n++;
            p *= g.nextDouble();
        } while (p > l && n < 9);
        n--;
        f.goals.clear();
        for (int i = 0; i < n; i++) f.goals.add(new int[]{3 + g.nextInt(87), g.nextDouble() < 0.55 ? 0 : 1});
        f.goals.sort(Comparator.comparingInt(a -> a[0]));
    }

    private static long cycleStart(long now) {
        return now / MATCH_MS * MATCH_MS;
    }

    /** the matches that are always played: found on the map once, then a new one starts every time the last one has ended */
    private void setupAlways() throws InterruptedException {
        long now = System.currentTimeMillis();
        for (int i = 0; i < ALWAYS.length; i++) {
            Object[] a = ALWAYS[i];
            Fixture f = new Fixture();
            f.id = "always-" + i;
            f.home = (String) a[0];
            f.away = (String) a[1];
            f.venue = (String) a[2];
            f.league = "always playing";
            f.lat = (Double) a[3];
            f.lon = (Double) a[4];
            f.permanent = true;
            f.pitch = findPitch(f);
            gen.zones.add(new double[]{f.pitch.cx, f.pitch.cz, Math.cos(f.pitch.ang), Math.sin(f.pitch.ang), f.pitch.hl + 60, f.pitch.hw + 60});
            f.kickoff = cycleStart(now);
            makeGoals(f, f.id.hashCode() * 31L + f.kickoff / MATCH_MS);
            f.located = true;
            fixtures.put(f.id, f);
            Thread.sleep(1200);
        }
        plugin.getLogger().info("football: " + ALWAYS.length + " stadiums with a match that is always played");
    }

    /** the match of a stadium has ended: the next one starts (the same clubs, a new score) */
    private void nextMatch(Fixture f, long now) {
        f.kickoff = cycleStart(now);
        makeGoals(f, f.id.hashCode() * 31L + f.kickoff / MATCH_MS);
        Sim old = sims.remove(f.id);
        if (old != null) clear(old);
    }

    void start() {
        Thread t = new Thread(() -> {
            try {
                setupAlways();
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("football: the always played matches: " + e);
            }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    refresh();
                    Thread.sleep(3 * 3600_000L);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    plugin.getLogger().warning("football: " + e);
                    try {
                        Thread.sleep(600_000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "football-fixtures");
        t.setDaemon(true);
        t.start();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 2L);
    }

    void removeAll() {
        for (Sim s : sims.values()) clear(s);
        sims.clear();
        venues.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
    }

    // ---- the data ----

    private String get(String url) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "WorldTiles-minecraft-plugin").build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("status " + r.statusCode() + " for " + url.replaceAll("json/[^/]*/", "json/KEY/"));
        return r.body();
    }

    private static String js(String chunk, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(chunk);
        return m.find() ? m.group(1).replace("\\/", "/").replace("\\\"", "\"") : null;
    }

    private void refresh() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String id : LEAGUES) {
            try {
                bodies.add(get("https://www.thesportsdb.com/api/v1/json/" + KEY + "/eventsnextleague.php?id=" + id));
            } catch (Exception e) {
                plugin.getLogger().warning("football: league " + id + ": " + e.getMessage());
            }
            Thread.sleep(1500);
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int d = -1; d <= 1; d++) {
            try {
                bodies.add(get("https://www.thesportsdb.com/api/v1/json/" + KEY + "/eventsday.php?d=" + today.plusDays(d) + "&s=Soccer"));
            } catch (Exception e) {
                plugin.getLogger().warning("football: day: " + e.getMessage());
            }
            Thread.sleep(1500);
        }
        long now = System.currentTimeMillis();
        for (String body : bodies) {
            for (String chunk : body.split("\\{\"idEvent\"")) {
                String id = js("{\"idEvent\"" + chunk, "idEvent");
                String ts = js(chunk, "strTimestamp"), home = js(chunk, "strHomeTeam"), away = js(chunk, "strAwayTeam"), venue = js(chunk, "strVenue");
                if (id == null || ts == null || home == null || away == null || venue == null || venue.isBlank()) continue;
                if (fixtures.containsKey(id)) continue;
                long ko;
                try {
                    ko = LocalDateTime.parse(ts).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (Exception e) {
                    continue;
                }
                if (ko < now - MATCH_MS || ko > now + 7L * 86400_000L) continue;
                Fixture f = new Fixture();
                f.id = id;
                f.home = home;
                f.away = away;
                f.venue = venue;
                f.league = js(chunk, "strLeague");
                f.kickoff = ko;
                // the goals: the same for everybody, from the match number
                Random g = new Random(id.hashCode() * 31L + 7);
                int n = 0;
                double l = Math.exp(-2.7), p = 1;
                do {
                    n++;
                    p *= g.nextDouble();
                } while (p > l && n < 9);
                n--;
                for (int i = 0; i < n; i++) f.goals.add(new int[]{3 + g.nextInt(87), g.nextDouble() < 0.55 ? 0 : 1});
                f.goals.sort(Comparator.comparingInt(a -> a[0]));
                fixtures.put(id, f);
            }
        }
        for (Fixture f : fixtures.values()) {
            if (f.located || f.failed) continue;
            locate(f);
            Thread.sleep(1200);
        }
        fixtures.values().removeIf(f -> !f.permanent && f.kickoff < now - MATCH_MS - 3600_000L);
        plugin.getLogger().info("football: " + fixtures.size() + " fixtures, " + fixtures.values().stream().filter(f -> f.located).count() + " with a stadium");
    }

    private static final Pattern NOM = Pattern.compile("\"lat\":\"(-?[0-9.]+)\",\"lon\":\"(-?[0-9.]+)\"");
    private static final Pattern PAIR = Pattern.compile("\\[(-?[0-9]+\\.[0-9]+),(-?[0-9]+\\.[0-9]+)\\]");

    private void locate(Fixture f) {
        Long failed = venueFail.get(f.venue);
        if (failed != null && System.currentTimeMillis() - failed < 6 * 3600_000L) {
            f.failed = true;
            return;
        }
        try {
            String body = get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(f.venue + " stadium", StandardCharsets.UTF_8));
            Matcher m = NOM.matcher(body);
            if (!m.find()) {
                venueFail.put(f.venue, System.currentTimeMillis());
                f.failed = true;
                return;
            }
            f.lat = Double.parseDouble(m.group(1));
            f.lon = Double.parseDouble(m.group(2));
            f.pitch = findPitch(f);
            // the houses of the map are left out around the pitch (the stadium of the map is one big hollow house with a roof): the stands are built here instead
            gen.zones.add(new double[]{f.pitch.cx, f.pitch.cz, Math.cos(f.pitch.ang), Math.sin(f.pitch.ang), f.pitch.hl + 60, f.pitch.hw + 60});
            f.located = true;
        } catch (Exception e) {
            plugin.getLogger().warning("football: stadium " + f.venue + ": " + e.getMessage());
            venueFail.put(f.venue, System.currentTimeMillis());
            f.failed = true;
        }
    }

    /** the football pitch inside the stadium (an OpenStreetMap area), as the smallest rectangle around it; a standard pitch when there is none */
    private Pitch findPitch(Fixture f) {
        Pitch def = new Pitch();
        def.cx = Cmds.blockX(f.lon) + 0.5;
        def.cz = Cmds.blockZ(f.lat) + 0.5;
        def.hl = 52.5;
        def.hw = 34;
        def.ang = 0;
        try {
            String sql = String.format(Locale.ROOT, "SELECT 'pitch' AS kind, geom FROM postpass_polygon WHERE geom && ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326) AND tags->>'leisure' = 'pitch' AND tags->>'sport' = 'soccer' LIMIT 20",
                    f.lon - 0.003, f.lat - 0.002, f.lon + 0.003, f.lat + 0.002);
            HttpRequest rq = HttpRequest.newBuilder(URI.create("https://postpass.geofabrik.de/api/interpreter")).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "WorldTiles-minecraft-plugin")
                    .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(sql, StandardCharsets.UTF_8))).build();
            HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return def;
            Pitch best = null;
            double bd = Double.MAX_VALUE;
            for (String line : r.body().split("\n")) {
                int gi = line.indexOf("\"coordinates\":");
                if (gi < 0) continue;
                String geo = line.substring(gi);
                Matcher m = PAIR.matcher(geo);
                List<double[]> pts = new ArrayList<>();
                while (m.find()) {
                    pts.add(new double[]{(Double.parseDouble(m.group(1)) - Cmds.REF_LON) * Cmds.M_LON, (Cmds.REF_LAT - Double.parseDouble(m.group(2))) * Cmds.M_LAT});
                    if (m.end() < geo.length() && geo.charAt(m.end()) == ']') break; // the first ring
                }
                if (pts.size() < 4) continue;
                Pitch p = rectangle(pts);
                double area = p.hl * p.hw * 4;
                if (area < 2500 || area > 12000) continue;
                double d = Math.hypot(p.cx - def.cx, p.cz - def.cz);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
            return best != null ? best : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static Pitch rectangle(List<double[]> pts) {
        Pitch best = null;
        double ba = Double.MAX_VALUE;
        for (int a = 0; a < 180; a += 3) {
            double c = Math.cos(Math.toRadians(a)), s = Math.sin(Math.toRadians(a));
            double u0 = 1e18, u1 = -1e18, v0 = 1e18, v1 = -1e18;
            for (double[] p : pts) {
                double u = p[0] * c + p[1] * s, v = -p[0] * s + p[1] * c;
                u0 = Math.min(u0, u);
                u1 = Math.max(u1, u);
                v0 = Math.min(v0, v);
                v1 = Math.max(v1, v);
            }
            double area = (u1 - u0) * (v1 - v0);
            if (area < ba) {
                ba = area;
                Pitch p = new Pitch();
                double cu = (u0 + u1) / 2, cv = (v0 + v1) / 2;
                p.cx = cu * c - cv * s;
                p.cz = cu * s + cv * c;
                boolean uLong = (u1 - u0) >= (v1 - v0);
                p.hl = Math.max(u1 - u0, v1 - v0) / 2;
                p.hw = Math.min(u1 - u0, v1 - v0) / 2;
                p.ang = Math.toRadians(a) + (uLong ? 0 : Math.PI / 2);
                best = p;
            }
        }
        return best;
    }

    // ---- /football ----

    private static String when(long ms) {
        long m = ms / 60000;
        if (m < 60) return "in " + m + " min";
        if (m < 48 * 60) return "in " + m / 60 + " h " + m % 60 + " min";
        return "in " + m / 1440 + " days";
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        long now = System.currentTimeMillis();
        List<Fixture> list = new ArrayList<>();
        for (Fixture f : fixtures.values()) if (f.located && f.kickoff + MATCH_MS > now) list.add(f);
        list.sort(Comparator.comparingLong(f -> f.kickoff));
        if (args.length >= 2 && args[0].equalsIgnoreCase("go")) {
            try {
                Fixture f = list.get(Integer.parseInt(args[1]) - 1);
                World earth = null;
                for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
                Pitch pt = f.pitch;
                final World ew = earth;
                final double gx = pt.cx + Math.cos(pt.ang) * (pt.hl + 10), gz = pt.cz + Math.sin(pt.ang) * (pt.hl + 10);
                p.sendMessage(Component.text("Going to " + f.venue + "... (the ground there is being made, a few seconds)", NamedTextColor.GREEN));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int g = gen.surfaceY((int) Math.floor(gx), (int) Math.floor(gz), true); // waits for the square
                    double y = (g == Integer.MIN_VALUE ? TileGen.SEA + 10 : g) + 2;
                    Bukkit.getScheduler().runTask(plugin, () -> p.teleportAsync(new Location(ew, gx, y, gz)));
                });
            } catch (Exception e) {
                p.sendMessage(Component.text("/football go <number from the list>", NamedTextColor.YELLOW));
            }
            return true;
        }
        if (list.isEmpty()) {
            p.sendMessage(Component.text(fixtures.isEmpty() ? "The fixtures are still loading (they come from TheSportsDB, every 3 hours). Try again in a minute." : "No match in the big leagues with a known stadium in the next days.", NamedTextColor.YELLOW));
            return true;
        }
        p.sendMessage(Component.text("Football (the big leagues and Champions League). Real kick-off times, villagers play it. /football go <number> to the stadium:", NamedTextColor.GREEN));
        for (int i = 0; i < Math.min(20, list.size()); i++) {
            Fixture f = list.get(i);
            String st;
            if (f.kickoff <= now) {
                int m = f.minute(now);
                int[] sc = f.score(m);
                st = m >= 90 && now - f.kickoff > MATCH_MS - 60000 ? "finished " + sc[0] + "-" + sc[1] : "LIVE " + Math.min(90, m) + "' " + sc[0] + "-" + sc[1];
            } else st = when(f.kickoff - now);
            p.sendMessage(Component.text(String.format(Locale.ROOT, " %d. %s vs %s (%s) %s, %s", i + 1, f.home, f.away, f.league, st, f.venue), f.kickoff <= now ? NamedTextColor.GOLD : NamedTextColor.WHITE));
        }
        return true;
    }

    // ---- the match ----

    private Location at(Sim s, double lu, double lv) {
        Pitch p = s.f.pitch;
        double c = Math.cos(p.ang), sn = Math.sin(p.ang);
        double x = p.cx + lu * c - lv * sn, z = p.cz + lu * sn + lv * c;
        int[] cell = gen.cell((int) Math.floor(x), (int) Math.floor(z));
        return new Location(s.w, x, cell != null ? cell[0] + 1 : 70, z);
    }

    private double[] local(Sim s, double x, double z) {
        Pitch p = s.f.pitch;
        double c = Math.cos(p.ang), sn = Math.sin(p.ang), dx = x - p.cx, dz = z - p.cz;
        return new double[]{dx * c + dz * sn, -dx * sn + dz * c};
    }

    private double[] formation(int i, boolean home, Pitch p) {
        double[][] f = {{-0.92, 0}, {-0.55, -0.75}, {-0.55, -0.25}, {-0.55, 0.25}, {-0.55, 0.75}, {-0.12, -0.75}, {-0.12, -0.25}, {-0.12, 0.25}, {-0.12, 0.75}, {0.35, -0.3}, {0.35, 0.3}};
        double[] g = f[i % 11];
        double sign = home ? 1 : -1;
        return new double[]{g[0] * sign * p.hl, g[1] * sign * p.hw};
    }

    private void setup(Sim s) {
        World w = s.w;
        for (int i = 0; i < 22; i++) {
            boolean home = i < 11;
            double[] g = formation(i, home, s.f.pitch);
            Location l = at(s, g[0], g[1]);
            final int num = i % 11 + 1;
            s.pl[i] = w.spawn(l, Villager.class, v -> {
                v.setAI(false);
                v.setSilent(true);
                v.setInvulnerable(true);
                v.setPersistent(false);
                v.addScoreboardTag(TAG);
                v.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
                v.customName(Component.text((home ? s.f.home : s.f.away).split(" ")[0] + " " + num, home ? NamedTextColor.RED : NamedTextColor.AQUA));
                v.setCustomNameVisible(true);
            });
            s.px[i] = l.getX();
            s.pz[i] = l.getZ();
        }
        // the crowd: on the rows of the stands (the long sides, and behind the goals)
        Pitch p = s.f.pitch;
        for (int i = 0; i < 40; i++) {
            int k = rnd.nextInt(6);
            double sgn = rnd.nextBoolean() ? 1 : -1;
            double lu = (rnd.nextDouble() * 2 - 1) * p.hl, lv = sgn * (p.hw + 8 + 2 * k);
            if (i % 4 == 0) {
                lu = sgn * (p.hl + 10 + 2 * k);
                lv = (rnd.nextDouble() * 2 - 1) * p.hw * 0.8;
            }
            Location l = at(s, lu, lv);
            l.setY(l.getY() - 1 + 1.4 * (k + 1)); // the top of the row
            int[] c = gen.cell(l.getBlockX(), l.getBlockZ());
            if (c == null || c[1] > 0 || c[3] == 1) continue;
            Villager v = w.spawn(l, Villager.class, e -> {
                e.setAI(false);
                e.setSilent(true);
                e.setInvulnerable(true);
                e.setPersistent(false);
                e.addScoreboardTag(TAG);
                e.setProfession(Villager.Profession.values()[rnd.nextInt(Villager.Profession.values().length)]);
            });
            double dx = p.cx - l.getX(), dz = p.cz - l.getZ();
            v.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
            s.crowd.add(v);
        }
        Location c = at(s, 0, 0);
        s.bx = c.getX();
        s.bz = c.getZ();
        s.ball = w.spawn(c, BlockDisplay.class, e -> {
            e.setBlock(Material.WHITE_CONCRETE.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(-0.2f, 0, -0.2f), new Quaternionf(), new Vector3f(0.4f, 0.4f, 0.4f), new Quaternionf()));
        });
        s.board = w.spawn(c.clone().add(0, 14, 0), TextDisplay.class, e -> {
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBillboard(Display.Billboard.CENTER);
            e.setViewRange(3f);
        });
    }

    private void clear(Sim s) {
        for (Villager v : s.pl) if (v != null) v.remove();
        for (Villager v : s.crowd) v.remove();
        if (s.ball != null) s.ball.remove();
        if (s.board != null) s.board.remove();
    }

    // ---- the stadium: grass in stripes, white lines, goals, four stands with a roof, floodlights (all made of block displays, only while somebody is near) ----

    private final Map<String, List<BlockDisplay>> venues = new HashMap<>();

    private void box(List<BlockDisplay> out, World w, Pitch p, double gy, Material m, double lu, double y, double lv, double sx, double sy, double sz) {
        Quaternionf q = new Quaternionf().rotateY((float) -p.ang);
        Vector3f off = new Vector3f((float) (lu - sx / 2), (float) y, (float) (lv - sz / 2)).rotate(q);
        BlockDisplay b = w.spawn(new Location(w, p.cx, gy, p.cz), BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setPersistent(false);
            e.addScoreboardTag(TAG);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            e.setDisplayWidth(320f);
            e.setDisplayHeight(80f);
            e.setTransformation(new Transformation(off, q, new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        });
        out.add(b);
    }

    private void buildVenue(List<BlockDisplay> o, Fixture f, World w, double gy) {
        Pitch p = f.pitch;
        double hl = p.hl, hw = p.hw;
        int n = 12;
        for (int i = 0; i < n; i++) box(o, w, p, gy, i % 2 == 0 ? Material.GREEN_CONCRETE_POWDER : Material.GREEN_CONCRETE, -hl + (i + 0.5) * 2 * hl / n, 0.0, 0, 2 * hl / n, 0.04, 2 * hw);
        double t = 0.25, ly = 0.04, lh = 0.05;
        Material wl = Material.WHITE_CONCRETE;
        for (int s = -1; s <= 1; s += 2) {
            box(o, w, p, gy, wl, s * hl, ly, 0, t, lh, 2 * hw);      // goal lines
            box(o, w, p, gy, wl, 0, ly, s * hw, 2 * hl, lh, t);      // touch lines
            box(o, w, p, gy, wl, s * (hl - 16.5), ly, 0, t, lh, 40.32); // penalty area
            box(o, w, p, gy, wl, s * (hl - 8.25), ly, 20.16, 16.5, lh, t);
            box(o, w, p, gy, wl, s * (hl - 8.25), ly, -20.16, 16.5, lh, t);
            box(o, w, p, gy, wl, s * (hl - 5.5), ly, 0, t, lh, 18.32);  // goal area
            box(o, w, p, gy, wl, s * (hl - 2.75), ly, 9.16, 5.5, lh, t);
            box(o, w, p, gy, wl, s * (hl - 2.75), ly, -9.16, 5.5, lh, t);
        }
        box(o, w, p, gy, wl, 0, ly, 0, t, lh, 2 * hw); // half way line
        for (int i = 0; i < 28; i++) box(o, w, p, gy, wl, 9.15 * Math.cos(i * Math.PI / 14), ly, 9.15 * Math.sin(i * Math.PI / 14), 0.45, lh, 0.45); // the centre circle
        for (int s = -1; s <= 1; s += 2) { // the goals
            for (int q = -1; q <= 1; q += 2) box(o, w, p, gy, Material.WHITE_CONCRETE, s * hl, 0, q * 3.66, 0.18, 2.44, 0.18);
            box(o, w, p, gy, Material.WHITE_CONCRETE, s * hl, 2.3, 0, 0.18, 0.18, 7.5);
            box(o, w, p, gy, Material.WHITE_STAINED_GLASS, s * (hl + 1.1), 0, 0, 2.0, 2.4, 7.3);
        }
        Material[] rows = {Material.GRAY_CONCRETE, Material.RED_CONCRETE, Material.WHITE_CONCRETE, Material.RED_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE};
        for (int k = 0; k < 6; k++) {
            double h = 1.4 * (k + 1);
            for (int s = -1; s <= 1; s += 2) {
                box(o, w, p, gy, rows[k], 0, 0, s * (hw + 8 + 2 * k), 2 * (hl + 11 + 2 * k), h, 2);     // the long sides
                box(o, w, p, gy, rows[k], s * (hl + 10 + 2 * k), 0, 0, 2, h, 2 * (hw + 7 + 2 * k));     // behind the goals
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            box(o, w, p, gy, Material.LIGHT_GRAY_CONCRETE, 0, 11.8, s * (hw + 14), 2 * (hl + 14), 0.5, 14);   // the roofs
            box(o, w, p, gy, Material.LIGHT_GRAY_CONCRETE, s * (hl + 16), 11.8, 0, 14, 0.5, 2 * (hw + 7));
            for (int i = -2; i <= 2; i++) box(o, w, p, gy, Material.GRAY_CONCRETE, i * hl / 2.2, 0, s * (hw + 20.5), 0.7, 12, 0.7);
            for (int q = -1; q <= 1; q += 2) {
                double cu = s * (hl + 18), cv = q * (hw + 22);
                box(o, w, p, gy, Material.GRAY_CONCRETE, cu, 0, cv, 0.8, 26, 0.8);                           // floodlights
                box(o, w, p, gy, Material.SEA_LANTERN, cu, 26, cv, 1.6, 1.8, 5.5);
            }
        }
    }

    private void venue(Fixture f, World w, List<Player> players) {
        List<BlockDisplay> v = venues.get(f.id);
        double near = Double.MAX_VALUE;
        for (Player p : players) near = Math.min(near, Math.hypot(p.getLocation().getX() - f.pitch.cx, p.getLocation().getZ() - f.pitch.cz));
        if (v == null && near < 260) {
            int[] c = gen.cell((int) Math.floor(f.pitch.cx), (int) Math.floor(f.pitch.cz));
            if (c == null) return;
            v = new ArrayList<>();
            venues.put(f.id, v);
            buildVenue(v, f, w, c[0] + 1);
        } else if (v != null && near > 330) {
            for (BlockDisplay b : v) b.remove();
            venues.remove(f.id);
        }
    }

    private void tick() {
        World earth = null;
        for (World w : Bukkit.getWorlds()) if (w.getGenerator() == gen) earth = w;
        if (earth == null) return;
        long now = System.currentTimeMillis();
        List<Player> players = earth.getPlayers();
        for (Fixture f : fixtures.values()) {
            if (f.permanent && now >= f.kickoff + MATCH_MS) nextMatch(f, now);
            if (f.located) venue(f, earth, players);
            boolean live = f.located && f.kickoff <= now && now < f.kickoff + MATCH_MS;
            Sim s = sims.get(f.id);
            boolean near = false;
            if (live && !players.isEmpty()) for (Player p : players) if (Math.hypot(p.getLocation().getX() - f.pitch.cx, p.getLocation().getZ() - f.pitch.cz) < 150) near = true;
            if (s == null && near) {
                int[] c = gen.cell((int) f.pitch.cx, (int) f.pitch.cz);
                if (c == null) continue; // the ground is not made yet
                s = new Sim();
                s.f = f;
                s.w = earth;
                setup(s);
                sims.put(f.id, s);
            } else if (s != null && (!near || !live)) {
                clear(s);
                sims.remove(f.id);
                continue;
            }
            if (s != null) play(s, now, players);
        }
    }

    private void play(Sim s, long now, List<Player> players) {
        Fixture f = s.f;
        Pitch p = f.pitch;
        int minute = f.minute(now);
        int[] score = f.score(minute);
        boolean half = now - f.kickoff >= 45 * 60000L && now - f.kickoff < 60 * 60000L;
        // a scheduled goal: the team that scores shoots into the net
        int due = 0;
        for (int[] g : f.goals) if (g[0] <= minute) due++;
        if (due > s.announced && s.flightLeft <= 0 && !half) {
            int[] g = f.goals.get(s.announced);
            s.announced++;
            s.cheer = 60;
            double dir = g[1] == 0 ? 1 : -1;
            double[] t = at2(s, dir * p.hl, 0);
            s.tx = t[0];
            s.tz = t[1];
            s.flightLeft = Math.hypot(s.tx - s.bx, s.tz - s.bz);
            s.carrier = -1;
            String team = g[1] == 0 ? f.home : f.away;
            for (Player pl : players) {
                if (Math.hypot(pl.getLocation().getX() - p.cx, pl.getLocation().getZ() - p.cz) < 200) {
                    pl.showTitle(Title.title(Component.text("GOAL! " + team, NamedTextColor.GOLD), Component.text(f.home + " " + (g[1] == 0 ? score[0] : score[0]) + " - " + score[1] + " " + f.away + "  (" + g[0] + "')", NamedTextColor.WHITE)));
                }
            }
        }
        // the ball
        if (s.flightLeft > 0) {
            double d = Math.hypot(s.tx - s.bx, s.tz - s.bz), step = Math.min(d, 15 / 10.0);
            if (d > 0.01) {
                s.bx += (s.tx - s.bx) / d * step;
                s.bz += (s.tz - s.bz) / d * step;
            }
            s.flightLeft -= step;
            if (s.flightLeft <= 0.05) {
                s.flightLeft = 0;
                double[] lb = local(s, s.bx, s.bz);
                if (Math.abs(lb[0]) > p.hl - 1) { // it was a goal: kick-off by the other team from the centre
                    double[] c = at2(s, 0, 0);
                    s.bx = c[0];
                    s.bz = c[1];
                    s.carrier = lb[0] > 0 ? 15 : 5;
                } else {
                    s.carrier = nearest(s, s.bx, s.bz, -1);
                }
            }
        } else if (s.carrier >= 0) {
            double dx = s.bx - s.px[s.carrier], dz = s.bz - s.pz[s.carrier];
            // the ball is at the feet of the player with it
            s.bx = s.px[s.carrier] + 0.0 * dx;
            s.bz = s.pz[s.carrier];
            if (now >= s.decideAt && !half) {
                s.decideAt = now + 900 + rnd.nextInt(900);
                boolean home = s.carrier < 11;
                double[] lb = local(s, s.bx, s.bz);
                double goalU = (home ? 1 : -1) * p.hl;
                if (Math.abs(goalU - lb[0]) < 0.3 * p.hl && rnd.nextInt(3) == 0) { // a shot (it does not count: only the scheduled goals do), the keeper takes it
                    int keeper = home ? 11 : 0;
                    s.tx = s.px[keeper];
                    s.tz = s.pz[keeper];
                    s.flightLeft = Math.hypot(s.tx - s.bx, s.tz - s.bz);
                    s.carrier = -1;
                } else if (rnd.nextInt(10) < 6) { // a pass to a team mate
                    int mate = (home ? 0 : 11) + 1 + rnd.nextInt(10);
                    if (mate != s.carrier) {
                        s.tx = s.px[mate];
                        s.tz = s.pz[mate];
                        s.flightLeft = Math.hypot(s.tx - s.bx, s.tz - s.bz);
                        s.carrier = -1;
                    }
                } else if (rnd.nextInt(5) == 0) { // lost to the other team
                    int other = nearest(s, s.bx, s.bz, home ? 0 : 1);
                    if (other >= 0) s.carrier = other;
                }
            }
        } else {
            s.carrier = nearest(s, s.bx, s.bz, -1);
        }
        // the players: to their place, a part of the way to the ball; the one with the ball runs to the goal
        for (int i = 0; i < 22; i++) {
            boolean home = i < 11;
            double[] g = formation(i, home, p);
            double[] lb = local(s, s.bx, s.bz);
            double tu = g[0] + 0.45 * (lb[0] - g[0]), tv = g[1] + 0.45 * (lb[1] - g[1]);
            double speed = 0.30; // blocks per tick (6 per second)
            if (i == s.carrier) {
                tu = lb[0] + (home ? 1 : -1) * 6;
                tv = lb[1] * 0.9;
            } else if (half) {
                tu = g[0] * 0.8;
                tv = g[1] * 0.8;
            }
            if (i == 0 || i == 11) tu = Math.max(-p.hl + 2, Math.min(p.hl - 2, i == 0 ? Math.min(tu, -p.hl + 6) : Math.max(tu, p.hl - 6)));
            tu = Math.max(-p.hl + 1, Math.min(p.hl - 1, tu));
            tv = Math.max(-p.hw + 1, Math.min(p.hw - 1, tv));
            double[] tw = at2(s, tu, tv);
            double dx = tw[0] - s.px[i], dz = tw[1] - s.pz[i], d = Math.hypot(dx, dz);
            if (d > 0.2) {
                double st = Math.min(d, speed);
                s.px[i] += dx / d * st;
                s.pz[i] += dz / d * st;
            }
            int[] c = gen.cell((int) Math.floor(s.px[i]), (int) Math.floor(s.pz[i]));
            double y = c != null ? c[0] + 1 : s.pl[i].getY();
            if (s.cheer > 0) y += Math.abs(Math.sin((60 - s.cheer + i) * 0.5)) * 0.6; // they jump when there is a goal
            float yaw = d > 0.2 ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : s.pl[i].getYaw();
            s.pl[i].teleport(new Location(s.w, s.px[i], y, s.pz[i], yaw, 0));
        }
        if (s.cheer > 0) {
            s.cheer--;
            if (s.cheer % 10 == 0) for (Villager v : s.crowd) v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 2, 0), 2, 0.3, 0.2, 0.3);
        }
        int[] c = gen.cell((int) Math.floor(s.bx), (int) Math.floor(s.bz));
        double by = (c != null ? c[0] + 1 : 70) + (s.flightLeft > 0 ? Math.min(1.5, Math.sin(Math.min(1, s.flightLeft / 20) * Math.PI) * 1.5) : 0.2);
        s.ball.teleport(new Location(s.w, s.bx, by, s.bz));
        int shown = Math.min(90, minute);
        s.board.text(Component.text(String.format(Locale.ROOT, "⚽ %s %d - %d %s   %s  (%s)", f.home, score[0], score[1], f.away, half ? "half time" : shown + "'", f.league), NamedTextColor.WHITE));
        s.board.teleport(new Location(s.w, p.cx, (gen.cell((int) p.cx, (int) p.cz) != null ? gen.cell((int) p.cx, (int) p.cz)[0] : 70) + 14, p.cz));
    }

    private double[] at2(Sim s, double lu, double lv) {
        Pitch p = s.f.pitch;
        double c = Math.cos(p.ang), sn = Math.sin(p.ang);
        return new double[]{p.cx + lu * c - lv * sn, p.cz + lu * sn + lv * c};
    }

    /** the player nearest to a place: of the home team (0), of the away team (1) or of any (-1) */
    private int nearest(Sim s, double x, double z, int team) {
        int best = -1;
        double bd = 1e18;
        for (int i = 0; i < 22; i++) {
            if (team == 0 && i >= 11 || team == 1 && i < 11) continue;
            double d = Math.hypot(s.px[i] - x, s.pz[i] - z);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }
}
