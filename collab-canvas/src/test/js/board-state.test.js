import { BoardState } from '../../main/resources/static/js/board-state.js';

suite('board-state');

const shape = (id, over) => ({ id, type: 'RECT', x: 0, y: 0, w: 10, h: 10, stroke: '#000000', fill: 'none', width: 2, z: 'a0', ...over });
const committedAdd = (seq, opId, author, s) => ({ t: 'committed', seq, opId, author, op: { kind: 'add', shape: { ...s, author, version: seq } } });

test('local add is visible before the ACK, then confirmed by the echo', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 0, shapes: [] });
  st.local('o1', { kind: 'add', shape: shape('s1') });
  ok(st.get('s1'), 'optimistic');
  eq(st.serverShapes.size, 0);
  ok(st.onCommitted(committedAdd(1, 'o1', 'me', shape('s1'))), 'acked');
  eq(st.pending.length, 0);
  eq(st.get('s1').version, 1);
  eq(st.seq, 1);
});

test('remote op while my update is pending: my values stay on top', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 1, shapes: [{ ...shape('s1'), author: 'x', version: 1 }] });
  st.local('o2', { kind: 'update', id: 's1', set: { x: 50 } });
  st.onCommitted({ t: 'committed', seq: 2, opId: 'peer', author: 'peer', op: { kind: 'update', id: 's1', set: { x: 99, stroke: '#ff0000' }, version: 2 } });
  eq(st.get('s1').x, 50, 'pending replayed over server');
  eq(st.get('s1').stroke, '#ff0000', 'untouched field from peer');
  st.onCommitted({ t: 'committed', seq: 3, opId: 'o2', author: 'me', op: { kind: 'update', id: 's1', set: { x: 50 }, version: 3 } });
  eq(st.get('s1').x, 50, 'server agrees after ack');
});

test('rejection drops the pending op and reverts the view', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 1, shapes: [shape('s1')] });
  st.local('o3', { kind: 'update', id: 's1', set: { x: 70 } });
  eq(st.get('s1').x, 70);
  ok(st.onRejected({ t: 'rejected', opId: 'o3', reason: 'shape_deleted' }));
  eq(st.get('s1').x, 0);
  eq(st.onRejected({ t: 'rejected', opId: 'nope', reason: 'x' }), null);
});

test('peer deletes a shape I am editing: pending update is a no-op in the view', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 1, shapes: [shape('s1')] });
  st.local('o4', { kind: 'update', id: 's1', set: { x: 5 } });
  st.onCommitted({ t: 'committed', seq: 2, opId: 'p', author: 'peer', op: { kind: 'delete', id: 's1' } });
  eq(st.get('s1'), undefined);
});

test('welcome after reconnect replaces server state and returns pending for resend', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 0, shapes: [] });
  st.local('o5', { kind: 'add', shape: shape('mine') });
  const resend = st.onWelcome({ seq: 7, shapes: [shape('theirs')] });
  eq(resend.map((p) => p.opId), ['o5']);
  eq(st.seq, 7);
  ok(st.get('mine') && st.get('theirs'));
});

test('sorted() paints by z then id', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 0, shapes: [shape('b', { z: 'a1' }), shape('a', { z: 'a1' }), shape('c', { z: 'a0' })] });
  eq(st.sorted().map((s) => s.id), ['c', 'a', 'b']);
});

test('view is cached until something changes', () => {
  const st = new BoardState();
  st.onWelcome({ seq: 0, shapes: [shape('a')] });
  const v1 = st.view();
  ok(st.view() === v1);
  st.local('o6', { kind: 'delete', id: 'a' });
  ok(st.view() !== v1);
});
