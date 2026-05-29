#!/usr/bin/env python3
"""Synthesize a realistic timed GPX from a starting coordinate.

Pipeline:
    geometry (OSRM or imported polyline)
      -> elevation along polyline (opentopodata)
      -> pace(d) model + integrate to t(d)
      -> 1 Hz wall-clock sampler
      -> optional pauses
      -> GPS jitter
      -> GPX writer

See docs/superpowers/specs/2026-05-25-gpx-build-design.md for the design.
"""

import argparse
import math
import random
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import gpxpy
import gpxpy.geo
import numpy as np
import requests
from dateutil import parser as dateparser


OSRM_BASE = "https://router.project-osrm.org"
OPENTOPO_BASE = "https://api.opentopodata.org/v1"
ELEVATION_QUERY_SPACING_M = 25.0
PACE_GRID_M = 10.0
EARTH_M_PER_DEG_LAT = 111_320.0


# ---------- CLI parsing helpers ----------

PACE_RE = re.compile(r"^(\d+):([0-5]?\d)$")
COORD_RE = re.compile(r"^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$")


def parse_pace(value: str) -> float:
    m = PACE_RE.match(value)
    if not m:
        raise argparse.ArgumentTypeError(
            f"pace must be MM:SS (e.g. 5:30), got {value!r}"
        )
    minutes, seconds = int(m.group(1)), int(m.group(2))
    return (minutes * 60 + seconds) / 1000.0


def parse_start_time(value: str) -> datetime:
    try:
        dt = dateparser.isoparse(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"start-time must be ISO 8601, got {value!r}: {exc}"
        )
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def parse_coord(value: str) -> tuple[float, float]:
    m = COORD_RE.match(value)
    if not m:
        raise argparse.ArgumentTypeError(
            f"coordinate must be LAT,LON (e.g. 12.97,77.59), got {value!r}"
        )
    lat, lon = float(m.group(1)), float(m.group(2))
    if not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
        raise argparse.ArgumentTypeError(f"coordinate out of range: {value!r}")
    return (lat, lon)


# ---------- Geodesy ----------

def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    return gpxpy.geo.haversine_distance(lat1, lon1, lat2, lon2)


def destination_point(lat: float, lon: float, bearing_deg: float, dist_m: float):
    """Compute destination given start, bearing (deg), distance (m). Spherical."""
    R = 6_371_000.0
    br = math.radians(bearing_deg)
    lat1 = math.radians(lat)
    lon1 = math.radians(lon)
    ang = dist_m / R
    lat2 = math.asin(math.sin(lat1) * math.cos(ang) + math.cos(lat1) * math.sin(ang) * math.cos(br))
    lon2 = lon1 + math.atan2(
        math.sin(br) * math.sin(ang) * math.cos(lat1),
        math.cos(ang) - math.sin(lat1) * math.sin(lat2),
    )
    return (math.degrees(lat2), math.degrees(lon2))


def cumulative_distances(polyline: list[tuple[float, float]]) -> np.ndarray:
    if len(polyline) < 2:
        return np.zeros(len(polyline))
    d = np.zeros(len(polyline))
    for i in range(1, len(polyline)):
        d[i] = d[i - 1] + haversine_m(*polyline[i - 1], *polyline[i])
    return d


def interpolate_polyline(polyline, cum_d: np.ndarray, target_d: float):
    """Linearly interpolate (lat, lon) at cumulative distance target_d."""
    if target_d <= 0:
        return polyline[0]
    if target_d >= cum_d[-1]:
        return polyline[-1]
    i = int(np.searchsorted(cum_d, target_d))
    d0, d1 = cum_d[i - 1], cum_d[i]
    f = (target_d - d0) / (d1 - d0) if d1 > d0 else 0.0
    lat0, lon0 = polyline[i - 1]
    lat1, lon1 = polyline[i]
    return (lat0 + (lat1 - lat0) * f, lon0 + (lon1 - lon0) * f)


# ---------- Stage 1: geometry ----------

