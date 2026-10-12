#!/usr/bin/env python3
"""Summarise a session's compositor log (wayland.log) for an A/B comparison.

The compositor writes a `pacing` and a `perf` line every 10 s (waylandcomp/src/compositor.c). This reads
them from one or more logs, leaves out the first windows (warm-up), and prints one row per log: the
game's frame intervals as committed and as new frames were shown, how many frames were never shown,
commit -> present, the compositor's own time, GPU waits, which path frames took, the panel rate and the
frame cap. Percentiles over the run are the MEDIAN of the per-window percentiles (p99 the worst window's
too): the log keeps no raw samples, so this is a summary, not an exact run-wide distribution.

    python3 tools/pacing_report.py --skip 30 A/wayland.log B/wayland.log
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys

PACING = re.compile(
    r"pacing\s+last 10 s: (?P<commits>\d+) frames committed, interval p50/p95/p99/max "
    r"(?P<c50>[\d.]+)/(?P<c95>[\d.]+)/(?P<c99>[\d.]+)/(?P<cmax>[\d.]+) ms, (?P<chitch>\d+) at 2x p50 or longer \| "
    r"(?P<shown>\d+) shown \((?P<never>\d+) never shown\), interval p50/p95/p99/max "
    r"(?P<s50>[\d.]+)/(?P<s95>[\d.]+)/(?P<s99>[\d.]+)/(?P<smax>[\d.]+) ms, (?P<shitch>\d+) at 2x p50 or longer \| "
    r"commit -> present p50/p95/max (?P<l50>[\d.]+)/(?P<l95>[\d.]+)/(?P<lmax>[\d.]+) ms"
    r"(?: \| panel (?P<hz>[\d.]+) Hz nominal, vsync (?P<vsync>[\d.]+) ms measured, fps limit (?P<cap>\d+), "
    r"layer mode (?P<layer>on|off))?")
PERF = re.compile(
    r"perf\s+last 10 s: (?P<ticks>\d+) ticks, (?P<scenes>\d+) scenes, (?P<onscreen>\d+) on screen "
    r"\(copy (?P<copy>\d+), zero-copy (?P<zero>\d+), layer copy (?P<layercopy>\d+)\) \| "
    r"render_scene (?P<rs_avg>[\d.]+)/(?P<rs_max>[\d.]+) ms .*?"
    r"fence wait (?P<fw_avg>[\d.]+)/(?P<fw_max>[\d.]+) ms \((?P<waits>\d+),"
    r"(?:.*GPU waits timed out (?P<timeouts>\d+), uses skipped (?P<skips>\d+), destroys deferred (?P<deferred>\d+))?")


def read(path: str) -> tuple[list[dict], list[dict]]:
    pacing, perf = [], []
    with open(path, encoding="utf-8", errors="replace") as log:
        for line in log:
            m = PACING.search(line)
            if m:
                pacing.append(m.groupdict())
                continue
            m = PERF.search(line)
            if m:
                perf.append(m.groupdict())
    return pacing, perf


def median(values: list[float]) -> float:
    return statistics.median(values) if values else float("nan")


def summarise(pacing: list[dict], perf: list[dict], skip: int = 0) -> dict:
    """Per-run figures from the windows after the first `skip` pacing windows (and as many perf ones)."""
    pacing, perf = pacing[skip:], perf[skip:]
    f = lambda rows, key: [float(r[key]) for r in rows if r.get(key) is not None]
    i = lambda rows, key: sum(int(r[key]) for r in rows if r.get(key) is not None)
    out = {
        "windows": len(pacing),
        "committed": i(pacing, "commits"),
        "shown": i(pacing, "shown"),
        "never_shown": i(pacing, "never"),
        "commit_p50": median(f(pacing, "c50")), "commit_p95": median(f(pacing, "c95")),
        "commit_p99": median(f(pacing, "c99")), "commit_p99_worst": max(f(pacing, "c99"), default=float("nan")),
        "commit_hitches": i(pacing, "chitch"),
        "shown_p50": median(f(pacing, "s50")), "shown_p95": median(f(pacing, "s95")),
        "shown_p99": median(f(pacing, "s99")), "shown_p99_worst": max(f(pacing, "s99"), default=float("nan")),
        "shown_hitches": i(pacing, "shitch"),
        "latency_p50": median(f(pacing, "l50")), "latency_p95": median(f(pacing, "l95")),
        "latency_max": max(f(pacing, "lmax"), default=float("nan")),
        "render_avg": median(f(perf, "rs_avg")), "render_max": max(f(perf, "rs_max"), default=float("nan")),
        "fence_avg": median(f(perf, "fw_avg")), "fence_max": max(f(perf, "fw_max"), default=float("nan")),
        "gpu_timeouts": i(perf, "timeouts"), "gpu_skips": i(perf, "skips"),
        "copy": i(perf, "copy"), "zero_copy": i(perf, "zero"), "layer_copy": i(perf, "layercopy"),
        "panel_hz": sorted({r["hz"] for r in pacing if r.get("hz")}),
        "fps_limit": sorted({r["cap"] for r in pacing if r.get("cap")}),
        "layer_mode": sorted({r["layer"] for r in pacing if r.get("layer")}),
    }
    seconds = 10.0 * len(pacing)
    out["committed_per_s"] = out["committed"] / seconds if seconds else float("nan")
    out["shown_per_s"] = out["shown"] / seconds if seconds else float("nan")
    return out


COLUMNS = (
    ("windows", "10 s windows", "{:d}"),
    ("committed_per_s", "committed frames/s", "{:.1f}"),
    ("shown_per_s", "shown frames/s", "{:.1f}"),
    ("never_shown", "never shown", "{:d}"),
    ("commit_p50", "commit interval p50 ms", "{:.1f}"),
    ("commit_p95", "commit interval p95 ms", "{:.1f}"),
    ("commit_p99", "commit interval p99 ms", "{:.1f}"),
    ("commit_p99_worst", "commit p99 worst window ms", "{:.1f}"),
    ("commit_hitches", "commit intervals >= 2x p50", "{:d}"),
    ("shown_p50", "shown interval p50 ms", "{:.1f}"),
    ("shown_p95", "shown interval p95 ms", "{:.1f}"),
    ("shown_p99", "shown interval p99 ms", "{:.1f}"),
    ("shown_p99_worst", "shown p99 worst window ms", "{:.1f}"),
    ("shown_hitches", "shown intervals >= 2x p50", "{:d}"),
    ("latency_p50", "commit->present p50 ms", "{:.1f}"),
    ("latency_p95", "commit->present p95 ms", "{:.1f}"),
    ("render_avg", "render_scene avg ms", "{:.2f}"),
    ("render_max", "render_scene max ms", "{:.2f}"),
    ("fence_avg", "fence wait avg ms", "{:.2f}"),
    ("fence_max", "fence wait max ms", "{:.2f}"),
    ("gpu_timeouts", "GPU waits timed out", "{:d}"),
    ("copy", "copy-path scenes", "{:d}"),
    ("zero_copy", "zero-copy frames", "{:d}"),
    ("layer_copy", "layer-copy frames", "{:d}"),
    ("panel_hz", "panel Hz (nominal)", "{}"),
    ("fps_limit", "fps limit", "{}"),
    ("layer_mode", "layer mode", "{}"),
)


def table(names: list[str], runs: list[dict]) -> str:
    width = max(len(label) for _, label, _ in COLUMNS)
    cells = [[fmt.format(run[key]) if not isinstance(run[key], list) else (",".join(run[key]) or "-")
              for key, _, fmt in COLUMNS] for run in runs]
    col = [max(len(n), *(len(c[i]) for c in cells)) if cells else len(n) for n, i in zip(names, range(len(names)))]
    lines = ["".ljust(width) + "  " + "  ".join(n.rjust(w) for n, w in zip(names, col))]
    for row, (_, label, _) in enumerate(COLUMNS):
        lines.append(label.ljust(width) + "  " + "  ".join(c[row].rjust(w) for c, w in zip(cells, col)))
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("logs", nargs="+", help="wayland.log files, one per run")
    parser.add_argument("--skip", type=int, default=0, help="10 s windows to leave out at the start (warm-up)")
    args = parser.parse_args(argv)
    runs = []
    for path in args.logs:
        pacing, perf = read(path)
        if len(pacing) <= args.skip:
            print("%s: %d pacing windows, none left after --skip %d" % (path, len(pacing), args.skip), file=sys.stderr)
        runs.append(summarise(pacing, perf, args.skip))
    print(table(args.logs, runs))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
