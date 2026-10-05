// Client replica of a board: the server's confirmed state plus this tab's unacknowledged ops.
//
//   view = serverShapes with pending ops replayed on top (optimistic rendering)
//
// The server is authoritative and applies ops in arrival order, so the client only needs to
// replay what it has not yet heard back about. No transformation is needed because ops address
// shapes by id, not by position.

/** Applies one op to a Map<id, shape>. Returns false if the op does not apply (shape missing / id taken). */
export function applyOp(shapes, op, author, version) {
  switch (op.kind) {
    case 'add': {
      if (shapes.has(op.shape.id)) return false;
      shapes.set(op.shape.id, { ...op.shape, author: author ?? op.shape.author, version: version ?? op.shape.version });
      return true;
    }
    case 'update': {
      const old = shapes.get(op.id);
      if (!old) return false;
      shapes.set(op.id, { ...old, ...op.set, version: version ?? old.version });
      return true;
    }
    case 'delete':
      return shapes.delete(op.id);
    default:
      return false;
  }
}

export class BoardState {
  constructor() {
    this.serverShapes = new Map();
    this.seq = 0;
    /** @type {{opId: string, op: object}[]} in send order */
    this.pending = [];
    this._view = null;
    this._sorted = null;
    this._compare = (a, b) => (a.z !== b.z ? (a.z < b.z ? -1 : 1) : a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  }

  /** Full snapshot from the server (first connect and every reconnect). Returns pending ops to resend. */
  onWelcome(msg) {
    this.serverShapes = new Map();
    for (const s of msg.shapes) this.serverShapes.set(s.id, s);
    this.seq = msg.seq;
    this._invalidate();
    return this.pending.slice();
  }

  /** A local edit: shows immediately, stays pending until the server's committed echo. */
  local(opId, op) {
    this.pending.push({ opId, op });
    this._invalidate();
  }

  /** Committed op from any peer (our own echo doubles as the ACK). */
  onCommitted(msg) {
    const { op } = msg;
    applyOp(this.serverShapes, op, msg.author, op.kind === 'add' ? op.shape.version : op.version);
    this.seq = msg.seq;
    const i = this.pending.findIndex((p) => p.opId === msg.opId);
    if (i >= 0) this.pending.splice(i, 1);
    this._invalidate();
    return i >= 0;
  }

  /** Server refused one of our ops: drop it so the view falls back to the server's truth. */
  onRejected(msg) {
    const i = this.pending.findIndex((p) => p.opId === msg.opId);
    if (i < 0) return null;
    const [removed] = this.pending.splice(i, 1);
    this._invalidate();
    return removed;
  }

  /** Map<id, shape> of what the user sees. */
  view() {
    if (this._view) return this._view;
    if (this.pending.length === 0) {
      this._view = this.serverShapes;
    } else {
      const v = new Map(this.serverShapes);
      for (const p of this.pending) applyOp(v, p.op, undefined, undefined);
      this._view = v;
    }
    return this._view;
  }

  /** Shapes in painting order (bottom first). */
  sorted() {
    if (!this._sorted) this._sorted = Array.from(this.view().values()).sort(this._compare);
    return this._sorted;
  }

  get(id) {
    return this.view().get(id);
  }

  _invalidate() {
    this._view = null;
    this._sorted = null;
  }
}