def osrm_route(coords, profile: str, service: str = "route", extra_qs: str = ""):
    """Call OSRM and return (polyline, total_meters)."""
    coord_str = ";".join(f"{lon},{lat}" for lat, lon in coords)
    url = f"{OSRM_BASE}/{service}/v1/{profile}/{coord_str}"
    params = {"overview": "full", "geometries": "geojson"}
    if extra_qs:
        for kv in extra_qs.split("&"):
            k, v = kv.split("=", 1)
            params[k] = v
    r = requests.get(url, params=params, timeout=30)
    r.raise_for_status()
    data = r.json()
    if data.get("code") != "Ok":
        raise RuntimeError(f"OSRM error: {data.get('code')} {data.get('message', '')}")
    if service == "trip":
        trips = data.get("trips", [])
        if not trips:
            raise RuntimeError("OSRM trip returned no trips")
        trip = trips[0]
        coords = trip["geometry"]["coordinates"]
        meters = trip["distance"]
    else:
        routes = data.get("routes", [])
        if not routes:
            raise RuntimeError("OSRM returned no routes")
        route = routes[0]
        coords = route["geometry"]["coordinates"]
        meters = route["distance"]
    polyline = [(lat, lon) for lon, lat in coords]
    return polyline, float(meters)


def geometry_from_end(start, end, profile):
    return osrm_route([start, end], profile, service="route")


def geometry_from_waypoints(start, waypoints, profile):
    return osrm_route([start] + waypoints, profile, service="route")


def geometry_from_loop(start, target_km, profile, rng):
    target_m = target_km * 1000.0
    radius_m = target_m / (2 * math.pi)
    best = None
    best_err = float("inf")
    for attempt in range(3):
        scale = 1.0 if attempt == 0 else (target_m / best[1] if best else 1.0)
        r = radius_m * scale
        bearings = sorted(rng.uniform(0, 360) for _ in range(3))
        wps = [destination_point(*start, b, r) for b in bearings]
        coords = [start] + wps + [start]
        try:
            polyline, meters = osrm_route(
                coords, profile, service="trip",
                extra_qs="roundtrip=true&source=first&destination=last",
            )
        except RuntimeError:
            continue
        err = abs(meters - target_m) / target_m
        if err < best_err:
            best = (polyline, meters)
            best_err = err
        if err < 0.15:
            break
    if best is None:
        raise RuntimeError("OSRM /trip failed on all loop attempts")
    if best_err >= 0.15:
        print(
            f"warning: loop distance {best[1]/1000:.2f} km is {best_err*100:.0f}% off target "
            f"({target_km} km) after 3 retries",
            file=sys.stderr,
        )
    return best


def geometry_from_polyline_file(path: Path):
    with path.open() as f:
        gpx = gpxpy.parse(f)
    pts = []
    had_time = False
    for trk in gpx.tracks:
        for seg in trk.segments:
            for p in seg.points:
                pts.append((p.latitude, p.longitude))
                if p.time is not None:
                    had_time = True
    if len(pts) < 2:
        raise RuntimeError(f"polyline file has only {len(pts)} point(s); need >=2")
    if had_time:
        print("warning: imported polyline had <time> tags; ignoring them.", file=sys.stderr)
    cum = cumulative_distances(pts)
    return pts, float(cum[-1])


# ---------- Stage 2: elevation along polyline ----------

def query_points_along(polyline, cum_d: np.ndarray, spacing_m: float):
    """Build a list of (lat, lon, d) every spacing_m along the polyline."""
    total = cum_d[-1]
    n = max(2, int(math.ceil(total / spacing_m)) + 1)
    ds = np.linspace(0, total, n)
    pts = [interpolate_polyline(polyline, cum_d, d) for d in ds]
    return list(zip([p[0] for p in pts], [p[1] for p in pts], ds))


