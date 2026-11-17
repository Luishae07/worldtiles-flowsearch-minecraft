#!/usr/bin/env python3
"""
Trains a small neural network that looks at the satellite picture of a building and says how tall it is and whether its roof is flat or sloped.

The training data are the files that "mctiles -roofdata <box> -crops" makes (one record per building, see tools/mctiles/cropdata.go), one file for every
town. Whole towns are kept apart for the test, so the numbers say how well the network does in a place it has never seen. It is also compared with
today's guesses: Microsoft's height for the building, and the most common roof.

    python train.py --data crops/*.bin --test berlin-mitte,cottbus,neuruppin --epochs 8 --out model

It runs on the CPU (GitHub Actions has no GPU), so the network is small.
"""
import argparse, glob, json, os, struct, sys, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

SIZE = 96
REC = SIZE * SIZE * 4 + 4 + 4 + 1 + 4 + 4


def load(path):
    raw = np.fromfile(path, dtype=np.uint8)
    n = len(raw) // REC
    raw = raw[: n * REC].reshape(n, REC)
    img = raw[:, : SIZE * SIZE * 4].reshape(n, SIZE, SIZE, 4)
    tail = raw[:, SIZE * SIZE * 4:]
    h = tail[:, 0:4].copy().view(np.float32)[:, 0]
    rh = tail[:, 4:8].copy().view(np.float32)[:, 0]
    cls = tail[:, 8]
    ms = tail[:, 9:13].copy().view(np.float32)[:, 0]
    area = tail[:, 13:17].copy().view(np.float32)[:, 0]
    return img, h, rh, cls, ms, area


class Net(nn.Module):
    """four blocks of convolution, then three answers: the height, the roof (flat or sloped), and the height of the roof itself"""

    def __init__(self):
        super().__init__()
        ch = [4, 32, 64, 96, 128]
        self.blocks = nn.ModuleList()
        for i in range(4):
            self.blocks.append(nn.Sequential(
                nn.Conv2d(ch[i], ch[i + 1], 3, padding=1), nn.BatchNorm2d(ch[i + 1]), nn.ReLU(),
                nn.Conv2d(ch[i + 1], ch[i + 1], 3, padding=1), nn.BatchNorm2d(ch[i + 1]), nn.ReLU(),
                nn.MaxPool2d(2)))
        self.head = nn.Sequential(nn.Linear(128 + 1, 128), nn.ReLU(), nn.Dropout(0.2), nn.Linear(128, 3))

    def forward(self, x, aux):
        for b in self.blocks:
            x = b(x)
        x = x.mean(dim=(2, 3))                       # the mean over the picture
        return self.head(torch.cat([x, aux], dim=1))


