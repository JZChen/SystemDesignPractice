// Pointer tools. Each gesture produces at most one committed op batch at pointer-up (pen, shapes,
// move) or small batches while dragging (eraser). Live pen strokes are streamed as ephemeral
// previews so peers see the line being drawn before it is committed.

import {
  BOARD_HEIGHT, BOARD_WIDTH, MAX_STROKE_POINTS, MIN_POINT_DISTANCE,
  clamp, rectFromDrag, round2, strokeGeometry, topmostAt, hitTest, zTop,
} from './geometry.js';

const PREVIEW_INTERVAL_MS = 50;
const ERASER_RADIUS = 8;
const ERASER_FLUSH_MS = 100;
const SELECT_TOLERANCE = 4;
const MIN_SHAPE_SIZE = 2;

export const TOOLS = ['select', 'pen', 'rect', 'ellipse', 'line', 'arrow', 'eraser'];

export class ToolController {
  /**
   * @param {{
   *   state: import('./board-state.js').BoardState,
   *   commit: function(object[], (string|undefined)): void,
   *   sendPreview: function(object): void,
   *   style: function(): {stroke: string, fill: string, width: number},
   *   newId: function(): string,
   *   invalidate: function(): void,
   *   onSelectionChange: function(?string): void,
   * }} host
   */
  constructor(host) {
    this.host = host;
    this.tool = 'pen';
    this.draft = null;            // shape being drawn locally (not yet committed)
    this.overrides = new Map();   // id -> shape shown instead of the real one (during a move)
    this.selectedId = null;
    this.gesture = null;
  }

  setTool(tool) {
    if (!TOOLS.includes(tool)) return;
    this.cancel();
    this.tool = tool;
    if (tool !== 'select') this.select(null);
  }

  select(id) {
    if (this.selectedId === id) return;
    this.selectedId = id;
    this.host.onSelectionChange(id);
    this.host.invalidate();
  }

  /** Called when the board changes underneath us (remote op, reject, reconnect). */
  onBoardChanged() {
    if (this.selectedId && !this.host.state.get(this.selectedId)) this.select(null);
  }

  deleteSelected() {
    const s = this.selectedId && this.host.state.get(this.selectedId);
    if (!s) return;
    this.select(null);
    this.host.commit([{ kind: 'delete', id: s.id }]);
  }

  cancel() {
    if (this.gesture && this.gesture.kind === 'pen') this._flushPreview(true);
    if (this.gesture && this.gesture.kind === 'erase') this._flushErase();
    this.gesture = null;
    this.draft = null;
    this.overrides.clear();
    this.host.invalidate();
  }

  pointerDown(x, y) {
    const { style } = this.host;
    switch (this.tool) {
      case 'pen': {
        const st = style();
        this.gesture = {
          kind: 'pen', sid: this.host.newId(), pts: [round2(x), round2(y)], sentUpTo: 0,
          stroke: st.stroke, width: st.width, lastFlush: 0,
        };
        this._updatePenDraft();
        this._flushPreview(false);
        break;
      }
      case 'rect':
      case 'ellipse':
      case 'line':
      case 'arrow':
        this.gesture = { kind: 'shape', x0: x, y0: y, x1: x, y1: y };
        this._updateShapeDraft();
        break;
      case 'select': {
        const hit = topmostAt(this.host.state.sorted(), x, y, SELECT_TOLERANCE);
        this.select(hit ? hit.id : null);
        if (hit) this.gesture = { kind: 'move', id: hit.id, x0: x, y0: y, dx: 0, dy: 0 };
        break;
      }
      case 'eraser':
        this.gesture = { kind: 'erase', key: this.host.newId(), erased: new Set(), batch: [], lastFlush: performance.now() };
        this._eraseAt(x, y);
        break;
      default:
        break;
    }
    this.host.invalidate();
  }

  pointerMove(x, y) {
    const g = this.gesture;
    if (!g) return;
    x = clamp(x, 0, BOARD_WIDTH);
    y = clamp(y, 0, BOARD_HEIGHT);
    switch (g.kind) {
      case 'pen': {
        const n = g.pts.length;
        if (n / 2 >= MAX_STROKE_POINTS) break;
        if (Math.hypot(x - g.pts[n - 2], y - g.pts[n - 1]) < MIN_POINT_DISTANCE) break;
        g.pts.push(round2(x), round2(y));
        this._updatePenDraft();
        if (performance.now() - g.lastFlush >= PREVIEW_INTERVAL_MS) this._flushPreview(false);
        break;
      }
      case 'shape':
        g.x1 = x;
        g.y1 = y;
        this._updateShapeDraft();
        break;
      case 'move': {
        g.dx = x - g.x0;
        g.dy = y - g.y0;
        const s = this.host.state.get(g.id);
        if (s) this.overrides.set(g.id, { ...s, x: round2(s.x + g.dx), y: round2(s.y + g.dy) });
        break;
      }
      case 'erase':
        this._eraseAt(x, y);
        break;
      default:
        break;
    }
    this.host.invalidate();
  }