def elevation_lookup(query_pts, dataset: str, verbose: bool):
    """Return numpy array of elevations aligned with query_pts."""
    out = np.zeros(len(query_pts))
    batch_size = 100
    for i in range(0, len(query_pts), batch_size):
        chunk = query_pts[i:i + batch_size]
        loc = "|".join(f"{lat:.6f},{lon:.6f}" for lat, lon, _ in chunk)
        try:
            r = requests.get(
                f"{OPENTOPO_BASE}/{dataset}",
                params={"locations": loc, "interpolation": "bilinear"},
                timeout=30,
            )
            r.raise_for_status()
            data = r.json()
            results = data.get("results", [])
            for j, res in enumerate(results):
                el = res.get("elevation")
                out[i + j] = el if el is not None else float("nan")
        except Exception as exc:
            print(f"warning: opentopodata batch {i}: {exc}; using 0 m for these points",
                  file=sys.stderr)
            for j in range(len(chunk)):
                out[i + j] = 0.0
        if i + batch_size < len(query_pts):
            time.sleep(1.1)
        if verbose:
            print(f"  elevation batch {i // batch_size + 1}: {len(chunk)} points",
                  file=sys.stderr)
    # Fill NaN by linear interpolation from neighbors.
    nan_mask = np.isnan(out)
    if nan_mask.any():
        idx = np.arange(len(out))
        out[nan_mask] = np.interp(idx[nan_mask], idx[~nan_mask], out[~nan_mask])
    return out


# ---------- Stage 3: pace model + integration ----------

def build_pace_curve(
    cum_d_query: np.ndarray,
    elevations: np.ndarray,
    base_pace_spm: float,
    pace_jitter: float,
    grade_penalty: float,
    warmup_sec: float,
    cooldown_sec: float,
    rng: np.random.Generator,
):
    """Return (d_grid, pace_per_m) sampled on a fine grid."""
    total_m = cum_d_query[-1]
    n = max(2, int(math.ceil(total_m / PACE_GRID_M)) + 1)
    d_grid = np.linspace(0, total_m, n)

    # Interpolate elevation onto fine grid.
    ele_grid = np.interp(d_grid, cum_d_query, elevations)

    # Grade as %: smooth via centered differences over ~50 m window.
    window_m = 50.0
    win = max(2, int(window_m / PACE_GRID_M))
    de = np.zeros_like(ele_grid)
    de[win:-win] = (ele_grid[2 * win:] - ele_grid[:-2 * win]) / (2 * win * PACE_GRID_M)
    de[:win] = de[win]
    de[-win:] = de[-win - 1]
    grade_pct = de * 100.0
    grade_factor = 1.0 + grade_penalty * grade_pct

    # Per-sample jitter at ~PACE_GRID_M, smoothed slightly.
    raw_noise = rng.normal(0.0, pace_jitter, size=n)
    if n >= 5:
        kernel = np.array([0.1, 0.2, 0.4, 0.2, 0.1])
        raw_noise = np.convolve(raw_noise, kernel, mode="same")
    jitter_factor = 1.0 + raw_noise

    # Drift (fatigue) factor.
    drift_factor = 1.0 + 0.03 * np.sin(2 * math.pi * d_grid / max(total_m, 1.0))

    # Warmup / cooldown ramps on distance proxy.
    warmup_m = warmup_sec / base_pace_spm
    cooldown_m = cooldown_sec / base_pace_spm
    ramp = np.ones(n)
    if warmup_m > 0:
        mask = d_grid < warmup_m
        ramp[mask] = 1.4 - 0.4 * (d_grid[mask] / warmup_m)
    if cooldown_m > 0:
        mask = d_grid > (total_m - cooldown_m)
        ramp[mask] = 1.0 + 0.4 * ((d_grid[mask] - (total_m - cooldown_m)) / cooldown_m)

    factor = grade_factor * jitter_factor * drift_factor * ramp
    factor = np.clip(factor, 0.5, 2.5)

    pace_per_m = base_pace_spm * factor
    return d_grid, pace_per_m


def integrate_time(d_grid: np.ndarray, pace_per_m: np.ndarray) -> np.ndarray:
    """Trapezoidal integration of pace over distance, giving t(d)."""
    t = np.zeros_like(d_grid)
    if len(d_grid) >= 2:
        dd = np.diff(d_grid)
        avg_pace = 0.5 * (pace_per_m[1:] + pace_per_m[:-1])
        t[1:] = np.cumsum(dd * avg_pace)
    return t


# ---------- Stage 4: 1 Hz wall-clock sampler ----------

