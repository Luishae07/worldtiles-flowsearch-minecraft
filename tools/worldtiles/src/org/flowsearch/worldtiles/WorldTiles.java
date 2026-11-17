package org.flowsearch.worldtiles;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;

/**
 * The world, made from the tile service of minecraft.flowsearch.org (Earth, one block per metre), and a second world: the Moon,
 * made from NASA's laser height map. Put "generator: WorldTiles" for the world in bukkit.yml.
 */
public final class WorldTiles extends JavaPlugin implements Listener {
    private TileStore store;
    private TileGen gen;
    /** -Dworldtiles.lite=true: only the world is made (a server for something else, like the duels), none of the game */
    static final boolean LITE = Boolean.getBoolean("worldtiles.lite");
    private MoonGen moonGen;
    private EarthSky earthSky;
    private Pilot pilot;
    private Flights flights;
    private Car car;
    private People people;
    private Places places;
    private Transit transit;
    private Traffic traffic;
    private Signals signals;
    private Work work;
    private Hotel hotel;
    private Football football;
    private ShopInterior interiors;
    private Drone drone;

    @Override
    public void onDisable() {
        if (drone != null) drone.removeAll();
        if (interiors != null) interiors.removeAll();
        if (football != null) football.removeAll();
        if (work != null) work.removeAll();
        if (signals != null) signals.removeAll();
        if (traffic != null) traffic.removeAll();
        if (transit != null) transit.removeAll();
        if (places != null) places.removeAll();
        if (people != null) people.removeAll();
        if (car != null) car.removeAll();
        if (flights != null) flights.removeAll();
        if (earthSky != null) earthSky.removeAll();
        if (pilot != null) pilot.removeAll();
    }

    @Override
    public void onLoad() {
        String url = System.getProperty("worldtiles.url", "http://127.0.0.1:8858/tiles/3d/load");
        store = new TileStore(url, getLogger());
        gen = new TileGen(store);
        gen.indoor = new Indoor();
        Landmarks.load(new File(System.getProperty("worldtiles.landmarks", "/srv/uploads/flowsites/minecraft/landmarks")), getLogger());
        if (LITE) return; // a server that only needs the world of Berlin (the duels): no Moon
        MoonData md = new MoonData(Path.of(System.getProperty("worldtiles.moon", "/media/data/moon-data/ldem_64.img")), new File("/srv/uploads/earthsite/tiles/moon/6"));
        if (!md.hasHeights()) getLogger().warning("Moon: the height map /media/data/moon-data/ldem_64.img is missing, the Moon will be flat");
        moonGen = new MoonGen(md);
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        if (LITE) { // -Dworldtiles.lite=true: only the world (the generator and the spawn), none of the game: no jobs, cars, people, weather or Moon
            getLogger().info("WorldTiles: lite mode, only the world of the tile service is made");
            return;
        }
        Weather weather = new Weather(this, gen);
        weather.start();
        MoonLife moonLife = new MoonLife(this, moonGen, weather);
        moonLife.start();
        earthSky = new EarthSky(this, moonGen);
        earthSky.start();
        pilot = new Pilot(this, store);
        pilot.start();
        getServer().getPluginManager().registerEvents(pilot, this);
        if (getCommand("plane") != null) getCommand("plane").setExecutor(pilot);
        flights = new Flights(this, gen, store);
        flights.start();
        getServer().getPluginManager().registerEvents(flights, this);
        for (String c : new String[]{"flights", "ride"}) if (getCommand(c) != null) getCommand(c).setExecutor(flights);
        Economy economy = new Economy(this);
        for (String c : new String[]{"money", "licence"}) if (getCommand(c) != null) getCommand(c).setExecutor(economy);
        car = new Car(this, gen, store, economy);
        car.start();
        getServer().getPluginManager().registerEvents(car, this);
        for (String c : new String[]{"car", "job"}) if (getCommand(c) != null) getCommand(c).setExecutor(car);
        people = new People(this, gen);
        people.start();
        transit = new Transit(this, gen, economy);
        transit.start();
        getServer().getPluginManager().registerEvents(transit, this);
        if (getCommand("train") != null) getCommand("train").setExecutor(transit);
        signals = new Signals(this, gen);
        signals.start();
        traffic = new Traffic(this, gen, signals);
        traffic.start();
        places = new Places(this, gen);
        places.start();
        if (getCommand("near") != null) getCommand("near").setExecutor(places);
        Shops shops = new Shops(this, places, economy);
        Places.shops = shops;
        if (getCommand("shop") != null) getCommand("shop").setExecutor(shops);
        People.places = places;
        Houses houses = new Houses(this, gen, economy);
        houses.start();
        Places.houses = houses;
        People.houses = houses;
        getServer().getPluginManager().registerEvents(people, this);
        if (getCommand("house") != null) getCommand("house").setExecutor(houses);
        football = new Football(this, gen);
        football.start();
        if (getCommand("football") != null) getCommand("football").setExecutor(football);
        hotel = new Hotel(this, places, economy, shops);
        hotel.start();
        getServer().getPluginManager().registerEvents(hotel, this);
        if (getCommand("hotel") != null) getCommand("hotel").setExecutor(hotel);
        if (getCommand("credits") != null) getCommand("credits").setExecutor(new Credits());
        drone = new Drone(this, gen, store);
        drone.start();
        getServer().getPluginManager().registerEvents(drone, this);
        if (getCommand("drone") != null) getCommand("drone").setExecutor(drone);
        if (getCommand("buy") != null) getCommand("buy").setExecutor(new Menu(gen, places, economy, shops));
        interiors = new ShopInterior(this, gen, places);
        interiors.start();
        work = new Work(this, gen, places, economy, shops);
        work.start();
        getServer().getPluginManager().registerEvents(work, this);
        if (getCommand("work") != null) getCommand("work").setExecutor(work);
        Skydive skydive = new Skydive(this, gen, store);
        skydive.start();
        getServer().getPluginManager().registerEvents(skydive, this);
        if (getCommand("skydive") != null) getCommand("skydive").setExecutor(skydive);
        getServer().getPluginManager().registerEvents(moonLife, this);
        Cmds cmds = new Cmds(this, gen, store, weather, moonLife);
        for (String c : new String[]{"tpll", "go", "where", "spawn", "worldmap", "fly", "realweather", "moon", "earth", "tp", "datacenter"}) {
            if (getCommand(c) != null) getCommand(c).setExecutor(cmds);
        }
        getLogger().info("WorldTiles: squares of 500 m from the tile service, one block per metre, sea level at y=" + TileGen.SEA);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return MoonLife.WORLD.equals(worldName) || "moon".equals(id) ? moonGen : gen;
    }

