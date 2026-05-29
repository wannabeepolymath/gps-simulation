#!/usr/bin/env python3
"""Add time information to a GPX file using a constant pace.

Walks every trackpoint, accumulates great-circle distance from the
previous point, and assigns a timestamp computed from the user-supplied
start time and pace.
"""

import argparse
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import gpxpy
import gpxpy.geo
from dateutil import parser as dateparser


PACE_RE = re.compile(r"^(\d+):([0-5]?\d)$")


def parse_pace(value: str) -> float:
    """Parse MM:SS pace and return seconds-per-meter."""
    m = PACE_RE.match(value)
    if not m:
        raise argparse.ArgumentTypeError(
            f"pace must be MM:SS (e.g. 5:30), got {value!r}"
        )
    minutes, seconds = int(m.group(1)), int(m.group(2))
    return (minutes * 60 + seconds) / 1000.0


def parse_start(value: str) -> datetime:
    try:
        dt = dateparser.isoparse(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"start must be ISO 8601 datetime, got {value!r}: {exc}"
        )
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def add_times(gpx, start: datetime, pace_sec_per_m: float, verbose: bool) -> int:
    """Assign timestamps to every trackpoint. Returns total point count."""
    had_existing_time = False
    cumulative_m = 0.0
    total_points = 0
    prev = None

    for track_idx, track in enumerate(gpx.tracks):
        for seg_idx, segment in enumerate(track.segments):
            seg_start_m = cumulative_m
            for point in segment.points:
                if point.time is not None:
                    had_existing_time = True
                if prev is not None:
                    cumulative_m += gpxpy.geo.haversine_distance(
                        prev.latitude, prev.longitude,
                        point.latitude, point.longitude,
                    )
                point.time = start + timedelta(seconds=cumulative_m * pace_sec_per_m)
                prev = point
                total_points += 1
            if verbose:
                seg_m = cumulative_m - seg_start_m
                seg_finish = start + timedelta(seconds=cumulative_m * pace_sec_per_m)
                print(
                    f"  track {track_idx} seg {seg_idx}: "
                    f"{len(segment.points)} points, {seg_m:.1f} m, "
                    f"ends at {seg_finish.isoformat()}",
                    file=sys.stderr,
                )

    if had_existing_time:
        print(
            "warning: input already had <time> tags; overwriting them.",
            file=sys.stderr,
        )
    return total_points


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Add time information to a GPX file using a constant pace.",
    )
    ap.add_argument("-i", "--input", required=True, type=Path,
                    help="Input GPX file (no time data).")
    ap.add_argument("-s", "--start", required=True, type=parse_start,
                    metavar="ISO_DT",
                    help="Start datetime, ISO 8601. Naive values are UTC.")
    ap.add_argument("-p", "--pace", required=True, type=parse_pace,
                    metavar="MM:SS",
                    help="Average pace, minutes:seconds per km (e.g. 5:30).")
    ap.add_argument("-o", "--output", type=Path,
                    help="Output GPX file. Default: <input-stem>_timed.gpx")
    ap.add_argument("-v", "--verbose", action="store_true",
                    help="Print per-segment stats to stderr.")
    args = ap.parse_args(argv)

    if not args.input.is_file():
        print(f"error: input file not found: {args.input}", file=sys.stderr)
        return 1

    output = args.output or args.input.with_name(args.input.stem + "_timed.gpx")

    try:
        with args.input.open("r") as f:
            gpx = gpxpy.parse(f)
    except Exception as exc:
        print(f"error: could not parse {args.input}: {exc}", file=sys.stderr)
        return 1

    point_count = sum(
        len(seg.points) for trk in gpx.tracks for seg in trk.segments
    )
    if point_count == 0:
        print("error: GPX file has no trackpoints.", file=sys.stderr)
        return 1

    total = add_times(gpx, args.start, args.pace, args.verbose)

    with output.open("w") as f:
        f.write(gpx.to_xml())

    if args.verbose:
        print(f"wrote {total} trackpoints to {output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
