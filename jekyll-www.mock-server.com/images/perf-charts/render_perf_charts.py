#!/usr/bin/env python3
"""
Render the MockServer "Scalability & Latency" documentation charts from real
performance-test data (k6 load sweeps + JMH micro-benchmarks).

The charts are committed PNGs under jekyll-www.mock-server.com/images/; this
script regenerates them from the JSON in ./data/ so every figure on the page is
reproducible from measured data rather than hand-drawn. It mirrors the
images/diagram-tools/ pattern (a committed renderer + its source data).

Data files (./data/, produced by the perf pipeline — see
mockserver-performance-test/k6/sweep.js and
mockserver/mockserver-benchmark/run-scaling.sh):

  perf-sweep.json    {proto, points:[{offered_rps, achieved_rps,
                       p50_ms,p90_ms,p95_ms,p99_ms,p999_ms, error_rate}]}
  perf-scaling.json  {scaling:{matching:[{expectations,matcherType,time_per_op,
                       time_unit,alloc_bytes_per_op}],
                       candidate_index:[{mode,expectations,time_per_op,time_unit}]}}
  perf-result.json   (optional, CI) {behaviours:{<op>_<proto>:{p50_ms,p95_ms,
                       p99_ms,throughput_rps,error_rate}}, ...}

Usage:
  python3 render_perf_charts.py                 # data/ -> images/ (parent dir)
  python3 render_perf_charts.py --data DIR --out DIR

Requires matplotlib + numpy (see ./requirements.txt). Each chart is skipped with
a notice if its source data file is absent, so a partial data set still renders
what it can.
"""
import argparse
import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

# --- house style (matches images/diagram-tools THEME + site palette) ----------
BLUE = "#4F81BD"   # accent1 — primary
RED = "#C00000"    # red — "slow"/baseline line
TEAL = "#4BACC6"   # accent5
PURPLE = "#8064A2"  # accent4
GREY = "#404040"   # text/axes
LIGHT = "#E6E6E6"  # grid

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Helvetica", "Arial", "DejaVu Sans"],
    "font.size": 12,
    "axes.edgecolor": GREY,
    "axes.labelcolor": GREY,
    "axes.titlecolor": GREY,
    "xtick.color": GREY,
    "ytick.color": GREY,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "figure.facecolor": "white",
    "axes.facecolor": "white",
    "savefig.facecolor": "white",
    "svg.fonttype": "none",
})
DPI = 160


def load(data_dir, name):
    path = os.path.join(data_dir, name)
    if not os.path.isfile(path):
        return None
    with open(path) as fh:
        return json.load(fh)


def save(fig, out_dir, stem):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, stem + ".png")
    fig.savefig(path, dpi=DPI, bbox_inches="tight")
    plt.close(fig)
    print("  wrote", os.path.relpath(path))


def _log_x(ax, ticks):
    ax.set_xscale("log")
    ax.set_xticks(ticks)
    ax.xaxis.set_major_formatter(mticker.FuncFormatter(
        lambda v, _: f"{int(v):,}" if v >= 1 else f"{v:g}"))
    ax.minorticks_off()


def _grid(ax):
    ax.grid(True, which="major", color=LIGHT, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)