    /** Minecraft picks the spawn height before any chunk exists (and gets the bottom of the world), so the spawn is set again when the worlds are loaded. The Moon is made here. */
    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        World moon = Bukkit.getWorld(MoonLife.WORLD);
        if (moon == null && !LITE) {
            WorldCreator wc = new WorldCreator(MoonLife.WORLD);
            wc.generator(moonGen);
            wc.environment(World.Environment.NORMAL);
            wc.generateStructures(false);
            moon = wc.createWorld();
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getGenerator() == null || (w.getGenerator() != gen && w.getGenerator() != moonGen)) continue;
            Location s = (w.getGenerator() == moonGen ? moonGen : gen).getFixedSpawnLocation(w, null);
            w.setSpawnLocation(s);
            w.setStorm(false);
            getLogger().info("spawn of " + w.getName() + " set to " + s.getBlockX() + ", " + s.getBlockY() + ", " + s.getBlockZ());
        }
    }

    /** Flying into a wall or the ground ("kinetic energy") does not hurt: the planes, the rides and the skydives are games, and the world cannot be seen far ahead at that speed. */
    @EventHandler
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FLY_INTO_WALL
                && (p.getWorld().getGenerator() == gen || p.getWorld().getGenerator() == moonGen)) {
            e.setCancelled(true);
        }
    }

    /** The worlds are made again from their data whenever a chunk is needed, so the chunks are not saved (nobody can build in adventure mode). This keeps the folders small. */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (e.getWorld().getGenerator() == gen || e.getWorld().getGenerator() == moonGen) e.setSaveChunk(false);
    }

    /** Somebody who is inside the ground (an old saved position) is put on the surface. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld().getGenerator() == moonGen) {
            Location l = p.getLocation();
            int y = moonGen.heightY(l.getBlockX(), l.getBlockZ());
            if (l.getY() < y - 1) p.teleportAsync(new Location(p.getWorld(), l.getX(), y + 1, l.getZ(), l.getYaw(), l.getPitch()));
            return;
        }
        if (p.getWorld().getGenerator() != gen) return;
        Location l = p.getLocation();
        int surface = gen.surfaceY(l.getBlockX(), l.getBlockZ(), false);
        if (surface == Integer.MIN_VALUE) { // this square is not here yet: the spawn is the safe place
            p.teleportAsync(p.getWorld().getSpawnLocation());
            return;
        }
        if (l.getY() < surface - 1) {
            Location up = new Location(p.getWorld(), l.getX(), surface + 1, l.getZ(), l.getYaw(), l.getPitch());
            p.setFallDistance(0);
            p.teleportAsync(up);
            getLogger().info(p.getName() + " was inside the ground at y=" + l.getBlockY() + ", moved to y=" + (surface + 1));
        }
    }
}