def sample_at_hz(
    polyline,
    cum_d_polyline: np.ndarray,
    cum_d_query: np.ndarray,
    elevations: np.ndarray,
    d_grid: np.ndarray,
    t_grid: np.ndarray,
    hz: float,
):
    """Emit (lat, lon, ele, t_seconds) at the requested sample rate."""
    total_t = t_grid[-1]
    dt = 1.0 / hz
    n = max(2, int(math.ceil(total_t / dt)) + 1)
    ts = np.linspace(0, total_t, n)

    # Invert t(d) to get d(t) at each sample time.
    ds = np.interp(ts, t_grid, d_grid)

    samples = []
    for d, t in zip(ds, ts):
        lat, lon = interpolate_polyline(polyline, cum_d_polyline, d)
        ele = float(np.interp(d, cum_d_query, elevations))
        samples.append((lat, lon, ele, float(t)))
    return samples


# ---------- Stage 5: pauses ----------

def inject_pauses(samples, num_pauses: int, warmup_sec: float, cooldown_sec: float, rng):
    if num_pauses <= 0 or len(samples) < 10:
        return samples
    total_t = samples[-1][3]
    usable_start = warmup_sec
    usable_end = total_t - cooldown_sec
    if usable_end <= usable_start:
        return samples

    # Pick pause positions in (sorted) time order.
    pause_times = sorted(rng.uniform(usable_start, usable_end) for _ in range(num_pauses))
    pause_durations = [rng.uniform(10, 30) for _ in range(num_pauses)]

    out = []
    pi = 0
    shift = 0.0
    for lat, lon, ele, t in samples:
        new_t = t + shift
        out.append((lat, lon, ele, new_t))
        while pi < len(pause_times) and t >= pause_times[pi]:
            dur = pause_durations[pi]
            # Duplicate point each second of the pause.
            for k in range(1, int(dur) + 1):
                out.append((lat, lon, ele, new_t + k))
            shift += dur
            pi += 1
    return out


# ---------- Stage 6: GPS jitter ----------

def apply_gps_jitter(samples, sigma_m: float, rng: np.random.Generator,
                     correlation: float = 0.95):
    """Apply 2D Gaussian GPS noise that is time-correlated (AR(1)).

    Real consumer GPS error is dominated by slowly-varying satellite-geometry
    and multipath effects, so adjacent samples are highly correlated. Independent
    Gaussian noise per sample would inflate inter-sample haversine distance and
    blow up any pace computed from the output. AR(1) with correlation ~0.95
    preserves the marginal sigma while keeping the per-step differential small.
    """
    if sigma_m <= 0 or len(samples) < 3:
        return samples
    alpha = correlation
    drive = math.sqrt(max(1.0 - alpha * alpha, 0.0))
    n = len(samples)
    # Generate two AR(1) sequences (lat-axis and lon-axis offsets in meters).
    nx = np.zeros(n)
    ny = np.zeros(n)
    nx[0] = rng.normal(0.0, sigma_m)
    ny[0] = rng.normal(0.0, sigma_m)
    for i in range(1, n):
        nx[i] = alpha * nx[i - 1] + drive * rng.normal(0.0, sigma_m)
        ny[i] = alpha * ny[i - 1] + drive * rng.normal(0.0, sigma_m)

    out = list(samples)
    for i in range(1, n - 1):
        lat, lon, ele, t = samples[i]
        d_lat_off = ny[i] / EARTH_M_PER_DEG_LAT
        d_lon_off = nx[i] / (
            EARTH_M_PER_DEG_LAT * max(math.cos(math.radians(lat)), 1e-6)
        )
        out[i] = (lat + d_lat_off, lon + d_lon_off, ele, t)
    return out


# ---------- Stage 7: GPX writer ----------

def write_gpx(samples, start_time: datetime, name: str, creator: str, output_path: Path):
    gpx = gpxpy.gpx.GPX()
    gpx.creator = creator
    track = gpxpy.gpx.GPXTrack(name=name)
    gpx.tracks.append(track)
    seg = gpxpy.gpx.GPXTrackSegment()
    track.segments.append(seg)
    for lat, lon, ele, t in samples:
        pt = gpxpy.gpx.GPXTrackPoint(
            latitude=lat,
            longitude=lon,
            elevation=ele,
            time=start_time + timedelta(seconds=t),
        )
        seg.points.append(pt)
    with output_path.open("w") as f:
        f.write(gpx.to_xml())


