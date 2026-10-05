// Pure geometry helpers (no DOM). Shapes use board coordinates; STROKE points are relative to (x, y).

export const BOARD_WIDTH = 4000;
export const BOARD_HEIGHT = 3000;
export const MAX_STROKE_POINTS = 5000;
export const MIN_POINT_DISTANCE = 1.5;

const Z_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
const Z_MAX_LENGTH = 32;

/** Painting order: z key, then id. Plain string comparison, so it matches the server's ordering. */
export function compareShapes(a, b) {
  if (a.z !== b.z) return a.z < b.z ? -1 : 1;
  if (a.id !== b.id) return a.id < b.id ? -1 : 1;
  return 0;
}

/**
 * Smallest-change key that sorts strictly after {@code key}: bump the last character that can
 * be bumped and drop what follows it. Base62 in ASCII order, so plain string compare works.
 */
export function zAfter(key) {
  if (!key) return 'a0';
  for (let i = key.length - 1; i >= 0; i--) {
    const pos = Z_ALPHABET.indexOf(key[i]);
    if (pos >= 0 && pos < Z_ALPHABET.length - 1) {
      return key.slice(0, i) + Z_ALPHABET[pos + 1];
    }
  }
  return key.length < Z_MAX_LENGTH ? key + '1' : key; // all 'z': extend (practically unreachable)
}

/** Key above every shape in {@code shapes}. */
export function zTop(shapes) {
  let max = '';
  for (const s of shapes) if (s.z > max) max = s.z;
  return zAfter(max);
}

/** Random id matching the server's [A-Za-z0-9-]{1,48}; {@code randomBytes(n)} returns a Uint8Array. */
export function newId(randomBytes) {
  const bytes = randomBytes(12);
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out;
}

/** Axis-aligned bounds {x, y, w, h} with non-negative w/h. */
export function bounds(s) {
  const x = Math.min(s.x, s.x + s.w);
  const y = Math.min(s.y, s.y + s.h);
  return { x, y, w: Math.abs(s.w), h: Math.abs(s.h) };
}

/** Rectangle from a drag (any direction) to normalized x/y/w/h. */
export function rectFromDrag(x0, y0, x1, y1) {
  return { x: Math.min(x0, x1), y: Math.min(y0, y1), w: Math.abs(x1 - x0), h: Math.abs(y1 - y0) };
}

export function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const cx = ax + t * dx;
  const cy = ay + t * dy;
  return Math.hypot(px - cx, py - cy);
}

/** True if (px, py) is within {@code tol} of the shape's visible geometry. */
export function hitTest(s, px, py, tol) {
  const reach = s.width / 2 + tol;
  switch (s.type) {
    case 'STROKE': {
      const p = s.points;
      if (p.length === 2) return Math.hypot(px - (s.x + p[0]), py - (s.y + p[1])) <= reach;
      for (let i = 0; i + 3 < p.length; i += 2) {
        if (distToSegment(px, py, s.x + p[i], s.y + p[i + 1], s.x + p[i + 2], s.y + p[i + 3]) <= reach) {
          return true;
        }
      }
      return false;
    }
    case 'LINE':
    case 'ARROW':
      return distToSegment(px, py, s.x, s.y, s.x + s.w, s.y + s.h) <= reach;
    case 'RECT': {
      const b = bounds(s);
      const inside = px >= b.x && px <= b.x + b.w && py >= b.y && py <= b.y + b.h;
      if (s.fill !== 'none' && inside) return true;
      const outer = px >= b.x - reach && px <= b.x + b.w + reach && py >= b.y - reach && py <= b.y + b.h + reach;
      const inner = px > b.x + reach && px < b.x + b.w - reach && py > b.y + reach && py < b.y + b.h - reach;
      return outer && !inner;
    }
    case 'ELLIPSE': {
      const b = bounds(s);
      const rx = b.w / 2;
      const ry = b.h / 2;
      if (rx < 1e-6 || ry < 1e-6) {
        return distToSegment(px, py, b.x, b.y, b.x + b.w, b.y + b.h) <= reach;
      }
      const nx = (px - (b.x + rx)) / rx;
      const ny = (py - (b.y + ry)) / ry;
      const d = Math.sqrt(nx * nx + ny * ny);
      if (s.fill !== 'none' && d <= 1) return true;
      return Math.abs(d - 1) * Math.min(rx, ry) <= reach;
    }
    default:
      return false;
  }
}

/** Topmost shape under the point, or null. {@code sorted} is in painting order (bottom first). */
export function topmostAt(sorted, px, py, tol) {
  for (let i = sorted.length - 1; i >= 0; i--) {
    if (hitTest(sorted[i], px, py, tol)) return sorted[i];
  }
  return null;
}

/**
 * Freehand points (absolute) to a STROKE geometry: bounding box origin plus relative points.
 * {@code abs} is a flat [x0, y0, x1, y1, ...] array.
 */
export function strokeGeometry(abs) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (let i = 0; i < abs.length; i += 2) {
    minX = Math.min(minX, abs[i]);
    maxX = Math.max(maxX, abs[i]);
    minY = Math.min(minY, abs[i + 1]);
    maxY = Math.max(maxY, abs[i + 1]);
  }
  const points = new Array(abs.length);
  for (let i = 0; i < abs.length; i += 2) {
    points[i] = round2(abs[i] - minX);
    points[i + 1] = round2(abs[i + 1] - minY);
  }
  return { x: round2(minX), y: round2(minY), w: round2(maxX - minX), h: round2(maxY - minY), points };
}

/** Keeps payloads small: two decimals is far below a device pixel. */
export function round2(v) {
  return Math.round(v * 100) / 100;
}

export function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}
