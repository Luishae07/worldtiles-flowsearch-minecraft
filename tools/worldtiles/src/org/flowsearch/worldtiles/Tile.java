package org.flowsearch.worldtiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/** One square of 500 x 500 blocks (and one block of its neighbours around it) from the tile service. */
final class Tile {
    static final int TILE = 500, PAD = 1, PP = TILE + 2 * PAD;

    // surfaces
    static final int S_VOID = 0, S_GRASS = 1, S_PLAZA = 2, S_ASPHALT = 3, S_GRAVEL = 4, S_WATER = 5, S_DIRT = 6, S_SAND = 7;

    private static final java.util.regex.Pattern TREE = java.util.regex.Pattern.compile("\\{\"X\":(-?\\d+),\"Z\":(-?\\d+),\"H\":(-?\\d+)\\}");

    final int tx, tz;
    final short[] ground = new short[PP * PP];
    final byte[] surf = new byte[PP * PP], wallH = new byte[PP * PP], roofH = new byte[PP * PP], wallM = new byte[PP * PP], roofM = new byte[PP * PP];
    int[] trees = new int[0]; // x, z, height, x, z, height, ...
    byte[] peri, door, ladder, hall;

    Tile(int tx, int tz) {
        this.tx = tx;
        this.tz = tz;
    }

    static int idx(int lx, int lz) {
        return (lz + PAD) * PP + lx + PAD;
    }

    static Tile parse(int tx, int tz, byte[] gz) throws IOException {
        byte[] raw = new GZIPInputStream(new ByteArrayInputStream(gz)).readAllBytes();
        if (raw.length < 9 || !new String(raw, 0, 5, StandardCharsets.US_ASCII).equals("MCT2\n")) throw new IOException("not a MCT2 square");
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int hl = bb.getInt(5);
        String hdr = new String(raw, 9, hl, StandardCharsets.UTF_8);
        Tile t = new Tile(tx, tz);
        int p = 9 + hl, n = PP * PP;
        for (int i = 0; i < n; i++) t.ground[i] = bb.getShort(p + i * 2);
        p += n * 2;
        System.arraycopy(raw, p, t.surf, 0, n);
        p += n;
        System.arraycopy(raw, p, t.wallH, 0, n);
        p += n;
        System.arraycopy(raw, p, t.roofH, 0, n);
        p += n;
        System.arraycopy(raw, p, t.wallM, 0, n);
        p += n;
        System.arraycopy(raw, p, t.roofM, 0, n);
        // the trees: [{"X":12,"Z":40,"H":6}, ...] in the header
        java.util.regex.Matcher m = TREE.matcher(hdr);
        java.util.ArrayList<int[]> list = new java.util.ArrayList<>();
        while (m.find()) list.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
        t.trees = new int[list.size() * 3];
        for (int i = 0; i < list.size(); i++) System.arraycopy(list.get(i), 0, t.trees, i * 3, 3);
        t.prepare();
        return t;
    }

    boolean isB(int i) {
        return (wallH[i] & 0xFF) > 0 && surf[i] != S_VOID;
    }

    private static final int[][] NB = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    /** The inside of the houses: walls, a door facing a street, a hall flag for tall buildings and one ladder. The same rules as on the website. */
    void prepare() {
        int W = PP, H = PP, N = W * H;
        int[] comp = new int[N];
        Arrays.fill(comp, -1);
        peri = new byte[N];
        door = new byte[N];
        ladder = new byte[N];
        hall = new byte[N];
        int nc = 0;
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int s = 0; s < N; s++) {
            if (!isB(s) || comp[s] >= 0) continue;
            java.util.ArrayList<Integer> cells = new java.util.ArrayList<>();
            comp[s] = nc;
            stack.push(s);
            while (!stack.isEmpty()) {
                int j = stack.pop();
                cells.add(j);
                int x = j % W, z = j / W;
                for (int[] d : NB) {
                    int X = x + d[0], Z = z + d[1];
                    if (X < 0 || Z < 0 || X >= W || Z >= H) continue;
                    int k = Z * W + X;
                    if (isB(k) && comp[k] < 0) {
                        comp[k] = nc;
                        stack.push(k);
                    }
                }
            }
            nc++;
            int maxH = 0;
            java.util.ArrayList<Integer> cands = new java.util.ArrayList<>(), cands2 = new java.util.ArrayList<>();
            for (int j : cells) {
                maxH = Math.max(maxH, wallH[j] & 0xFF);
                int x = j % W, z = j / W;
                boolean out = false, roadSide = false, anySide = false;
                for (int[] d : NB) {
                    int X = x + d[0], Z = z + d[1];
                    if (X < 0 || Z < 0 || X >= W || Z >= H) {
                        out = true;
                        continue;
                    }
                    int k = Z * W + X;
                    if (isB(k)) continue;
                    out = true;
                    int sk = surf[k];
                    if (sk == S_VOID || sk == S_WATER || Math.abs(ground[k] - ground[j]) > 1) continue;
                    anySide = true;
                    if (sk == S_PLAZA || sk == S_ASPHALT || sk == S_GRAVEL) roadSide = true;
                }
                if (out) {
                    peri[j] = 1;
                    if (roadSide) cands.add(j);
                    else if (anySide) cands2.add(j);
                }
            }
            boolean isHall = maxH > 40;
            if (isHall) for (int j : cells) hall[j] = 1;
            java.util.ArrayList<Integer> pick = cands.isEmpty() ? cands2 : cands;
            int dj = -1;
            if (!pick.isEmpty()) {
                dj = pick.get(pick.size() / 2);
                door[dj] = 1;
            }
            if (!isHall && maxH >= 5 && dj >= 0) {
                int dx0 = dj % W, dz0 = dj / W, best = -1, bd = Integer.MAX_VALUE;
                for (int j : cells) {
                    if (peri[j] != 0) continue;
                    int x = j % W, z = j / W;
                    boolean wallNear = false;
                    for (int[] d : NB) {
                        int k = (z + d[1]) * W + (x + d[0]);
                        if (k >= 0 && k < N && isB(k) && peri[k] != 0 && door[k] == 0) wallNear = true;
                    }
                    if (!wallNear) continue;
                    int dist = Math.abs(x - dx0) + Math.abs(z - dz0), score = Math.abs(dist - 4);
                    if (score < bd) {
                        bd = score;
                        best = j;
                    }
                }
                if (best >= 0) ladder[best] = 1;
            }
        }
    }
}