# ---------- Main ----------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Synthesize a realistic timed GPX from a starting coordinate.",
    )
    ap.add_argument("-s", "--start", type=parse_coord, metavar="LAT,LON",
                    help="Starting coordinate (required except with --polyline).")
    ap.add_argument("-t", "--start-time", required=True, type=parse_start_time,
                    metavar="ISO_DT",
                    help="Activity start time, ISO 8601 (naive values are UTC).")
    ap.add_argument("-p", "--pace", required=True, type=parse_pace, metavar="MM:SS",
                    help="Base pace, minutes:seconds per km.")

    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--end", type=parse_coord, metavar="LAT,LON",
                      help="M1: route from start to this endpoint.")
    mode.add_argument("--waypoint", type=parse_coord, action="append", metavar="LAT,LON",
                      help="M2: waypoint in order (repeatable, >=1).")
    mode.add_argument("--loop", action="store_true",
                      help="M3: auto-generated round trip from start.")
    mode.add_argument("--polyline", type=Path, metavar="FILE",
                      help="M4: import polyline from a GPX file.")

    ap.add_argument("--distance", type=float, metavar="KM",
                    help="M3 target loop distance in km (required with --loop).")
    ap.add_argument("--profile", choices=("foot", "bike", "car"), default="foot")
    ap.add_argument("--spacing-hz", type=float, default=1.0,
                    help="Sample rate in Hz (default 1.0).")
    ap.add_argument("--pace-jitter", type=float, default=5.0,
                    help="Per-sample Gaussian noise sigma on pace, percent (default 5).")
    ap.add_argument("--grade-penalty", type=float, default=0.033,
                    help="Uphill/downhill pace sensitivity (default 0.033).")
    ap.add_argument("--gps-noise", type=float, default=2.0,
                    help="Lateral GPS noise sigma in meters (default 2).")
    ap.add_argument("--warmup-sec", type=float, default=20.0)
    ap.add_argument("--cooldown-sec", type=float, default=20.0)
    ap.add_argument("--pauses", type=int, default=0,
                    help="Number of random micro-pauses (default 0).")
    ap.add_argument("--no-elevation", action="store_true",
                    help="Skip opentopodata; all elevations set to 0.")
    ap.add_argument("--elevation-dataset", default="aster30m",
                    help="opentopodata dataset (default aster30m).")
    ap.add_argument("--name", default="Activity",
                    help="GPX track <name>.")
    ap.add_argument("--creator", default="StravaGPX iPhone",
                    help='GPX root creator attribute.')
    ap.add_argument("--seed", type=int, default=None,
                    help="RNG seed for reproducible jitter.")
    ap.add_argument("-o", "--output", type=Path,
                    help="Output GPX path. Default depends on input mode.")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="Per-stage stats to stderr.")
    args = ap.parse_args(argv)

    # Mode-specific validation.
    if args.polyline is None and args.start is None:
        ap.error("--start is required unless --polyline is given")
    if args.loop and args.distance is None:
        ap.error("--loop requires --distance KM")
    if args.distance is not None and not args.loop:
        ap.error("--distance is only valid with --loop")
    if args.waypoint is not None and len(args.waypoint) < 1:
        ap.error("--waypoint requires at least one coordinate")

    # RNGs.
    py_rng = random.Random(args.seed)
    np_rng = np.random.default_rng(args.seed)

    # Stage 1: geometry.
    if args.verbose:
        print("stage 1: geometry", file=sys.stderr)
    if args.polyline is not None:
        if not args.polyline.is_file():
            print(f"error: polyline file not found: {args.polyline}", file=sys.stderr)
            return 1
        polyline, total_m = geometry_from_polyline_file(args.polyline)
        # In M4 the start coord comes from the file.
        if args.start is None:
            args.start = polyline[0]
    elif args.end is not None:
        if args.end == args.start:
            print("error: --end equals --start; use --loop for a round trip.",
                  file=sys.stderr)
            return 1
        polyline, total_m = geometry_from_end(args.start, args.end, args.profile)
    elif args.waypoint is not None:
        polyline, total_m = geometry_from_waypoints(
            args.start, args.waypoint, args.profile
        )
    else:  # --loop
        polyline, total_m = geometry_from_loop(
            args.start, args.distance, args.profile, py_rng
        )

    if total_m <= 0 or len(polyline) < 2:
        print("error: geometry stage produced an empty route.", file=sys.stderr)
        return 1
    if args.verbose:
        print(f"  polyline vertices: {len(polyline)}, total distance: {total_m:.1f} m",
              file=sys.stderr)

    cum_d_polyline = cumulative_distances(polyline)

    # Stage 2: elevation along polyline.
    query_pts = query_points_along(polyline, cum_d_polyline, ELEVATION_QUERY_SPACING_M)
    cum_d_query = np.array([d for _, _, d in query_pts])
    if args.no_elevation:
        elevations = np.zeros(len(query_pts))
        if args.verbose:
            print("stage 2: elevation skipped (--no-elevation)", file=sys.stderr)
    else:
        if args.verbose:
            print(f"stage 2: elevation ({len(query_pts)} points, dataset {args.elevation_dataset})",
                  file=sys.stderr)
        elevations = elevation_lookup(query_pts, args.elevation_dataset, args.verbose)

    # Stage 3: pace model + integration.
    if args.verbose:
        print("stage 3: pace model + integration", file=sys.stderr)
    d_grid, pace_per_m = build_pace_curve(
        cum_d_query,
        elevations,
        args.pace,
        args.pace_jitter / 100.0,
        args.grade_penalty,
        args.warmup_sec,
        args.cooldown_sec,
        np_rng,
    )
    t_grid = integrate_time(d_grid, pace_per_m)
    total_duration = float(t_grid[-1])

    # Adjust warmup/cooldown if total_duration < warmup + cooldown.
    if args.warmup_sec + args.cooldown_sec > total_duration:
        scale = total_duration / (args.warmup_sec + args.cooldown_sec) * 0.9
        new_warm = args.warmup_sec * scale
        new_cool = args.cooldown_sec * scale
        print(
            f"warning: total duration {total_duration:.0f}s is shorter than warmup+cooldown; "
            f"shrinking to {new_warm:.0f}s + {new_cool:.0f}s",
            file=sys.stderr,
        )
        # Re-run pace + integration with shrunk ramps.
        d_grid, pace_per_m = build_pace_curve(
            cum_d_query, elevations, args.pace,
            args.pace_jitter / 100.0, args.grade_penalty,
            new_warm, new_cool, np.random.default_rng(args.seed),
        )
        t_grid = integrate_time(d_grid, pace_per_m)
        total_duration = float(t_grid[-1])

    if args.verbose:
        print(f"  total duration: {total_duration:.1f} s "
              f"({total_duration / 60:.1f} min)", file=sys.stderr)

    # Stage 4: 1 Hz wall-clock sampler.
    samples = sample_at_hz(
        polyline, cum_d_polyline, cum_d_query, elevations,
        d_grid, t_grid, args.spacing_hz,
    )
    if args.verbose:
        print(f"stage 4: sampled {len(samples)} points at {args.spacing_hz} Hz",
              file=sys.stderr)

    # Stage 5: pauses.
    samples = inject_pauses(
        samples, args.pauses, args.warmup_sec, args.cooldown_sec, py_rng,
    )
    if args.verbose and args.pauses > 0:
        print(f"stage 5: injected {args.pauses} pause(s), total samples now {len(samples)}",
              file=sys.stderr)

    # Stage 6: GPS jitter.
    samples = apply_gps_jitter(samples, args.gps_noise, np_rng)
    if args.verbose:
        print(f"stage 6: applied GPS jitter (sigma={args.gps_noise} m)", file=sys.stderr)

    # Stage 7: write GPX.
    if args.output:
        output_path = args.output
    elif args.polyline is not None:
        output_path = args.polyline.with_name(args.polyline.stem + "_built.gpx")
    else:
        ts = args.start_time.strftime("%Y%m%dT%H%M%S")
        output_path = Path(f"activity_{ts}.gpx")
    write_gpx(samples, args.start_time, args.name, args.creator, output_path)

    print(f"wrote {len(samples)} trackpoints to {output_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
