// Canvas renderer. The canvas only covers the visible viewport (a 4000x3000 backing store at 2x
// DPR would be ~190 MB); the scroll offset is applied as a translation. Redraws on rAF when dirty.

import { bounds } from './geometry.js';

export class Renderer {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {function(): object} getScene returns
   *        {shapes, overrides: Map<id, shape|null>, draft, previews: Map, cursors: Map, selectedId, scrollX, scrollY}
   */
  constructor(canvas, getScene) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.getScene = getScene;
    this.dirty = true;
    this.cssWidth = 0;
    this.cssHeight = 0;
    this._loop = this._loop.bind(this);
    requestAnimationFrame(this._loop);
  }

  invalidate() {
    this.dirty = true;
  }

  resize(cssWidth, cssHeight) {
    const dpr = window.devicePixelRatio || 1;
    this.cssWidth = cssWidth;
    this.cssHeight = cssHeight;
    this.canvas.width = Math.max(1, Math.round(cssWidth * dpr));
    this.canvas.height = Math.max(1, Math.round(cssHeight * dpr));
    this.dirty = true;
  }

  _loop() {
    if (this.dirty) {
      this.dirty = false;
      this._draw();
    }
    requestAnimationFrame(this._loop);
  }

  _draw() {
    const { ctx } = this;
    const scene = this.getScene();
    const dpr = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    ctx.setTransform(dpr, 0, 0, dpr, -scene.scrollX * dpr, -scene.scrollY * dpr);

    const viewport = { x: scene.scrollX, y: scene.scrollY, w: this.cssWidth, h: this.cssHeight };
    for (const s of scene.shapes) {
      const shown = scene.overrides.has(s.id) ? scene.overrides.get(s.id) : s;
      if (shown && intersects(shown, viewport)) drawShape(ctx, shown);
    }
    if (scene.draft) drawShape(ctx, scene.draft);

    for (const p of scene.previews.values()) {
      drawPolyline(ctx, p.pts, 0, 0, p.stroke, p.width);
    }

    const selected = scene.selectedId && (scene.overrides.get(scene.selectedId) ?? scene.shapesById.get(scene.selectedId));
    if (selected) drawSelection(ctx, selected);

    for (const c of scene.cursors.values()) drawCursor(ctx, c);
  }
}

function intersects(s, v) {
  const b = bounds(s);
  const pad = s.width + 12; // stroke width and arrow heads
  return b.x - pad <= v.x + v.w && b.x + b.w + pad >= v.x && b.y - pad <= v.y + v.h && b.y + b.h + pad >= v.y;
}

export function drawShape(ctx, s) {
  ctx.lineWidth = s.width;
  ctx.strokeStyle = s.stroke;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  switch (s.type) {
    case 'STROKE':
      drawPolyline(ctx, s.points, s.x, s.y, s.stroke, s.width);
      break;
    case 'RECT': {
      const b = bounds(s);
      if (s.fill !== 'none') {
        ctx.fillStyle = s.fill;
        ctx.fillRect(b.x, b.y, b.w, b.h);
      }
      ctx.strokeRect(b.x, b.y, b.w, b.h);
      break;
    }
    case 'ELLIPSE': {
      const b = bounds(s);
      ctx.beginPath();
      ctx.ellipse(b.x + b.w / 2, b.y + b.h / 2, b.w / 2, b.h / 2, 0, 0, Math.PI * 2);
      if (s.fill !== 'none') {
        ctx.fillStyle = s.fill;
        ctx.fill();
      }
      ctx.stroke();
      break;
    }
    case 'LINE':
    case 'ARROW': {
      const x2 = s.x + s.w;
      const y2 = s.y + s.h;
      ctx.beginPath();
      ctx.moveTo(s.x, s.y);
      ctx.lineTo(x2, y2);
      ctx.stroke();
      if (s.type === 'ARROW') drawArrowHead(ctx, s.x, s.y, x2, y2, s.width);
      break;
    }
    default:
      break;
  }
}

function drawPolyline(ctx, pts, ox, oy, color, width) {
  if (!pts || pts.length < 2) return;
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  if (pts.length === 2) {
    ctx.beginPath();
    ctx.arc(ox + pts[0], oy + pts[1], width / 2, 0, Math.PI * 2);
    ctx.fill();
    return;
  }
  ctx.beginPath();
  ctx.moveTo(ox + pts[0], oy + pts[1]);
  for (let i = 2; i + 1 < pts.length; i += 2) ctx.lineTo(ox + pts[i], oy + pts[i + 1]);
  ctx.stroke();
}

function drawArrowHead(ctx, x1, y1, x2, y2, width) {
  const angle = Math.atan2(y2 - y1, x2 - x1);
  const len = 10 + width * 2;
  ctx.beginPath();
  ctx.moveTo(x2, y2);
  ctx.lineTo(x2 - len * Math.cos(angle - Math.PI / 7), y2 - len * Math.sin(angle - Math.PI / 7));
  ctx.moveTo(x2, y2);
  ctx.lineTo(x2 - len * Math.cos(angle + Math.PI / 7), y2 - len * Math.sin(angle + Math.PI / 7));
  ctx.stroke();
}

function drawSelection(ctx, s) {
  const b = bounds(s);
  const pad = s.width / 2 + 4;
  ctx.save();
  ctx.setLineDash([6, 4]);
  ctx.lineWidth = 1;
  ctx.strokeStyle = '#2563eb';
  ctx.strokeRect(b.x - pad, b.y - pad, b.w + pad * 2, b.h + pad * 2);
  ctx.restore();
}

function drawCursor(ctx, c) {
  ctx.save();
  ctx.fillStyle = c.color;
  ctx.beginPath();
  ctx.moveTo(c.x, c.y);
  ctx.lineTo(c.x, c.y + 16);
  ctx.lineTo(c.x + 4.5, c.y + 12);
  ctx.lineTo(c.x + 11, c.y + 12);
  ctx.closePath();
  ctx.fill();
  ctx.font = '12px system-ui, sans-serif';
  const label = c.name;
  const w = ctx.measureText(label).width + 8;
  ctx.fillRect(c.x + 10, c.y + 14, w, 17);
  ctx.fillStyle = '#ffffff';
  ctx.fillText(label, c.x + 14, c.y + 27); // canvas text: never parsed as HTML
  ctx.restore();
}