def prep(img, area):
    x = torch.from_numpy(img).float().permute(0, 3, 1, 2) / 255.0
    x[:, :3] = (x[:, :3] - 0.4) / 0.25
    aux = torch.from_numpy(np.log1p(area)[:, None].astype(np.float32)) / 8.0
    return x, aux


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--test", default="", help="names of towns (the file names without .bin) that are only used for the test")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--out", default="model")
    ap.add_argument("--max", type=int, default=0, help="use at most this many buildings for the learning (a test run)")
    a = ap.parse_args()
    torch.set_num_threads(os.cpu_count() or 4)
    torch.manual_seed(1); np.random.seed(1)
    files = sorted(sum([glob.glob(p) for p in a.data], []))
    test_names = {t.strip() for t in a.test.split(",") if t.strip()}
    tr, te = [], []
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0].replace("crops-", "")
        d = load(f)
        print(f"{name}: {len(d[1])} buildings", "(test)" if name in test_names else "")
        (te if name in test_names else tr).append(d)
    if not tr:
        sys.exit("no files for the learning")
    cat = lambda L, i: np.concatenate([d[i] for d in L])
    Xi, H, RH, C, MS, AR = [cat(tr, i) for i in range(6)]
    if a.max and len(H) > a.max:
        idx = np.random.permutation(len(H))[: a.max]; Xi, H, RH, C, MS, AR = Xi[idx], H[idx], RH[idx], C[idx], MS[idx], AR[idx]
    print(f"learning from {len(H)} buildings" + (f", test on {sum(len(d[1]) for d in te)} buildings of other towns" if te else " (no test towns: the last 15% are used)"))
    if not te:
        k = int(len(H) * 0.85); perm = np.random.permutation(len(H)); Xi, H, RH, C, MS, AR = [v[perm] for v in (Xi, H, RH, C, MS, AR)]
        te = [(Xi[k:], H[k:], RH[k:], C[k:], MS[k:], AR[k:])]; Xi, H, RH, C, MS, AR = Xi[:k], H[:k], RH[:k], C[:k], MS[:k], AR[:k]
    net = Net()
    opt = torch.optim.AdamW(net.parameters(), lr=2e-3, weight_decay=1e-4)
    steps = a.epochs * ((len(H) + a.batch - 1) // a.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=steps)
    t0 = time.time()
    for ep in range(a.epochs):
        net.train(); perm = np.random.permutation(len(H)); tot = 0.0
        for b in range(0, len(perm), a.batch):
            ix = np.sort(perm[b: b + a.batch])
            x, aux = prep(Xi[ix], AR[ix])
            if np.random.rand() < 0.5: x = x.flip(3)          # turn the picture over (a roof is still a roof)
            if np.random.rand() < 0.5: x = x.flip(2)
            k = np.random.randint(4); x = torch.rot90(x, k, (2, 3))
            h = torch.from_numpy(H[ix]); rh = torch.from_numpy(RH[ix]); c = torch.from_numpy(C[ix].astype(np.int64))
            o = net(x, aux)
            loss = F.smooth_l1_loss(o[:, 0], h / 10.0) + 0.5 * F.smooth_l1_loss(o[:, 2], rh / 5.0)
            known = c != 255
            if known.any():
                loss = loss + F.binary_cross_entropy_with_logits(o[known, 1], c[known].float())
            opt.zero_grad(); loss.backward(); opt.step(); sched.step(); tot += float(loss) * len(ix)
        print(f"epoch {ep + 1}/{a.epochs}: loss {tot / len(H):.4f}, {time.time() - t0:.0f} s", flush=True)

    # the test: buildings of other towns that it has not seen
    net.eval(); res = {"height": [], "ms": [], "true": [], "p": [], "cls": []}
    with torch.no_grad():
        for d in te:
            for b in range(0, len(d[1]), 256):
                s = slice(b, b + 256)
                x, aux = prep(d[0][s], d[5][s]); o = net(x, aux)
                res["height"].append(o[:, 0].numpy() * 10.0); res["p"].append(torch.sigmoid(o[:, 1]).numpy())
                res["true"].append(d[1][s]); res["ms"].append(d[4][s]); res["cls"].append(d[3][s])
    r = {k: np.concatenate(v) for k, v in res.items()}
    err = np.abs(r["height"] - r["true"]); ok = r["ms"] > 0
    msErr = np.abs(r["ms"][ok] - r["true"][ok])
    kn = r["cls"] != 255
    acc = float(((r["p"][kn] > 0.5) == (r["cls"][kn] == 1)).mean()) if kn.any() else float("nan")
    base = float(max((r["cls"][kn] == 1).mean(), (r["cls"][kn] == 0).mean())) if kn.any() else float("nan")
    rep = {
        "test_buildings": int(len(r["true"])),
        "height_error_m": {"network": float(err.mean()), "microsoft_guess": float(msErr.mean()) if ok.any() else None, "same_for_all": float(np.abs(r["true"] - H.mean()).mean())},
        "height_within_2m_percent": {"network": float((err <= 2).mean() * 100), "microsoft_guess": float((msErr <= 2).mean() * 100) if ok.any() else None},
        "roof_flat_or_sloped_right_percent": {"network": acc * 100, "always_the_most_common": base * 100},
        "learned_from": int(len(H)), "epochs": a.epochs, "seconds": int(time.time() - t0),
    }
    print(json.dumps(rep, indent=2))
    os.makedirs(a.out, exist_ok=True)
    json.dump(rep, open(os.path.join(a.out, "report.json"), "w"), indent=2)
    torch.save(net.state_dict(), os.path.join(a.out, "model.pt"))
    # the model for other programs (ONNX); the picture of 96 x 96 x 4 and the area go in
    try:
        torch.onnx.export(net, (torch.zeros(1, 4, SIZE, SIZE), torch.zeros(1, 1)), os.path.join(a.out, "model.onnx"), input_names=["picture", "area"], output_names=["out"], dynamic_axes={"picture": {0: "n"}, "area": {0: "n"}, "out": {0: "n"}}, opset_version=17)
    except Exception as e:
        print("no ONNX file:", e)


if __name__ == "__main__":
    main()
