package org.flowsearch.worldtiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Gets the squares from the tile service on this machine (it makes a square that is not there yet) and keeps the latest ones in memory. */
final class TileStore {
    private final String url;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(12, r -> {
        Thread t = new Thread(r, "worldtiles-fetch");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<Long, CompletableFuture<Tile>> inflight = new ConcurrentHashMap<>();
    private final Map<Long, Tile> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Tile> e) {
            return size() > 16; // squares are about 3 MB each in memory: the ones around a player are enough, the rest comes from the disk of the tile service
        }
    };

    TileStore(String url, Logger log) {
        this.url = url;
        this.log = log;
    }

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    /** Starts getting a square in the background (for the maps). */
    void prefetch(int tx, int tz) {
        long k = key(tx, tz);
        if (peek(tx, tz) != null) return;
        // starts the download and returns at once; it does not wait, so it never holds a worker
        inflight.computeIfAbsent(k, kk -> CompletableFuture.supplyAsync(() -> load(tx, tz), pool).whenComplete((t, err) -> {
            if (t != null) synchronized (cache) {
                cache.put(k, t);
            }
            inflight.remove(k);
        }));
    }

    /** The square when it is already in memory, otherwise null (never waits). */
    Tile peek(int tx, int tz) {
        synchronized (cache) {
            return cache.get(key(tx, tz));
        }
    }

    /** The square, or null when the service could not make it (after some tries). Waits for the answer. */
    Tile get(int tx, int tz) {
        long k = key(tx, tz);
        synchronized (cache) {
            Tile t = cache.get(k);
            if (t != null) return t;
        }
        CompletableFuture<Tile> f = inflight.computeIfAbsent(k, kk -> CompletableFuture.supplyAsync(() -> load(tx, tz), pool));
        try {
            Tile t = f.get(6, TimeUnit.MINUTES);
            if (t != null) synchronized (cache) {
                cache.put(k, t);
            }
            return t;
        } catch (Exception e) {
            log.warning("square " + tx + "," + tz + ": " + e);
            return null;
        } finally {
            inflight.remove(k, f);
        }
    }

    private Tile load(int tx, int tz) {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"x\":" + tx + ",\"z\":" + tz + "}")).build();
                HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return Tile.parse(tx, tz, r.body());
                if (r.statusCode() >= 400 && r.statusCode() < 500 && r.statusCode() != 429) { // the service says no (for example out of the world): trying again does not help
                    log.warning("square " + tx + "," + tz + " refused: " + r.statusCode() + " " + new String(r.body(), 0, Math.min(r.body().length, 80)).trim());
                    return null;
                }
                last = new IOException("tile service answered " + r.statusCode() + ": " + new String(r.body(), 0, Math.min(r.body().length, 120)));
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            try {
                Thread.sleep(3000L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warning("square " + tx + "," + tz + " failed: " + last);
        return null;
    }
}