# --- chart 1: throughput vs latency "knee" ------------------------------------
def chart_knee(sweep, out_dir):
    pts = sorted(sweep["points"], key=lambda p: p["offered_rps"])
    offered = [p["offered_rps"] for p in pts]
    achieved = [p["achieved_rps"] for p in pts]
    p50 = [p["p50_ms"] for p in pts]
    p95 = [p["p95_ms"] for p in pts]
    p99 = [p["p99_ms"] for p in pts]

    fig, (axL, axR) = plt.subplots(1, 2, figsize=(12.4, 4.9))

    # Panel A: latency percentiles vs OFFERED load (log-log) — flat, then the knee.
    # Offered rate keeps the x-axis monotonic once the server saturates (achieved folds back).
    axL.plot(offered, p50, "-o", color=BLUE, lw=2.2, ms=5, label="p50", zorder=4)
    axL.plot(offered, p95, "-o", color=TEAL, lw=2.2, ms=5, label="p95", zorder=4)
    axL.plot(offered, p99, "-o", color=RED, lw=2.2, ms=5, label="p99", zorder=4)
    # Thin the log-axis ticks (~2x apart) so high-end labels don't collide; all data still plots.
    knee_ticks = [t for t in (2000, 4000, 8000, 16000, 32000, 64000)
                  if min(offered) <= t <= max(offered)]
    _log_x(axL, knee_ticks or offered)
    axL.set_yscale("log")
    _grid(axL)
    axL.set_xlabel("offered load (requests / sec)")
    axL.set_ylabel("response latency (ms)")
    axL.set_title("Latency stays flat until the knee", fontweight="bold", fontsize=13)
    axL.legend(frameon=False, loc="upper left")

    # Panel B: achieved vs offered (linear) — throughput tracks load then hits a ceiling.
    axR.plot(offered, offered, "--", color=GREY, lw=1.4, alpha=0.6,
             label="ideal (keeps up)", zorder=2)
    axR.plot(offered, achieved, "-o", color=BLUE, lw=2.4, ms=5,
             label="achieved", zorder=4)
    peak = max(achieved)
    axR.axhline(peak, color=RED, lw=1.0, ls=":", alpha=0.7, zorder=1)
    axR.annotate(f"ceiling ≈ {peak / 1000:.0f}k req/s",
                 xy=(offered[-1], peak), xytext=(0, 8), textcoords="offset points",
                 ha="right", va="bottom", color=RED, fontsize=10)
    _grid(axR)
    axR.xaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{int(v / 1000)}k"))
    axR.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{int(v / 1000)}k"))
    axR.set_xlabel("offered load (requests / sec)")
    axR.set_ylabel("achieved throughput (requests / sec)")
    axR.set_title("Throughput tracks load to a ceiling", fontweight="bold", fontsize=13)
    axR.legend(frameon=False, loc="upper left")

    fig.suptitle("Single MockServer instance — throughput vs latency",
                 fontsize=15, fontweight="bold", color=GREY, y=1.02)
    save(fig, out_dir, "perf_throughput_latency")


# --- chart 2: matching cost stays flat as expectations grow -------------------
def chart_matching_scaling(scaling, out_dir):
    ci = scaling["scaling"].get("candidate_index", [])
    if not ci:
        print("  (no candidate_index data — skipping matching-scaling chart)")
        return

    def series(mode):
        rows = sorted((r for r in ci if r["mode"] == mode),
                      key=lambda r: r["expectations"])
        return [r["expectations"] for r in rows], [r["time_per_op"] for r in rows]

    sx, sy = series("SCAN")
    ix, iy = series("INDEX")
    unit = ci[0].get("time_unit", "us/op").replace("/op", "")

    fig, ax = plt.subplots(figsize=(8.8, 5.2))
    ax.plot(sx, sy, "-o", color=RED, lw=2.4, ms=6,
            label="linear scan (every expectation)", zorder=4)
    ax.plot(ix, iy, "-o", color=BLUE, lw=2.4, ms=6,
            label="candidate index (MockServer default)", zorder=5)
    _log_x(ax, sx or ix)
    _grid(ax)
    ax.set_xlabel("number of registered expectations")
    ax.set_ylabel(f"time per match ({unit})")
    ax.set_title("Request matching stays flat as expectations scale",
                 fontweight="bold", fontsize=14)
    ax.set_ylim(bottom=0)
    ax.legend(frameon=False, loc="upper left")

    # annotate the largest speed-up across the expectation counts both modes share
    common = sorted(set(sx) & set(ix))
    sdict, idict = dict(zip(sx, sy)), dict(zip(ix, iy))
    best = max(((n, sdict[n] / idict[n]) for n in common if idict.get(n, 0) > 0),
               key=lambda t: t[1], default=None)
    if best and best[1] >= 1.5:
        n, ratio = best
        ax.annotate(f"up to {ratio:.0f}x faster\n(at {n:,} expectations)",
                    xy=(n, idict[n]), xytext=(0.42, 0.6), textcoords="axes fraction",
                    ha="left", color=GREY, fontsize=11,
                    arrowprops=dict(arrowstyle="->", color=GREY, lw=1.2))
    save(fig, out_dir, "perf_matching_scaling")


