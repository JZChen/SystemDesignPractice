import { zAfter, zTop, compareShapes, hitTest, strokeGeometry, newId, topmostAt, rectFromDrag } from '../../main/resources/static/js/geometry.js';

suite('geometry');

const rect = (over) => ({ id: 'r', type: 'RECT', x: 0, y: 0, w: 100, h: 50, stroke: '#000000', fill: 'none', width: 2, z: 'a0', ...over });

test('zAfter sorts strictly after its input', () => {
  for (const k of ['', 'a0', 'a9', 'aZ', 'az', 'zz', '0', 'Zz']) {
    const next = zAfter(k);
    ok(next > k, `${next} > ${k}`);
  }
});

test('zAfter bumps the last bumpable character', () => {
  eq(zAfter(''), 'a0');
  eq(zAfter('a0'), 'a1');
  eq(zAfter('a9'), 'aA');
  eq(zAfter('az'), 'b');
  eq(zAfter('zz'), 'zz1');
});

test('zTop is above every shape', () => {
  const shapes = [{ z: 'a3' }, { z: 'b' }, { z: 'a9z' }];
  const top = zTop(shapes);
  ok(shapes.every((s) => top > s.z));
});

test('compareShapes orders by z then id', () => {
  const list = [{ z: 'b', id: '1' }, { z: 'a', id: '2' }, { z: 'b', id: '0' }];
  eq(list.sort(compareShapes).map((s) => s.id), ['2', '0', '1']);
});

test('outlined rect is hit on the edge, not the middle', () => {
  ok(hitTest(rect(), 0, 25, 4));
  ok(hitTest(rect(), 50, 51, 4));
  ok(!hitTest(rect(), 50, 25, 4));
});

test('filled rect is hit anywhere inside', () => {
  ok(hitTest(rect({ fill: '#ff0000' }), 50, 25, 4));
});

test('negative-size line is hit along its segment', () => {
  const line = rect({ type: 'LINE', x: 100, y: 100, w: -100, h: -100 });
  ok(hitTest(line, 50, 50, 2));
  ok(!hitTest(line, 50, 80, 2));
});

test('ellipse outline and fill', () => {
  const e = rect({ type: 'ELLIPSE', w: 100, h: 100 });
  ok(hitTest(e, 0, 50, 3));
  ok(!hitTest(e, 50, 50, 3));
  ok(hitTest({ ...e, fill: '#00ff00' }, 50, 50, 3));
});

test('stroke points are relative to x,y', () => {
  const s = rect({ type: 'STROKE', x: 10, y: 10, w: 20, h: 0, points: [0, 0, 20, 0] });
  ok(hitTest(s, 20, 10, 1));
  ok(!hitTest(s, 20, 20, 1));
});

test('single-point stroke is a dot', () => {
  const s = rect({ type: 'STROKE', x: 5, y: 5, w: 0, h: 0, points: [0, 0], width: 4 });
  ok(hitTest(s, 6, 6, 1));
  ok(!hitTest(s, 12, 12, 1));
});

test('strokeGeometry normalizes to the bounding box', () => {
  eq(strokeGeometry([10, 20, 30, 5, 15, 25]), { x: 10, y: 5, w: 20, h: 20, points: [0, 15, 20, 0, 5, 20] });
});

test('topmostAt prefers the last painted shape', () => {
  const a = rect({ id: 'a', fill: '#111111' });
  const b = rect({ id: 'b', fill: '#222222', z: 'a1' });
  eq(topmostAt([a, b], 10, 10, 2).id, 'b');
  eq(topmostAt([a, b], 500, 500, 2), null);
});

test('rectFromDrag works in any direction', () => {
  eq(rectFromDrag(50, 40, 10, 0), { x: 10, y: 0, w: 40, h: 40 });
});

test('newId matches the server id pattern', () => {
  let n = 0;
  const id = newId((len) => { const a = new Uint8Array(len); for (let i = 0; i < len; i++) a[i] = (n++ * 37) & 255; return a; });
  ok(/^[A-Za-z0-9-]{1,48}$/.test(id), id);
  eq(id.length, 24);
});
