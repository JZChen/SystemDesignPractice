// Per-user undo/redo. Undo never rewinds the shared log; it sends NEW ops that reverse this
// user's own change, computed against what the board looks like right now.

const SHAPE_FIELDS = ['id', 'type', 'x', 'y', 'w', 'h', 'points', 'stroke', 'fill', 'width', 'z'];
const MAX_ENTRIES = 200;

/** Client-sendable copy of a shape (drops server-owned author/version). */
export function stripShape(s) {
  const out = {};
  for (const f of SHAPE_FIELDS) {
    if (s[f] !== undefined && s[f] !== null) out[f] = Array.isArray(s[f]) ? s[f].slice() : s[f];
  }
  return out;
}

/**
 * Inverse of {@code ops} given the view BEFORE they were applied. Ops are inverted in reverse
 * order and applied to a scratch copy so a batch that touches the same shape twice still inverts.
 */
export function invert(ops, view) {
  const scratch = new Map(view);
  const inverse = [];
  for (const op of ops) {
    switch (op.kind) {
      case 'add':
        inverse.push({ kind: 'delete', id: op.shape.id });
        scratch.set(op.shape.id, op.shape);
        break;
      case 'delete': {
        const old = scratch.get(op.id);
        if (old) inverse.push({ kind: 'add', shape: stripShape(old) });
        scratch.delete(op.id);
        break;
      }
      case 'update': {
        const old = scratch.get(op.id);
        if (!old) break;
        const prev = {};
        for (const k of Object.keys(op.set)) {
          prev[k] = Array.isArray(old[k]) ? old[k].slice() : old[k];
        }
        inverse.push({ kind: 'update', id: op.id, set: prev });
        scratch.set(op.id, { ...old, ...op.set });
        break;
      }
      default:
        break;
    }
  }
  return inverse.reverse();
}

/** Drops ops that can no longer apply (a peer deleted the shape, or the id is live again). */
export function applicable(ops, view) {
  const live = new Set(view.keys());
  const out = [];
  for (const op of ops) {
    if (op.kind === 'add') {
      if (live.has(op.shape.id)) continue;
      live.add(op.shape.id);
    } else {
      if (!live.has(op.id)) continue;
      if (op.kind === 'delete') live.delete(op.id);
    }
    out.push(op);
  }
  return out;
}

export class UndoManager {
  constructor() {
    this.undoStack = [];
    this.redoStack = [];
  }

  /**
   * Records a user action. {@code view} is the board BEFORE the ops. Entries with the same
   * {@code mergeKey} (e.g. one eraser gesture) collapse into one undo step.
   */
  record(ops, view, mergeKey) {
    if (ops.length === 0) return;
    const inverse = invert(ops, view);
    const top = this.undoStack[this.undoStack.length - 1];
    if (mergeKey !== undefined && top && top.mergeKey === mergeKey) {
      top.inverse = inverse.concat(top.inverse);
    } else {
      this.undoStack.push({ inverse, mergeKey });
      if (this.undoStack.length > MAX_ENTRIES) this.undoStack.shift();
    }
    this.redoStack.length = 0;
  }

  canUndo() {
    return this.undoStack.length > 0;
  }

  canRedo() {
    return this.redoStack.length > 0;
  }

  /** Ops to send for undo (possibly empty if a peer already changed everything), given the current view. */
  undo(view) {
    return this._step(this.undoStack, this.redoStack, view);
  }

  redo(view) {
    return this._step(this.redoStack, this.undoStack, view);
  }

  /** Forget everything (e.g. after a reconnect to a board whose state changed under us). */
  clear() {
    this.undoStack.length = 0;
    this.redoStack.length = 0;
  }

  _step(from, to, view) {
    while (from.length > 0) {
      const entry = from.pop();
      const ops = applicable(entry.inverse, view);
      if (ops.length === 0) continue; // nothing left to reverse; try the next entry
      to.push({ inverse: invert(ops, view), mergeKey: undefined });
      return ops;
    }
    return [];
  }
}
