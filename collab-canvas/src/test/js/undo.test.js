import { UndoManager, invert, applicable, stripShape } from '../../main/resources/static/js/undo.js';
import { applyOp } from '../../main/resources/static/js/board-state.js';

suite('undo');

const shape = (id, over) => ({ id, type: 'RECT', x: 0, y: 0, w: 10, h: 10, stroke: '#000000', fill: 'none', width: 2, z: 'a0', ...over });
const apply = (view, ops) => { const v = new Map(view); for (const op of ops) applyOp(v, op); return v; };

test('undo of an add deletes it; redo re-adds it', () => {
  const um = new UndoManager();
  let view = new Map();
  const ops = [{ kind: 'add', shape: shape('s1') }];
  um.record(ops, view);
  view = apply(view, ops);
  const u = um.undo(view);
  eq(u, [{ kind: 'delete', id: 's1' }]);
  view = apply(view, u);
  const r = um.redo(view);
  eq(r[0].kind, 'add');
  eq(r[0].shape.id, 's1');
});

test('undo of a delete re-adds without server-owned fields', () => {
  const um = new UndoManager();
  let view = new Map([['s1', { ...shape('s1'), author: 'u1', version: 4 }]]);
  const ops = [{ kind: 'delete', id: 's1' }];
  um.record(ops, view);
  view = apply(view, ops);
  const u = um.undo(view);
  eq(u, [{ kind: 'add', shape: shape('s1') }]);
});

test('undo of an update restores only the fields I changed', () => {
  const um = new UndoManager();
  let view = new Map([['s1', shape('s1', { x: 1, stroke: '#111111' })]]);
  const ops = [{ kind: 'update', id: 's1', set: { x: 9 } }];
  um.record(ops, view);
  view = apply(view, ops);
  view = apply(view, [{ kind: 'update', id: 's1', set: { stroke: '#ff0000' } }]); // peer recolors
  const u = um.undo(view);
  eq(u, [{ kind: 'update', id: 's1', set: { x: 1 } }]);
  eq(apply(view, u).get('s1').stroke, '#ff0000', 'peer change survives my undo');
});

test('undo skips entries a peer made moot', () => {
  const um = new UndoManager();
  let view = new Map();
  const a = [{ kind: 'add', shape: shape('a') }];
  um.record(a, view);
  view = apply(view, a);
  const b = [{ kind: 'update', id: 'a', set: { x: 5 } }];
  um.record(b, view);
  view = apply(view, b);
  view = apply(view, [{ kind: 'delete', id: 'a' }]); // peer deletes it
  eq(um.undo(view), [], 'both entries refer to a deleted shape');
  ok(!um.canUndo());
});

test('mergeKey folds one eraser gesture into one undo step', () => {
  const um = new UndoManager();
  let view = new Map([['a', shape('a')], ['b', shape('b')]]);
  const d1 = [{ kind: 'delete', id: 'a' }];
  um.record(d1, view, 'g1');
  view = apply(view, d1);
  const d2 = [{ kind: 'delete', id: 'b' }];
  um.record(d2, view, 'g1');
  view = apply(view, d2);
  eq(um.undoStack.length, 1);
  eq(um.undo(view).map((op) => op.shape.id).sort(), ['a', 'b']);
});

test('new action clears redo', () => {
  const um = new UndoManager();
  let view = new Map();
  um.record([{ kind: 'add', shape: shape('a') }], view);
  view = apply(view, [{ kind: 'add', shape: shape('a') }]);
  view = apply(view, um.undo(view));
  ok(um.canRedo());
  um.record([{ kind: 'add', shape: shape('b') }], view);
  ok(!um.canRedo());
});

test('invert handles a batch touching one shape twice', () => {
  const view = new Map([['s', shape('s', { x: 0 })]]);
  const inv = invert([{ kind: 'update', id: 's', set: { x: 1 } }, { kind: 'update', id: 's', set: { x: 2 } }], view);
  eq(inv, [{ kind: 'update', id: 's', set: { x: 1 } }, { kind: 'update', id: 's', set: { x: 0 } }]);
});

test('applicable drops ops for missing or already-live shapes', () => {
  const view = new Map([['live', shape('live')]]);
  const out = applicable([
    { kind: 'add', shape: shape('live') },
    { kind: 'update', id: 'gone', set: { x: 1 } },
    { kind: 'delete', id: 'live' },
    { kind: 'add', shape: shape('live') },
  ], view);
  eq(out.map((o) => o.kind), ['delete', 'add']);
});

test('stripShape copies arrays', () => {
  const s = shape('p', { type: 'STROKE', points: [0, 0, 1, 1], author: 'x', version: 2 });
  const c = stripShape(s);
  ok(c.points !== s.points);
  eq(c.author, undefined);
});
