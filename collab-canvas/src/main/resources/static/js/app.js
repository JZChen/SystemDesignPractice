// Bootstrap and glue: routing, socket message handling, UI controls.
// Security: all user-visible strings go through textContent / canvas fillText; nothing is parsed as HTML.

import { BoardState } from './board-state.js';
import { Connection } from './connection.js';
import { newId } from './geometry.js';
import { Renderer } from './renderer.js';
import { TOOLS, ToolController } from './tools.js';
import { UndoManager } from './undo.js';

const BOARD_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_OPS_PER_MESSAGE = 100;
const CURSOR_INTERVAL_MS = 50;
const CURSOR_TTL_MS = 10000;
const PREVIEW_TTL_MS = 3000;
const HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;

const $ = (id) => document.getElementById(id);
const randomBytes = (n) => crypto.getRandomValues(new Uint8Array(n));
const makeId = () => newId(randomBytes);

function setStatus(text, kind) {
  const el = $('status');
  el.textContent = text;
  el.dataset.kind = kind || '';
}

async function landing() {
  setStatus('Creating a new board…', 'busy');
  try {
    const res = await fetch('/api/boards', { method: 'POST', headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error(String(res.status));
    const body = await res.json();
    if (typeof body.boardId !== 'string' || !BOARD_ID_RE.test(body.boardId)) throw new Error('bad id');
    window.location.replace(`/b/${body.boardId}`);
  } catch {
    setStatus('Could not create a board. Try again later.', 'error');
  }
}

function boardPage(boardId) {
  const state = new BoardState();
  const undo = new UndoManager();
  const peers = new Map();     // userId -> {userId, name, color}
  const cursors = new Map();   // userId -> {x, y, name, color, at}
  const previews = new Map();  // userId -> {sid, pts[], stroke, width, doneAt}
  let me = null;
  let lastCursorSent = 0;

  const stage = $('stage');
  const scroller = $('scroller');
  const canvas = $('canvas');

  const renderer = new Renderer(canvas, () => ({
    shapes: state.sorted(),
    shapesById: state.view(),
    overrides: tools.overrides,
    draft: tools.draft,
    previews,
    cursors,
    selectedId: tools.selectedId,
    scrollX: scroller.scrollLeft,
    scrollY: scroller.scrollTop,
  }));

  const style = () => ({
    stroke: safeColor($('stroke-color').value, '#1f2937'),
    fill: $('fill-on').checked ? safeColor($('fill-color').value, '#fde68a') : 'none',
    width: Number($('stroke-width').value) || 3,
  });

  const conn = new Connection(wsUrl(boardId), {
    onMessage: handleMessage,
    onStatus: (s) => {
      const labels = {
        connecting: ['Connecting…', 'busy'],
        reconnecting: ['Reconnecting… (your edits are kept)', 'busy'],
        connected: ['Connected', 'ok'],
        refused: ['Disconnected by the server.', 'error'],
      };
      const [text, kind] = labels[s] || [s, ''];
      setStatus(text, kind);
      updatePendingBadge();
    },
    onGiveUp: async () => {
      try {
        const res = await fetch(`/api/boards/${boardId}`, { headers: { Accept: 'application/json' } });
        if (res.status === 404) {
          showMissing();
          return true;
        }
      } catch {
        // server unreachable: keep retrying
      }
      return false;
    },
  });

  const tools = new ToolController({
    state,
    commit,
    sendPreview: (msg) => conn.send(msg),
    style,
    newId: makeId,
    invalidate: () => renderer.invalidate(),
    onSelectionChange: () => {},
  });

  /** Applies ops optimistically, records undo, and sends them (or keeps them pending while offline). */
  function commit(ops, mergeKey, recordUndo = true) {
    if (ops.length === 0) return;
    if (recordUndo) undo.record(ops, state.view(), mergeKey);
    const wire = [];
    for (const op of ops) {
      const opId = makeId();
      state.local(opId, op);
      wire.push({ opId, ...op });
    }
    sendOps(wire);
    afterChange();
  }

  function sendOps(wireOps) {
    for (let i = 0; i < wireOps.length; i += MAX_OPS_PER_MESSAGE) {
      conn.send({ t: 'ops', ops: wireOps.slice(i, i + MAX_OPS_PER_MESSAGE) });
    }
  }

  function afterChange() {
    tools.onBoardChanged();
    updatePendingBadge();
    updateUndoButtons();
    renderer.invalidate();
  }

  function handleMessage(msg) {
    switch (msg.t) {
      case 'welcome': {
        me = msg.you;
        const resend = state.onWelcome(msg);
        sendOps(resend.map((p) => ({ opId: p.opId, ...p.op })));
        setPeers(msg.peers);
        afterChange();
        break;
      }
      case 'committed': {
        state.onCommitted(msg);
        if (msg.op.kind === 'add' && msg.op.shape.type === 'STROKE') previews.delete(msg.author);
        afterChange();
        break;
      }
      case 'rejected': {
        if (state.onRejected(msg)) {
          setStatus(`A change was not applied (${reasonText(msg.reason)}).`, 'warn');
          afterChange();
        }
        break;
      }
      case 'error':
        setStatus(`Server: ${reasonText(msg.reason)}`, 'warn');
        break;
      case 'presence':
        setPeers(msg.peers);
        break;
      case 'preview':
        onPreview(msg);
        break;
      case 'cursor': {
        const p = peers.get(msg.author);
        if (p) cursors.set(msg.author, { x: msg.x, y: msg.y, name: p.name, color: p.color, at: performance.now() });
        renderer.invalidate();
        break;
      }
      default:
        break;
    }
  }

  function onPreview(msg) {
    if (!peers.has(msg.author)) return;
    const cur = previews.get(msg.author);
    if (cur && cur.sid === msg.sid) {
      for (const v of msg.pts) cur.pts.push(v);
    } else {
      previews.set(msg.author, { sid: msg.sid, pts: msg.pts.slice(), stroke: msg.stroke, width: msg.width, doneAt: 0 });
    }
    if (msg.done) previews.get(msg.author).doneAt = performance.now();
    renderer.invalidate();
  }

  function setPeers(list) {
    peers.clear();
    for (const p of list) peers.set(p.userId, p);
    for (const id of [...cursors.keys()]) if (!peers.has(id)) cursors.delete(id);
    for (const id of [...previews.keys()]) if (!peers.has(id)) previews.delete(id);
    renderAvatars();
    renderer.invalidate();
  }

  function renderAvatars() {
    const box = $('avatars');
    box.replaceChildren();
    for (const p of peers.values()) {
      const el = document.createElement('span');
      el.className = 'avatar' + (me && p.userId === me.userId ? ' me' : '');
      const word = String(p.name).split(' ').pop() || '?';
      el.textContent = word.charAt(0).toUpperCase();
      el.title = me && p.userId === me.userId ? `${p.name} (you)` : p.name;
      el.style.backgroundColor = safeColor(p.color, '#64748b');
      box.appendChild(el);
    }
  }

  function updatePendingBadge() {
    const n = state.pending.length;
    $('pending').textContent = n > 0 ? `${n} unsynced` : '';
  }

  function updateUndoButtons() {
    $('undo').disabled = !undo.canUndo();
    $('redo').disabled = !undo.canRedo();
  }

  function doUndo(redo) {
    tools.cancel();
    const ops = redo ? undo.redo(state.view()) : undo.undo(state.view());
    commit(ops, undefined, false);
    updateUndoButtons();
  }

  function showMissing() {
    setStatus('This board does not exist (boards live in server memory and vanish on restart).', 'error');
    $('missing').hidden = false;
  }

  // ---- pointer input -------------------------------------------------------------------------

  const toBoard = (ev) => {
    const r = scroller.getBoundingClientRect();
    return { x: ev.clientX - r.left + scroller.scrollLeft, y: ev.clientY - r.top + scroller.scrollTop };
  };

  scroller.addEventListener('pointerdown', (ev) => {
    if (ev.button !== 0) return;
    scroller.setPointerCapture(ev.pointerId);
    const p = toBoard(ev);
    tools.pointerDown(p.x, p.y);
    ev.preventDefault();
  });
  scroller.addEventListener('pointermove', (ev) => {
    const p = toBoard(ev);
    tools.pointerMove(p.x, p.y);
    const now = performance.now();
    if (now - lastCursorSent >= CURSOR_INTERVAL_MS && conn.isOpen) {
      lastCursorSent = now;
      conn.send({ t: 'cursor', x: Math.round(p.x), y: Math.round(p.y) });
    }
  });
  const end = () => tools.pointerUp();
  scroller.addEventListener('pointerup', end);
  scroller.addEventListener('pointercancel', () => tools.cancel());
  scroller.addEventListener('scroll', () => renderer.invalidate(), { passive: true });

  new ResizeObserver(() => renderer.resize(stage.clientWidth, stage.clientHeight)).observe(stage);

  // ---- toolbar -------------------------------------------------------------------------------

  const toolButtons = document.querySelectorAll('[data-tool]');
  function setTool(tool) {
    tools.setTool(tool);
    for (const b of toolButtons) b.classList.toggle('active', b.dataset.tool === tool);
    scroller.dataset.tool = tool;
  }
  for (const b of toolButtons) b.addEventListener('click', () => setTool(b.dataset.tool));
  setTool('pen');

  $('undo').addEventListener('click', () => doUndo(false));
  $('redo').addEventListener('click', () => doUndo(true));
  $('copy-link').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setStatus('Link copied. Anyone with the link can draw on this board.', 'ok');
    } catch {
      setStatus('Copy failed; copy the address bar URL instead.', 'warn');
    }
  });

  // Style changes also restyle the selected shape.
  const restyle = (field) => {
    const s = tools.selectedId && state.get(tools.selectedId);
    if (!s) return;
    const st = style();
    if (field === 'fill' && s.type !== 'RECT' && s.type !== 'ELLIPSE') return;
    if (s[field] === st[field]) return;
    commit([{ kind: 'update', id: s.id, set: { [field]: st[field] } }]);
  };
  $('stroke-color').addEventListener('change', () => restyle('stroke'));
  $('stroke-width').addEventListener('change', () => restyle('width'));
  $('fill-color').addEventListener('change', () => restyle('fill'));
  $('fill-on').addEventListener('change', () => restyle('fill'));

  const KEY_TOOLS = { v: 'select', p: 'pen', r: 'rect', o: 'ellipse', l: 'line', a: 'arrow', e: 'eraser' };
  window.addEventListener('keydown', (ev) => {
    if (ev.target instanceof HTMLInputElement) return;
    const mod = ev.metaKey || ev.ctrlKey;
    const key = ev.key.toLowerCase();
    if (mod && key === 'z') {
      ev.preventDefault();
      doUndo(ev.shiftKey);
    } else if (mod && key === 'y') {
      ev.preventDefault();
      doUndo(true);
    } else if (!mod && (ev.key === 'Delete' || ev.key === 'Backspace')) {
      ev.preventDefault();
      tools.deleteSelected();
    } else if (ev.key === 'Escape') {
      tools.cancel();
      tools.select(null);
    } else if (!mod && !ev.altKey && KEY_TOOLS[key] && TOOLS.includes(KEY_TOOLS[key])) {
      setTool(KEY_TOOLS[key]);
    }
  });

  // Expire stale peer cursors and finished previews whose committed op never arrived.
  setInterval(() => {
    const now = performance.now();
    let changed = false;
    for (const [id, c] of cursors) if (now - c.at > CURSOR_TTL_MS) { cursors.delete(id); changed = true; }
    for (const [id, p] of previews) if (p.doneAt && now - p.doneAt > PREVIEW_TTL_MS) { previews.delete(id); changed = true; }
    if (changed) renderer.invalidate();
  }, 1000);

  window.addEventListener('beforeunload', (ev) => {
    if (state.pending.length > 0) ev.preventDefault(); // unsynced edits would be lost
  });

  updateUndoButtons();
  conn.start();
}

function wsUrl(boardId) {
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${window.location.host}/ws/boards/${boardId}`;
}

function safeColor(v, fallback) {
  return typeof v === 'string' && HEX_COLOR_RE.test(v) ? v.toLowerCase() : fallback;
}

function reasonText(reason) {
  const known = {
    shape_deleted: 'someone deleted that shape',
    duplicate_id: 'already exists',
    rate_limited: 'too many changes at once',
    board_full: 'board is full',
    too_many_points: 'board is full',
  };
  return known[reason] || 'invalid change';
}

const match = window.location.pathname.match(/^\/b\/([^/]+)$/);
if (match && BOARD_ID_RE.test(match[1])) {
  document.body.classList.add('board');
  boardPage(match[1]);
} else if (window.location.pathname === '/' || window.location.pathname === '/index.html') {
  landing();
} else {
  setStatus('Invalid board link.', 'error');
  $('missing').hidden = false;
}