  pointerUp() {
    const g = this.gesture;
    this.gesture = null;
    if (!g) return;
    const { state } = this.host;
    switch (g.kind) {
      case 'pen': {
        this._flushPreview(true, g);
        const geo = strokeGeometry(g.pts);
        const st = { stroke: g.stroke, width: g.width };
        this.host.commit([{
          kind: 'add',
          shape: { id: this.host.newId(), type: 'STROKE', ...geo, stroke: st.stroke, fill: 'none', width: st.width, z: zTop(state.view().values()) },
        }]);
        break;
      }
      case 'shape': {
        const shape = this.draft;
        if (shape && (Math.abs(shape.w) >= MIN_SHAPE_SIZE || Math.abs(shape.h) >= MIN_SHAPE_SIZE)) {
          this.host.commit([{ kind: 'add', shape: { ...shape, z: zTop(state.view().values()) } }]);
        }
        break;
      }
      case 'move': {
        const s = state.get(g.id);
        if (s && (g.dx !== 0 || g.dy !== 0)) {
          this.host.commit([{ kind: 'update', id: g.id, set: { x: round2(s.x + g.dx), y: round2(s.y + g.dy) } }]);
        }
        break;
      }
      case 'erase':
        this._flushErase(g);
        break;
      default:
        break;
    }
    this.draft = null;
    this.overrides.clear();
    this.host.invalidate();
  }

  _updatePenDraft() {
    const g = this.gesture;
    this.draft = { id: '_draft', type: 'STROKE', x: 0, y: 0, w: 0, h: 0, points: g.pts, stroke: g.stroke, fill: 'none', width: g.width, z: '' };
  }

  _updateShapeDraft() {
    const g = this.gesture;
    const st = this.host.style();
    const type = this.tool.toUpperCase();
    let geo;
    if (type === 'LINE' || type === 'ARROW') {
      geo = { x: round2(g.x0), y: round2(g.y0), w: round2(g.x1 - g.x0), h: round2(g.y1 - g.y0) };
    } else {
      const r = rectFromDrag(g.x0, g.y0, g.x1, g.y1);
      geo = { x: round2(r.x), y: round2(r.y), w: round2(r.w), h: round2(r.h) };
    }
    const fill = type === 'RECT' || type === 'ELLIPSE' ? st.fill : 'none';
    this.draft = { id: this.draft?.id ?? this.host.newId(), type, ...geo, stroke: st.stroke, fill, width: st.width, z: '' };
  }

  /** Streams the not-yet-sent pen points to peers (ephemeral; the server never stores them). */
  _flushPreview(done, g = this.gesture) {
    if (!g || g.kind !== 'pen') return;
    // overlap by one point so the peer's polyline segments connect
    const from = Math.max(0, g.sentUpTo - 2);
    let pts = g.pts.slice(from);
    if (pts.length > 1000) pts = pts.slice(pts.length - 1000);
    if (pts.length === 0) pts = g.pts.slice(-2);
    this.host.sendPreview({ t: 'preview', sid: g.sid, pts, stroke: g.stroke, width: g.width, done });
    g.sentUpTo = g.pts.length;
    g.lastFlush = performance.now();
  }

  _eraseAt(x, y) {
    const g = this.gesture;
    for (const s of this.host.state.sorted()) {
      if (!g.erased.has(s.id) && hitTest(s, x, y, ERASER_RADIUS)) {
        g.erased.add(s.id);
        g.batch.push({ kind: 'delete', id: s.id });
      }
    }
    if (g.batch.length > 0 && performance.now() - g.lastFlush >= ERASER_FLUSH_MS) this._flushErase(g);
  }

  _flushErase(g = this.gesture) {
    if (!g || g.batch.length === 0) return;
    const ops = g.batch;
    g.batch = [];
    g.lastFlush = performance.now();
    if (ops.some((op) => op.id === this.selectedId)) this.select(null);
    this.host.commit(ops, g.key); // one undo step per eraser gesture
  }
}
