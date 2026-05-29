# GPX Add Time Tool — Design

## Goal

Add a standalone Python CLI to this repo that takes a GPX file lacking
`<time>` data and produces a new GPX file with a `<time>` tag on every
trackpoint, computed from a user-supplied start time and constant pace.

## Why

The existing `strava_traces_downloader.py` can already produce a GPX file
without time data (via its `-nt` flag), and users sometimes have GPX files
from other sources that lack timestamps. Many downstream tools (Strava
upload, training-load analyzers, replay tools) require timestamps. This
tool synthesizes plausible timestamps so such files become usable.

## Non-goals

- Variable pace by gradient, terrain, or heart-rate model.
- Pause/break detection or injection.
- Editing waypoints (`<wpt>`) or routes (`<rte>`) — only tracks (`<trk>`).
- Login or Strava API interaction.

## CLI

New script at the repo root: `gpx_add_time.py`.

```
usage: gpx_add_time.py [-h] -i INPUT -s START -p PACE [-o OUTPUT] [-v]

required:
  -i, --input PATH     Input GPX file (no time data).
  -s, --start ISO_DT   Start datetime, ISO 8601. Examples:
                         2024-03-15T07:30:00
                         2024-03-15T07:30:00+05:30
                       If no timezone offset is supplied, UTC is assumed.
  -p, --pace MM:SS     Average pace, minutes:seconds per km (e.g. 5:30).

optional:
  -o, --output PATH    Output GPX file. Default: <input-stem>_timed.gpx
                       written alongside the input.
  -v, --verbose        Print per-segment stats: point count, total distance,
                       computed finish time.
```

Behavior on the existing-output-file question: overwrite silently. Matches
the pattern of the other tools in this repo.

## Algorithm

1. Parse input with `gpxpy.parse(open(input_path))`.
2. Validate: at least one trackpoint exists across all tracks/segments.
   Exit 1 with a clear stderr message if none.
3. Convert pace `MM:SS` → seconds-per-meter:
   `pace_sec_per_m = (mm * 60 + ss) / 1000.0`.
4. Walk every trackpoint in order across all `<trk>` → `<trkseg>` →
   `<trkpt>`, maintaining `cumulative_meters` as a single running total
   (no reset at segment boundaries):
   - First point: `cumulative_meters = 0`.
   - Subsequent points: add `gpxpy.geo.haversine_distance(prev_lat,
     prev_lon, curr_lat, curr_lon)` (great-circle, 2D, ignores
     elevation).
   - Assign
     `point.time = start_time + timedelta(seconds=cumulative_meters * pace_sec_per_m)`.
5. Write modified GPX with `gpx.to_xml()` to output path.

Constant-pace, 2D distance. No smoothing. O(N) over trackpoints.

## Edge cases

| Case | Behavior |
| --- | --- |
| Input already has `<time>` tags | Overwrite, print warning to stderr. |
| Empty GPX / no trackpoints | Exit 1 with clear stderr message. |
| Two consecutive identical coordinates | Distance contribution is 0; both points get the same timestamp. |
| Pace not in `MM:SS` form | argparse-level error via custom type function. |
| Start datetime unparseable | `dateutil.parser.isoparse` failure, exit 1 with clear message. |
| Start datetime naive (no offset) | Treat as UTC. |
| Output file exists | Overwrite silently. |
| Routes (`<rte>`) and waypoints (`<wpt>`) | Left untouched. |
| Input file unreadable / not GPX | Let `gpxpy.parse` raise; print the exception message and exit 1. |

## Dependencies

- New: `gpxpy` — standard Python GPX parser. Add to `requirements.txt`.
- Already present: `python-dateutil` (for `isoparse`).

No changes to `strava_hack_tools_common/` — this tool does not need login.

## Files changed

- `gpx_add_time.py` — new file, ~80 lines.
- `requirements.txt` — add `gpxpy`.
- `README.md` — append a `### gpx_add_time.py` section matching the
  format of the existing tool sections (one usage block, one example).

## Verification

After implementation, the tool should be verified by:

1. Running it on the output of `strava_traces_downloader.py -nt` and
   confirming the resulting file has `<time>` tags on every trackpoint
   with monotonically increasing timestamps.
2. Confirming finish-time matches `start + total_distance * pace`
   (verbose output makes this checkable).
3. Sanity-checking the file parses cleanly with `gpxpy` after writing.
