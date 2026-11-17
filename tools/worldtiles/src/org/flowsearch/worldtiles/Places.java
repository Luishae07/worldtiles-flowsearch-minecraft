package org.flowsearch.worldtiles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real shops, cafes, pharmacies and transport stops (from OpenStreetMap, through Postpass) as labels in the world, with their real
 * opening hours: green = open now (and until when), red = closed (and when it opens). /near lists them.
 */
public final class Places implements CommandExecutor {
    private static final String API = "https://postpass.geofabrik.de/api/interpreter";
    private static final double CELL = 0.004; // degrees, about 440 x 280 m
    private static final double SHOW = 55;    // blocks

    static volatile Shops shops;

    static final class Poi {
        String name, kind, oh, stars;
        String realName; // the name on the map: only used to know a place again (the game shows the generic name)
        boolean stop;
        double lat, lon;
        int x, z;
    }

    private static final class Cell {
        List<Poi> pois = new ArrayList<>();
        long failedAt;
        boolean done;
    }

    private final JavaPlugin plugin;
    private final TileGen gen;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "places-fetch");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Map<Poi, TextDisplay>> labels = new HashMap<>();

    Places(JavaPlugin plugin, TileGen gen) {
        this.plugin = plugin;
        this.gen = gen;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 40L);
    }

    void removeAll() {
        for (Map<Poi, TextDisplay> m : labels.values()) for (TextDisplay t : m.values()) t.remove();
        labels.clear();
        worker.shutdownNow();
    }

    private static long key(int cy, int cx) {
        return ((long) cy << 32) ^ (cx & 0xFFFFFFFFL);
    }

    // ---- the data ----

    private void want(double lat, double lon) {
        int cy = (int) Math.floor(lat / CELL), cx = (int) Math.floor(lon / CELL);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                long k = key(cy + dy, cx + dx);
                Cell c = cells.get(k);
                if (c != null && (c.done || System.currentTimeMillis() - c.failedAt < 60000)) continue;
                if (!queued.add(k)) continue;
                final int fy = cy + dy, fx = cx + dx;
                worker.submit(() -> {
                    try {
                        fetch(k, fy, fx);
                        Thread.sleep(1200); // one request at a time, not faster than this
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        queued.remove(k);
                    }
                });
            }
        }
    }

    private static final String SHOP_WHERE = "tags ? 'name' AND (tags ? 'shop' OR tags->>'amenity' IN ('cafe','restaurant','fast_food','bar','pub','pharmacy','bank','post_office','cinema','doctors','library','theatre','ice_cream','dentist','marketplace'))";

    private void fetch(long k, int cy, int cx) {
        double la0 = cy * CELL, la1 = la0 + CELL, lo0 = cx * CELL, lo1 = lo0 + CELL;
        String box = String.format(Locale.ROOT, "geom && ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326)", lo0, la0, lo1, la1);
        String shops = "SELECT tags->>'name' AS name, COALESCE(tags->>'shop', tags->>'amenity') AS kind, tags->>'opening_hours' AS oh, geom FROM postpass_point WHERE " + box + " AND " + SHOP_WHERE
                + " UNION ALL SELECT tags->>'name', COALESCE(tags->>'shop', tags->>'amenity'), tags->>'opening_hours', ST_PointOnSurface(geom) FROM postpass_polygon WHERE " + box + " AND " + SHOP_WHERE + " LIMIT 400";
        String stops = "SELECT tags->>'name' AS name, CASE WHEN tags->>'highway'='bus_stop' THEN 'bus' WHEN tags->>'railway'='tram_stop' THEN 'tram' WHEN tags->>'railway'='subway_entrance' THEN 'subway' ELSE 'train' END AS kind, geom FROM postpass_point WHERE "
                + box + " AND tags ? 'name' AND (tags->>'railway' IN ('station','tram_stop','halt','subway_entrance') OR tags->>'highway'='bus_stop') LIMIT 300";
        Cell c = new Cell();
        try {
            parse(query(shops), c.pois, false);
            Thread.sleep(1200);
            parse(query(stops), c.pois, true);
            Thread.sleep(1200);
            String hotels = "SELECT tags->>'name' AS name, tags->>'tourism' AS kind, tags->>'stars' AS stars, geom FROM postpass_point WHERE " + box + " AND tags ? 'name' AND tags->>'tourism' IN ('hotel','hostel','guest_house','motel','apartment')"
                    + " UNION ALL SELECT tags->>'name', tags->>'tourism', tags->>'stars', ST_PointOnSurface(geom) FROM postpass_polygon WHERE " + box + " AND tags ? 'name' AND tags->>'tourism' IN ('hotel','hostel','guest_house','motel','apartment') LIMIT 200";
            List<Poi> hs = new ArrayList<>();
            parse(query(hotels), hs, false);
            c.pois.addAll(hs);
            // one stop with the same name next to another is one stop for the player
            Set<String> seen = new HashSet<>();
            c.pois.removeIf(p -> p.stop && !seen.add(p.name + "|" + Math.round(p.lat * 2000) + "|" + Math.round(p.lon * 2000)));
            c.done = true;
        } catch (Exception e) {
            c.failedAt = System.currentTimeMillis();
            plugin.getLogger().warning("places: " + e);
        }
        cells.put(k, c);
    }

    private String query(String sql) throws Exception {
        HttpRequest rq = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "WorldTiles-minecraft-plugin")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(sql, StandardCharsets.UTF_8))).build();
        HttpResponse<String> r = http.send(rq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Postpass status " + r.statusCode());
        return r.body();
    }

    private static final Pattern FEATURE = Pattern.compile("\"coordinates\":\\[(-?[0-9.]+),(-?[0-9.]+)\\]\\}, \"properties\": \\{(.*)\\}\\}");
    private static final Pattern PROP = Pattern.compile("\"(\\w+)\": (null|\"((?:[^\"\\\\]|\\\\.)*)\")");

    private static void parse(String body, List<Poi> out, boolean stops) {
        for (String line : body.split("\n")) {
            Matcher m = FEATURE.matcher(line);
            if (!m.find()) continue;
            Poi p = new Poi();
            p.lon = Double.parseDouble(m.group(1));
            p.lat = Double.parseDouble(m.group(2));
            Matcher pm = PROP.matcher(m.group(3));
            while (pm.find()) {
                String v = pm.group(3) == null ? null : pm.group(3).replace("\\\"", "\"").replace("\\\\", "\\");
                switch (pm.group(1)) {
                    case "name" -> p.name = v;
                    case "kind" -> p.kind = v;
                    case "oh" -> p.oh = v;
                    case "stars" -> p.stars = v;
                    default -> {
                    }
                }
            }
            if (p.name == null || p.name.isBlank()) continue;
            p.stop = stops;
            p.realName = p.name;
            if (!stops) p.name = generic(p); // no real business names in the game
            p.x = Cmds.blockX(p.lon);
            p.z = Cmds.blockZ(p.lat);
            out.add(p);
        }
    }

    private List<Poi> around(double lat, double lon) {
        List<Poi> r = new ArrayList<>();
        int cy = (int) Math.floor(lat / CELL), cx = (int) Math.floor(lon / CELL);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                Cell c = cells.get(key(cy + dy, cx + dx));
                if (c != null) r.addAll(c.pois);
            }
        }
        Houses h = houses;
        if (h != null) r.addAll(h.pois(lat, lon)); // the shops that players opened in their houses
        return r;
    }

    static volatile Houses houses;

    /** all shops (not stops) within r blocks of a place */
    List<Poi> shopsNear(double x, double z, double r) {
        List<Poi> out = new ArrayList<>();
        for (Poi q : around(Cmds.latOf(z), Cmds.lonOf(x))) if (!q.stop && !isHotel(q.kind) && Math.hypot(q.x - x, q.z - z) <= r) out.add(q);
        return out;
    }

    // ---- opening hours ----

    private static final String[] DAYS = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    private static int dayIndex(String s) {
        for (int i = 0; i < 7; i++) if (DAYS[i].equalsIgnoreCase(s)) return i;
        return -1;
    }

    private static int mins(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + (p.length > 1 ? Integer.parseInt(p[1]) : 0);
    }

    /** "open until 19:00", "closed, opens 10:30" or null when the hours cannot be read; day 0 = Monday */
    static String[] hours(String oh, int day, int now) {
        if (oh == null || oh.isBlank()) return null;
        try {
            List<int[]>[] per = new List[7], carry = new List[7];
            for (int i = 0; i < 7; i++) {
                per[i] = new ArrayList<>();
                carry[i] = new ArrayList<>();
            }
            for (String rule : oh.split(";")) {
                rule = rule.trim();
                if (rule.isEmpty()) continue;
                if (rule.equalsIgnoreCase("24/7")) {
                    for (int i = 0; i < 7; i++) {
                        per[i].clear();
                        per[i].add(new int[]{0, 1440});
                    }
                    continue;
                }
                int di = 0;
                while (di < rule.length() && !Character.isDigit(rule.charAt(di))) di++;
                String dayPart = rule.substring(0, di).trim(), timePart = rule.substring(di).trim();
                boolean off = false;
                if (timePart.isEmpty()) {
                    String low = dayPart.toLowerCase(Locale.ROOT);
                    if (!low.contains("off") && !low.contains("closed")) throw new IllegalArgumentException(rule);
                    off = true;
                    dayPart = dayPart.replaceAll("(?i)off|closed", "").trim();
                }
                boolean[] days = new boolean[7];
                if (dayPart.isEmpty()) java.util.Arrays.fill(days, true);
                else {
                    for (String tok : dayPart.split(",")) {
                        tok = tok.trim();
                        if (tok.isEmpty() || tok.startsWith("PH") || tok.startsWith("SH")) continue;
                        String[] ab = tok.split("-");
                        int a = dayIndex(ab[0].trim());
                        int b = ab.length > 1 ? dayIndex(ab[1].trim()) : a;
                        if (a < 0 || b < 0) throw new IllegalArgumentException(tok);
                        for (int d = a; ; d = (d + 1) % 7) {
                            days[d] = true;
                            if (d == b) break;
                        }
                    }
                }
                List<int[]> iv = new ArrayList<>(), cv = new ArrayList<>();
                if (!off) {
                    for (String t : timePart.split(",")) {
                        String[] ab = t.trim().split("-");
                        int s = mins(ab[0]), e = mins(ab[1].replaceAll("[^0-9:].*$", ""));
                        if (e <= s) {
                            iv.add(new int[]{s, 1440});
                            cv.add(new int[]{0, e});
                        } else iv.add(new int[]{s, e});
                    }
                }
                for (int d = 0; d < 7; d++) {
                    if (!days[d]) continue;
                    per[d].clear();
                    per[d].addAll(iv);
                    carry[(d + 1) % 7].clear();
                    carry[(d + 1) % 7].addAll(cv);
                }
            }
            List<int[]> today = new ArrayList<>(per[day]);
            today.addAll(carry[day]);
            for (int[] iv : today) {
                if (now >= iv[0] && now < iv[1]) return new String[]{"open", "open until " + fmt(iv[1] % 1440 == 0 && iv[1] > 0 ? 0 : iv[1])};
            }
            int next = Integer.MAX_VALUE;
            for (int[] iv : today) if (iv[0] > now) next = Math.min(next, iv[0]);
            if (next != Integer.MAX_VALUE) return new String[]{"closed", "closed, opens " + fmt(next)};
            for (int k = 1; k <= 7; k++) {
                int d = (day + k) % 7;
                int first = Integer.MAX_VALUE;
                for (int[] iv : per[d]) first = Math.min(first, iv[0]);
                for (int[] iv : carry[d]) first = Math.min(first, iv[0]);
                if (first != Integer.MAX_VALUE) return new String[]{"closed", "closed, opens " + (k == 1 ? "tomorrow " : DAYS[d] + " ") + fmt(first)};
            }
            return new String[]{"closed", "closed"};
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(int m) {
        return String.format(Locale.ROOT, "%02d:%02d", (m / 60) % 24, m % 60);
    }

    static String[] hoursOf(Poi p) {
        return state(p);
    }

    /** was a shop with these opening hours open at this time (hours that cannot be read count as 10:00-18:00) */
    static boolean openAt(String oh, double lon, long epochMs) {
        ZoneOffset off = ZoneOffset.ofHours(Math.max(-12, Math.min(14, (int) Math.round(lon / 15))));
        OffsetDateTime t = java.time.Instant.ofEpochMilli(epochMs).atOffset(off);
        int min = t.getHour() * 60 + t.getMinute();
        String[] st = hours(oh, t.getDayOfWeek().getValue() - 1, min);
        if (st == null) return min >= 600 && min < 1080;
        return st[0].equals("open");
    }

    /** a random shop that is open now, within r blocks of a place (or null) */
    Poi randomOpenShop(double x, double z, double r, java.util.Random rnd) {
        List<Poi> ok = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Poi q : around(Cmds.latOf(z), Cmds.lonOf(x))) {
            if (q.stop || Math.hypot(q.x - x, q.z - z) > r) continue;
            if (openAt(q.oh, q.lon, now)) ok.add(q);
        }
        return ok.isEmpty() ? null : ok.get(rnd.nextInt(ok.size()));
    }

    static double parseStars(String s) {
        try {
            return Double.parseDouble(s.replace('S', ' ').trim().split("[; ]")[0]);
        } catch (Exception e) {
            return 3;
        }
    }

    private static final String[] ADJ = {"Corner", "Central", "Sunny", "Green", "Blue", "Golden", "Old Town", "City", "Riverside", "Park", "Royal", "Little", "Happy", "Lucky", "Fresh", "Urban",
            "Northern", "Southern", "Garden", "Station", "Maple", "Harbor", "Eastside", "Westside", "Silver", "Cozy", "Grand", "Main Street"};

    /** a name that is not a real business: what it is, with a word before it (always the same for the same place) */
    static String generic(Poi q) {
        String k = q.kind == null ? "" : q.kind;
        String what = switch (k) {
            case "cafe" -> "Café";
            case "restaurant" -> "Restaurant";
            case "fast_food" -> "Snack Bar";
            case "bar" -> "Bar";
            case "pub" -> "Pub";
            case "ice_cream" -> "Ice Cream";
            case "bakery" -> "Bakery";
            case "pharmacy" -> "Pharmacy";
            case "bank" -> "Bank";
            case "post_office" -> "Post Office";
            case "cinema" -> "Cinema";
            case "theatre" -> "Theatre";
            case "library" -> "Library";
            case "doctors" -> "Clinic";
            case "dentist" -> "Dentist";
            case "marketplace" -> "Market";
            case "supermarket", "convenience" -> "Supermarket";
            case "clothes", "shoes", "boutique" -> "Clothes Shop";
            case "hotel" -> "Hotel";
            case "hostel" -> "Hostel";
            case "guest_house" -> "Guest House";
            case "motel" -> "Motel";
            case "apartment" -> "Apartments";
            case "books" -> "Book Store";
            case "electronics", "mobile_phone", "computer" -> "Electronics Store";
            case "hairdresser", "beauty" -> "Salon";
            case "florist" -> "Flower Shop";
            case "butcher", "greengrocer", "deli", "seafood" -> "Food Shop";
            case "kiosk" -> "Kiosk";
            case "department_store", "mall" -> "Department Store";
            default -> k.isEmpty() ? "Shop" : "Store";
        };
        int h = Math.floorMod((int) (Math.round(q.lat * 100000) * 31 + Math.round(q.lon * 100000) * 17), 1000003);
        return ADJ[h % ADJ.length] + " " + what;
    }

    static boolean isHotel(String kind) {
        return kind != null && (kind.equals("hotel") || kind.equals("hostel") || kind.equals("guest_house") || kind.equals("motel") || kind.equals("apartment"));
    }

    /** the hotel that is nearest to a place, within r blocks */
    Poi nearestHotel(double x, double z, double r) {
        Poi best = null;
        double bd = r;
        for (Poi q : around(Cmds.latOf(z), Cmds.lonOf(x))) {
            if (q.stop || !isHotel(q.kind)) continue;
            double d = Math.hypot(q.x - x, q.z - z);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    /** the shop (not a stop) that is nearest to a place, within r blocks */
    Poi nearest(double x, double z, double r) {
        double lat = Cmds.latOf(z), lon = Cmds.lonOf(x);
        Poi best = null;
        double bd = r;
        for (Poi q : around(lat, lon)) {
            if (q.stop) continue;
            double d = Math.hypot(q.x - x, q.z - z);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    private static String[] state(Poi p) {
        ZoneOffset off = ZoneOffset.ofHours(Math.max(-12, Math.min(14, (int) Math.round(p.lon / 15))));
        OffsetDateTime t = OffsetDateTime.now(off);
        return hours(p.oh, t.getDayOfWeek().getValue() - 1, t.getHour() * 60 + t.getMinute());
    }

    private static String nice(String s) {
        return s == null ? "" : s.replace('_', ' ');
    }

    // ---- the labels ----

    private void tick() {
        Set<UUID> online = new HashSet<>();
        for (World w : Bukkit.getWorlds()) {
            if (w.getGenerator() != gen) continue;
            for (Player p : w.getPlayers()) {
                online.add(p.getUniqueId());
                Location pl = p.getLocation();
                double lat = Cmds.latOf(pl.getZ()), lon = Cmds.lonOf(pl.getX());
                want(lat, lon);
                if (gen.indoor != null) gen.indoor.want(lat, lon);
                List<Poi> near = new ArrayList<>();
                for (Poi q : around(lat, lon)) {
                    double d = Math.hypot(q.x - pl.getX(), q.z - pl.getZ());
                    if (d < SHOW) near.add(q);
                }
                near.sort(Comparator.comparingDouble(q -> Math.hypot(q.x - pl.getX(), q.z - pl.getZ())));
                if (near.size() > 18) near = near.subList(0, 18);
                Map<Poi, TextDisplay> mine = labels.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
                Set<Poi> keep = new HashSet<>(near);
                mine.entrySet().removeIf(e -> {
                    if (keep.contains(e.getKey()) && e.getValue().isValid()) return false;
                    e.getValue().remove();
                    return true;
                });
                for (Poi q : near) {
                    int[] c = gen.cell(q.x, q.z);
                    if (c == null || Math.abs(c[0] - pl.getY()) > 40) continue;
                    TextDisplay td = mine.get(q);
                    Location at = new Location(w, q.x + 0.5, c[0] + 3.2, q.z + 0.5);
                    if (td == null) {
                        td = w.spawn(at, TextDisplay.class, e -> {
                            e.setVisibleByDefault(false);
                            e.setPersistent(false);
                            e.setBillboard(Display.Billboard.CENTER);
                            e.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                            e.setViewRange(1.2f);
                        });
                        p.showEntity(plugin, td);
                        mine.put(q, td);
                    }
                    td.text(label(q));
                }
            }
        }
        for (UUID id : new ArrayList<>(labels.keySet())) {
            if (online.contains(id)) continue;
            for (TextDisplay t : labels.remove(id).values()) t.remove();
        }
    }

    private static Component label(Poi q) {
        Component head = Component.text(q.name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
        if (isHotel(q.kind)) {
            Shops hs = shops;
            String ho = hs == null ? null : hs.ownerOf(q);
            String stars = q.stars == null ? "" : " " + "★".repeat(Math.max(0, Math.min(5, (int) Math.round(parseStars(q.stars)))));
            return head.append(Component.newline()).append(Component.text(nice(q.kind) + stars, NamedTextColor.LIGHT_PURPLE))
                    .append(Component.newline()).append(Component.text("rent a room: /hotel", NamedTextColor.GREEN))
                    .append(ho == null ? Component.empty() : Component.newline().append(Component.text("owner " + ho, NamedTextColor.GOLD)));
        }
        if (q.stop) {
            NamedTextColor c = switch (q.kind) {
                case "bus" -> NamedTextColor.GOLD;
                case "tram" -> NamedTextColor.RED;
                case "subway" -> NamedTextColor.AQUA;
                default -> NamedTextColor.GREEN;
            };
            return head.append(Component.newline()).append(Component.text(q.kind + " stop", c));
        }
        String[] st = state(q);
        Component sub = Component.text(nice(q.kind), NamedTextColor.GRAY);
        Shops sh = shops;
        String owner = sh == null ? null : sh.ownerOf(q);
        Component own = owner == null ? Component.empty() : Component.newline().append(Component.text("★ " + owner, NamedTextColor.GOLD));
        if (st == null) return head.append(Component.newline()).append(sub).append(Component.text(" · hours unknown", NamedTextColor.DARK_GRAY)).append(own);
        return head.append(Component.newline()).append(sub).append(Component.text(" · " + st[1], st[0].equals("open") ? NamedTextColor.GREEN : NamedTextColor.RED)).append(own);
    }

    // ---- /near ----

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) return true;
        if (p.getWorld().getGenerator() != gen) {
            p.sendMessage(Component.text("Shops and stops are for Earth (/earth).", NamedTextColor.RED));
            return true;
        }
        Location l = p.getLocation();
        double lat = Cmds.latOf(l.getZ()), lon = Cmds.lonOf(l.getX());
        want(lat, lon);
        boolean onlyOpen = args.length > 0 && args[0].equalsIgnoreCase("open");
        boolean onlyStops = args.length > 0 && (args[0].equalsIgnoreCase("stops") || args[0].equalsIgnoreCase("transit"));
        List<Poi> all = new ArrayList<>();
        for (Poi q : around(lat, lon)) {
            if (onlyStops && !q.stop) continue;
            if (onlyOpen && (q.stop || state(q) == null || !state(q)[0].equals("open"))) continue;
            all.add(q);
        }
        all.sort(Comparator.comparingDouble(q -> Math.hypot(q.x - l.getX(), q.z - l.getZ())));
        if (all.isEmpty()) {
            p.sendMessage(Component.text("Nothing found yet. The map data for this place is being loaded, try again in a few seconds. (/near, /near open, /near stops)", NamedTextColor.YELLOW));
            return true;
        }
        double heading = l.getYaw() + 180;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        p.sendMessage(Component.text("Near you (/near open: only open shops, /near stops: only transport):", NamedTextColor.GREEN));
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            Poi q = all.get(i);
            double dx = q.x - l.getX(), dz = q.z - l.getZ();
            double rel = ((Math.toDegrees(Math.atan2(dx, -dz)) - heading) % 360 + 540) % 360 - 180;
            String ar = arrows[Math.floorMod(Math.round(rel / 45), 8)];
            String[] st = q.stop ? null : state(q);
            String info = q.stop ? q.kind + " stop" : nice(q.kind) + (st == null ? ", hours unknown" : ", " + st[1]);
            NamedTextColor c = q.stop ? NamedTextColor.AQUA : st == null ? NamedTextColor.GRAY : st[0].equals("open") ? NamedTextColor.GREEN : NamedTextColor.RED;
            p.sendMessage(Component.text(String.format(Locale.ROOT, " %s %4d m  %s (%s)", ar, Math.round(Math.hypot(dx, dz)), q.name, info), c));
        }
        return true;
    }
}
