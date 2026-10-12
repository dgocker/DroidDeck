#!/usr/bin/env python3
"""Summarise game-diagnostics launch folders for a translator / settings A/B comparison.

Each launch folder (Setup > Diagnostics export, diagnostics/<game>/<id>/) holds manifest.json and,
when the launcher sampled them, fex_stats.csv (FEX's own counters), cpu.csv (CPU clocks, hottest
thermal zone) and performance.csv (the compositor's 10 s windows). This prints one row per launch and,
for launches of the same game with the same FEX build, FEX variables and session settings, the
median over the repetitions.

The first --warmup seconds of a launch are its cold phase (shader and block compilation, loading);
the rest is the warm phase. Both are reported apart: a cold-phase number says nothing about play.

    python3 tools/launch_report.py --warmup 90 exportA/diagnostics exportB/diagnostics

What the columns are, and what they are not:
  jit_ms/s      CPU time FEX spent compiling blocks, summed over the game's threads, per second of
                wall time. Not the time spent running translated code.
  signal_ms/s   CPU time in FEX's signal handling (SIGBUS fix-ups of unaligned atomics under TSO, SMC).
  sigbus/s, smc/s, miss/s, blocks/s   events per second of wall time.
  fps           median of the compositor's on-screen frames per second over the warm windows.
  low1%         1000 / the median of the windows' p99 commit interval: an estimate of the 1% low FPS
                from per-window percentiles, not from a run-wide distribution of frame times.
  clk_max/min   the highest and lowest per-CPU mean clock over the warm phase, MHz.
  cap           the lowest clock limit any cluster was held at in the warm phase (scaling_max_freq),
                MHz; hw_max is the clusters' hardware maxima from the launch's start. A cap under
                hw_max is a throttled or power-limited CPU, not a lightly loaded one.
  cpu_temp/gpu_temp  the hottest CPU and GPU thermal zones seen, degrees C.
  temp_max      the hottest thermal zone of any kind, degrees C, and its zone name (board sensors
                that read a constant value land here too; trust cpu_temp/gpu_temp first).
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
from pathlib import Path

RATES = ("sigbus", "smc", "cache_misses", "jit_blocks", "float_fallbacks", "disk_cache_misses")


def launches(roots: list[str]) -> list[Path]:
    found = []
    for root in roots:
        p = Path(root)
        if (p / "manifest.json").is_file():
            found.append(p)
        else:
            found.extend(sorted(m.parent for m in p.rglob("manifest.json")))
    return found


def rows(path: Path) -> list[dict]:
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as f:
            return list(csv.DictReader(f))
    except OSError:
        return []


def number(value) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def fex_phases(stats: list[dict], warmup: float, counter_hz: float | None) -> dict:
    """Cold and warm totals of FEX's counters, summed over the game's FEX processes. The file's rows
    are running totals per process, so a phase is the difference of a process's totals at its ends."""
    by_pid: dict[str, list[tuple[float, dict]]] = {}
    for r in stats:
        t = number(r.get("t_s"))
        if t is not None:
            by_pid.setdefault(r.get("pid", ""), []).append((t, r))
    names = [k for k in (stats[0].keys() if stats else []) if k not in ("t_s", "pid", "app_type", "fex_version", "threads")]
    cold = dict.fromkeys(names, 0.0)
    warm = dict.fromkeys(names, 0.0)
    versions = set()
    end = 0.0
    for samples in by_pid.values():
        samples.sort(key=lambda s: s[0])
        end = max(end, samples[-1][0])
        versions.update(s[1].get("fex_version", "") for s in samples)
        at_warmup = [s for s in samples if s[0] <= warmup]
        for name in names:
            last = number(samples[-1][1].get(name)) or 0.0
            mid = (number(at_warmup[-1][1].get(name)) or 0.0) if at_warmup else 0.0
            cold[name] += mid
            warm[name] += last - mid
    for phase in (cold, warm):
        for name in list(phase):
            if name.endswith("_ticks") and name[:-6] + "_ms" not in phase and counter_hz:
                phase[name[:-6] + "_ms"] = phase[name] * 1000.0 / counter_hz
    return {"cold": cold, "warm": warm, "end": end, "versions": sorted(v for v in versions if v)}


def per_second(phase: dict, name: str, seconds: float) -> float | None:
    if name not in phase or seconds <= 0:
        return None
    return phase[name] / seconds


def cpu_summary(samples: list[dict], warmup: float) -> dict:
    warm = [r for r in samples if (number(r.get("t_s")) or 0.0) > warmup] or samples
    means = []
    for col in (samples[0].keys() if samples else []):
        if col.startswith("cpu") and col.endswith("_khz"):
            values = [v for v in (number(r.get(col)) for r in warm) if v is not None]
            if values:
                means.append(statistics.fmean(values) / 1000.0)
    caps = [v for r in warm for col in r if col.startswith("policy") and col.endswith("_max_khz")
            for v in [number(r.get(col))] if v is not None]
    hottest, zone = None, ""
    for r in samples:
        t = number(r.get("thermal_max_millic"))
        if t is not None and (hottest is None or t > hottest):
            hottest, zone = t, r.get("thermal_max_zone", "")

    def peak(col):
        values = [v for v in (number(r.get(col)) for r in samples) if v is not None]
        return max(values) / 1000.0 if values else None
    return {"clk_max": max(means) if means else None, "clk_min": min(means) if means else None,
            "cap": min(caps) / 1000.0 if caps else None,
            "cpu_temp": peak("cpu_temp_max_millic"), "gpu_temp": peak("gpu_temp_max_millic"),
            "temp_max": hottest / 1000.0 if hottest is not None else None, "temp_zone": zone}


