/**
 * Tiny GPX parser — extracts trackpoints to compute summary metadata
 * (distance, duration, point count). Mirrors the Android-side parser:
 * v1 requires timed trackpoints.
 */

export interface TrackPoint {
    lat: number;
    lon: number;
    ele?: number;
    time: Date;
}

export class GpxParseError extends Error {}

const TRKPT_RE = /<trkpt\b([^>]*)>([\s\S]*?)<\/trkpt>/g;
const SELF_CLOSING_TRKPT_RE = /<trkpt\b([^>]*?)\/>/g;
const ATTR_RE = /(\w+)\s*=\s*"([^"]*)"/g;
const ELE_RE = /<ele>\s*([-+0-9.eE]+)\s*<\/ele>/;
const TIME_RE = /<time>\s*([^<\s]+)\s*<\/time>/;

export function parseGpx(buf: Buffer): TrackPoint[] {
    const xml = buf.toString("utf-8");
    const points: TrackPoint[] = [];

    const visit = (attrs: string, inner: string) => {
        const a: Record<string, string> = {};
        for (const m of attrs.matchAll(ATTR_RE)) a[m[1]] = m[2];
        const lat = Number(a.lat);
        const lon = Number(a.lon);
        if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
            throw new GpxParseError("trkpt missing valid lat/lon");
        }
        const timeMatch = inner.match(TIME_RE);
        if (!timeMatch) {
            throw new GpxParseError(
                "trkpt missing <time>. v1 requires timed GPX. Use gpx_add_time.py first.",
            );
        }
        const time = new Date(timeMatch[1]);
        if (Number.isNaN(time.getTime())) {
            throw new GpxParseError(`invalid ISO-8601 time: ${timeMatch[1]}`);
        }
        const eleMatch = inner.match(ELE_RE);
        const ele = eleMatch ? Number(eleMatch[1]) : undefined;
        points.push({ lat, lon, ele, time });
    };

    for (const m of xml.matchAll(TRKPT_RE)) {
        visit(m[1], m[2]);
    }
    for (const m of xml.matchAll(SELF_CLOSING_TRKPT_RE)) {
        // Self-closing trkpts can't carry <time>, so they'd fail visit().
        // Surface a clearer error here.
        const inner = "";
        visit(m[1], inner);
    }

    if (points.length === 0) throw new GpxParseError("GPX has no trackpoints");
    if (points.length < 2) throw new GpxParseError("GPX needs at least two trackpoints");
    return points;
}

const EARTH_R = 6_371_000;
function haversine(a: TrackPoint, b: TrackPoint): number {
    const toRad = (x: number) => (x * Math.PI) / 180;
    const dLat = toRad(b.lat - a.lat);
    const dLon = toRad(b.lon - a.lon);
    const s =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * EARTH_R * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
}

export function summarize(points: TrackPoint[]) {
    let distance = 0;
    for (let i = 1; i < points.length; i++) distance += haversine(points[i - 1], points[i]);
    const duration = Math.max(
        0,
        Math.round((points[points.length - 1].time.getTime() - points[0].time.getTime()) / 1000),
    );
    return { distanceM: distance, durationS: duration, pointCount: points.length };
}
