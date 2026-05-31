/**
 * Tiny GPX parser — extracts trackpoints to compute summary metadata
 * (distance, duration, point count).
 *
 * <time> is optional. Files without per-point timestamps still parse;
 * `hasTime` is reported per-file and `durationS` is 0 in that case.
 * Timed playback requires time tags — the Android client gates the
 * simulator on hasTime and offers an "Add Time" tool to fix the file.
 */

export interface TrackPoint {
    lat: number;
    lon: number;
    ele?: number;
    time?: Date;
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
        let time: Date | undefined;
        const timeMatch = inner.match(TIME_RE);
        if (timeMatch) {
            const t = new Date(timeMatch[1]);
            if (Number.isNaN(t.getTime())) {
                throw new GpxParseError(`invalid ISO-8601 time: ${timeMatch[1]}`);
            }
            time = t;
        }
        const eleMatch = inner.match(ELE_RE);
        const ele = eleMatch ? Number(eleMatch[1]) : undefined;
        points.push({ lat, lon, ele, time });
    };

    for (const m of xml.matchAll(TRKPT_RE)) {
        visit(m[1], m[2]);
    }
    for (const m of xml.matchAll(SELF_CLOSING_TRKPT_RE)) {
        visit(m[1], "");
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
    const hasTime = points.every((p) => p.time !== undefined);
    let duration = 0;
    if (hasTime) {
        const first = points[0].time as Date;
        const last = points[points.length - 1].time as Date;
        duration = Math.max(0, Math.round((last.getTime() - first.getTime()) / 1000));
    }
    return { distanceM: distance, durationS: duration, pointCount: points.length, hasTime };
}