# --- chart 3: latency percentile distribution at sustained load ---------------
def chart_percentiles(sweep, out_dir):
    # representative sustained point: the highest offered rate that kept up
    # (achieved within 2% of offered) with no errors.
    pts = sorted(sweep["points"], key=lambda p: p["offered_rps"])
    sustained = [p for p in pts
                 if p["error_rate"] == 0 and p["achieved_rps"] >= 0.98 * p["offered_rps"]]
    pt = (sustained or pts)[-1]

    labels = ["p50", "p90", "p95", "p99", "p99.9"]
    vals = [pt["p50_ms"], pt["p90_ms"], pt["p95_ms"], pt["p99_ms"], pt["p999_ms"]]
    colors = [BLUE, BLUE, TEAL, PURPLE, RED]

    fig, ax = plt.subplots(figsize=(8.4, 4.9))
    bars = ax.bar(labels, vals, color=colors, width=0.62, zorder=3)
    _grid(ax)
    ax.set_ylabel("response latency (ms)")
    ax.set_title(
        f"Latency distribution at {pt['achieved_rps']:,.0f} requests / sec",
        fontweight="bold", fontsize=14)
    # A single GC pause can make p99.9 dwarf the rest; switch to a log axis when the
    # spread is large so every percentile stays readable, else keep a linear axis.
    lo = min(v for v in vals if v > 0)
    if max(vals) / lo > 30:
        ax.set_yscale("log")
        ax.set_ylim(bottom=lo * 0.5, top=max(vals) * 2.2)
    else:
        ax.set_ylim(top=max(vals) * 1.18)
    for b, v in zip(bars, vals):
        ax.text(b.get_x() + b.get_width() / 2, v, f"{v:.2f} ms",
                ha="center", va="bottom", fontsize=11, color=GREY)
    save(fig, out_dir, "perf_latency_percentiles")


# --- chart 4 (optional, CI): HTTP vs HTTPS+H2 per behaviour -------------------
def chart_http_vs_h2(result, out_dir):
    beh = result.get("behaviours") or {}
    ops = ["match", "forward", "template", "large"]
    have = [o for o in ops if f"{o}_http" in beh or f"{o}_https_h2" in beh]
    if not have:
        print("  (no CI behaviours data — skipping HTTP-vs-H2 chart)")
        return

    # Use median (p50): the fixed-rate regression's p95/p99 tail is dominated by
    # cold-JVM/JIT warmup over the measurement window (the warm sweep shows the same
    # match path at sub-ms), so p50 is the honest steady-state protocol comparison.
    http = [beh.get(f"{o}_http", {}).get("p50_ms", 0) for o in have]
    h2 = [beh.get(f"{o}_https_h2", {}).get("p50_ms", 0) for o in have]
    x = np.arange(len(have))
    w = 0.38

    fig, ax = plt.subplots(figsize=(8.8, 4.9))
    b1 = ax.bar(x - w / 2, http, w, color=BLUE, label="HTTP/1.1", zorder=3)
    b2 = ax.bar(x + w / 2, h2, w, color=PURPLE, label="HTTPS + HTTP/2", zorder=3)
    _grid(ax)
    ax.set_xticks(x)
    ax.set_xticklabels(have)
    ax.set_ylabel("median latency (ms)")
    ax.set_title("Median latency by action — HTTP vs HTTPS + HTTP/2",
                 fontweight="bold", fontsize=14)
    ax.set_ylim(top=max(http + h2) * 1.2)
    for bars in (b1, b2):
        for bar in bars:
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height(),
                    f"{bar.get_height():.2f}", ha="center", va="bottom",
                    fontsize=9, color=GREY)
    ax.legend(frameon=False, loc="upper left")
    save(fig, out_dir, "perf_http_vs_h2")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(here, "data"))
    ap.add_argument("--out", default=os.path.dirname(here))  # images/
    args = ap.parse_args()

    print(f"data: {args.data}\nout:  {args.out}")
    sweep = load(args.data, "perf-sweep.json")
    scaling = load(args.data, "perf-scaling.json")
    result = load(args.data, "perf-result.json")

    if sweep:
        chart_knee(sweep, args.out)
        chart_percentiles(sweep, args.out)
    else:
        print("  (no perf-sweep.json — skipping knee + percentile charts)")
    if scaling:
        chart_matching_scaling(scaling, args.out)
    else:
        print("  (no perf-scaling.json — skipping matching-scaling chart)")
    if result:
        chart_http_vs_h2(result, args.out)

    if not any([sweep, scaling, result]):
        print("ERROR: no data files found in", args.data, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
