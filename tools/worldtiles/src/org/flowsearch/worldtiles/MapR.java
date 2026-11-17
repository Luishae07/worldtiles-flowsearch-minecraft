package org.flowsearch.worldtiles;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;

/** Draws the real map (streets, water, parks, roofs) on a map item, from the same squares that the world is made of. */
final class MapR extends MapRenderer {
    private final TileStore store;
    private long lastTry;
    private boolean done;

    MapR(TileStore store) {
        super(false);
        this.store = store;
    }

    private static int rgb(int r, int g, int b) {
        return (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
    }

    /** the colour of one place, or -1 when its square is not in memory yet */
    private int colorAt(int X, int Z) {
        int T = Tile.TILE;
        int tx = Math.floorDiv(X, T), tz = Math.floorDiv(Z, T);
        Tile t = store.peek(tx, tz);
        if (t == null) {
            store.prefetch(tx, tz);
            return -1;
        }
        int lx = X - tx * T, lz = Z - tz * T, i = Tile.idx(lx, lz);
        int wh = t.wallH[i] & 0xFF, sf = t.surf[i];
        int r, g, b;
        if (wh > 0) {
            switch (t.roofM[i] & 0xFF) {
                case 21 -> { r = 84; g = 90; b = 100; }
                case 22 -> { r = 92; g = 164; b = 140; }
                case 23 -> { r = 140; g = 138; b = 132; }
                default -> { r = 178; g = 96; b = 62; }
            }
        } else {
            switch (sf) {
                case Tile.S_PLAZA -> { r = 196; g = 190; b = 178; }
                case Tile.S_ASPHALT -> { r = 74; g = 76; b = 82; }
                case Tile.S_GRAVEL -> { r = 164; g = 154; b = 138; }
                case Tile.S_WATER -> { r = 58; g = 108; b = 200; }
                case Tile.S_DIRT -> { r = 134; g = 98; b = 68; }
                case Tile.S_SAND -> { r = 226; g = 206; b = 152; }
                default -> { r = 104; g = 156; b = 66; }
            }
        }
        // the light from the north-west, so that hills can be seen
        int dg = t.ground[i] - t.ground[Tile.idx(lx - 1, lz - 1)];
        double k = 1 + Math.max(-0.35, Math.min(0.35, dg * 0.06));
        // a house is a little brighter than the ground next to it, with a darker edge
        if (wh > 0) k *= 1.0 + Math.min(0.25, wh * 0.01);
        return rgb((int) (r * k), (int) (g * k), (int) (b * k));
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        if (done) return;
        long now = System.currentTimeMillis();
        if (now - lastTry < 2500) return; // squares that are still being made: look again in a moment
        lastTry = now;
        int s = 1 << view.getScale().getValue();
        int cx = view.getCenterX(), cz = view.getCenterZ();
        boolean missing = false;
        BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < 128; py++) {
            for (int px = 0; px < 128; px++) {
                int c = colorAt(cx + (px - 64) * s, cz + (py - 64) * s);
                if (c < 0) {
                    missing = true;
                    c = 0x2a2d33;
                }
                img.setRGB(px, py, c);
            }
        }
        canvas.drawImage(0, 0, img);
        done = !missing;
    }
}