def frame_summary(windows: list[dict], warmup: float) -> dict:
    warm = [w for w in windows if (number(w.get("seconds_since_launch")) or 0.0) > warmup]
    fps = [v for v in (number(w.get("onscreen_fps")) for w in warm) if v is not None]
    p99 = [v for v in (number(w.get("commit_p99_ms")) for w in warm) if v]
    return {"fps": statistics.median(fps) if fps else None,
            "low1": 1000.0 / statistics.median(p99) if p99 else None, "windows": len(warm)}


def summarise(folder: Path, warmup: float) -> dict:
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    device = manifest.get("device") or {}
    launch = manifest.get("launch") or {}
    fex = launch.get("fex") or {}
    counter_hz = number(device.get("counter_hz"))
    phases = fex_phases(rows(folder / "fex_stats.csv"), warmup, counter_hz)
    duration = (number((manifest.get("time") or {}).get("duration_ms")) or 0.0) / 1000.0
    end = max(phases["end"], duration)
    cold_s, warm_s = min(warmup, end), max(0.0, end - warmup)
    out = {
        "launch": folder.name,
        "game": (manifest.get("game") or {}).get("name") or (manifest.get("game") or {}).get("key"),
        "status": (manifest.get("status") or {}).get("status"),
        "duration_s": end,  # a launch with no exit record runs to its last sample
        "fex_build": ",".join(sorted(set(v for v in (fex.get("build") or {}).values() if isinstance(v, str)))) or ",".join(phases["versions"]),
        "fex_env": " ".join("%s=%s" % kv for kv in sorted((fex.get("env") or {}).items())),
        "session": " ".join("%s=%s" % kv for kv in sorted(((manifest.get("graphics") or {}).get("session") or {}).items())),
        "sve_bits": device.get("sve_vector_bits"),
        "hw_max": "/".join("%.0f" % (p["cpuinfo_max_khz"] / 1000.0) for p in ((launch.get("host_cpu") or {}).get("policies") or {}).values()
                           if isinstance(p, dict) and isinstance(p.get("cpuinfo_max_khz"), (int, float))),
    }
    for phase, seconds in (("cold", cold_s), ("warm", warm_s)):
        p = phases[phase]
        out[phase + "_jit_ms_s"] = per_second(p, "jit_ms", seconds)
        out[phase + "_signal_ms_s"] = per_second(p, "signal_ms", seconds)
        for name in RATES:
            out["%s_%s_s" % (phase, name)] = per_second(p, name, seconds)
    out.update(cpu_summary(rows(folder / "cpu.csv"), warmup))
    out.update(frame_summary(rows(folder / "performance.csv"), warmup))
    return out


COLUMNS = (("launch", "%s"), ("status", "%s"), ("duration_s", "%.0f"), ("fps", "%.1f"), ("low1", "%.1f"),
           ("cold_jit_ms_s", "%.1f"), ("warm_jit_ms_s", "%.2f"), ("warm_signal_ms_s", "%.2f"),
           ("warm_sigbus_s", "%.1f"), ("warm_smc_s", "%.2f"), ("warm_cache_misses_s", "%.1f"),
           ("warm_jit_blocks_s", "%.1f"), ("clk_max", "%.0f"), ("clk_min", "%.0f"), ("cap", "%.0f"), ("hw_max", "%s"),
           ("cpu_temp", "%.1f"), ("gpu_temp", "%.1f"), ("temp_max", "%.1f"))


def cell(value, fmt):
    return "-" if value is None else fmt % value


def group_key(r: dict) -> tuple:
    return (r["game"], r["fex_build"], r["fex_env"], r["session"])


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("roots", nargs="+", help="launch folders, or directories holding them")
    parser.add_argument("--warmup", type=float, default=60.0, help="seconds counted as the cold phase (default 60)")
    parser.add_argument("--json", action="store_true", help="print every launch's numbers as JSON lines")
    args = parser.parse_args(argv)

    found = launches(args.roots)
    if not found:
        print("no launch folders (manifest.json) under %s" % ", ".join(args.roots), file=sys.stderr)
        return 1
    summaries = []
    for folder in found:
        try:
            summaries.append(summarise(folder, args.warmup))
        except (OSError, ValueError) as e:
            print("%s: %s" % (folder, e), file=sys.stderr)
    if args.json:
        for s in summaries:
            print(json.dumps(s, sort_keys=True))
        return 0
    groups: dict[tuple, list[dict]] = {}
    for s in summaries:
        groups.setdefault(group_key(s), []).append(s)
    for key, members in groups.items():
        game, build, env, session = key
        print("## %s | FEX %s | %s" % (game, build or "?", env or "no FEX variables from DroidDeck"))
        if session:
            print("   session: %s" % session)
        print("\t".join(c for c, _ in COLUMNS))
        for s in members:
            print("\t".join(cell(s.get(c), f) for c, f in COLUMNS))
        if len(members) > 1:
            med = []
            for c, f in COLUMNS:
                values = [s[c] for s in members if isinstance(s.get(c), (int, float))]
                med.append("median" if c == "launch" else "" if c == "status" else cell(statistics.median(values) if values else None, f))
            print("\t".join(med) + "\t(n=%d)" % len(members))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
