// JavaScriptCore test runner (osascript -l JavaScript). Loads the DOM-free ES modules by
// rewriting `export` / `import` into plain scopes, then runs the *.test.js files.
//
//   osascript -l JavaScript src/test/js/runner.js <project-root>

ObjC.import('Foundation');

function read(path) {
  const s = $.NSString.stringWithContentsOfFileEncodingError(path, 4, null);
  if (!s || s.isNil()) throw new Error('cannot read ' + path);
  return s.js;
}

/** Removes (possibly multi-line) `import ... from '...';` statements; deps are already in scope. */
function stripImports(src) {
  return src.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?/gm, '');
}

/** Wraps a module so its exports become locals of the combined script. */
function asScope(src, tag) {
  const names = [];
  const body = stripImports(src)
    .split('\n')
    .map((line) => line.replace(/^export\s+((?:async\s+)?(?:function|class|const|let)\s+([A-Za-z_$][\w$]*))/, (m, decl, name) => {
      names.push(name);
      return decl;
    }))
    .join('\n');
  const v = '__mod_' + tag;
  return `const ${v} = (function () {\n${body}\nreturn { ${names.join(', ')} };\n})();\nconst { ${names.join(', ')} } = ${v};\n`;
}

const MODULES = ['geometry.js', 'board-state.js', 'undo.js'];
const TESTS = ['geometry.test.js', 'board-state.test.js', 'undo.test.js'];

function run(argv) {
  const root = argv[0];
  const staticDir = root + '/src/main/resources/static/js/';
  const testDir = root + '/src/test/js/';

  let script = '';
  MODULES.forEach((m, i) => { script += asScope(read(staticDir + m), i); });

  script += `
    const __results = [];
    let __suite = '';
    function suite(name) { __suite = name; }
    function test(name, fn) {
      try { fn(); __results.push({ ok: true, name: __suite + ' > ' + name }); }
      catch (e) { __results.push({ ok: false, name: __suite + ' > ' + name, err: String(e && e.message || e) }); }
    }
    function eq(actual, expected, msg) {
      const a = JSON.stringify(actual), b = JSON.stringify(expected);
      if (a !== b) throw new Error((msg ? msg + ': ' : '') + 'expected ' + b + ' but got ' + a);
    }
    function ok(cond, msg) { if (!cond) throw new Error(msg || 'expected truthy'); }
  `;
  TESTS.forEach((t) => {
    script += '\n// ---- ' + t + '\n{\n' + stripImports(read(testDir + t)) + '\n}\n';
  });
  script += '\nreturn __results;';

  const results = new Function(script)();
  const failed = results.filter((r) => !r.ok);
  const lines = results.map((r) => (r.ok ? 'PASS ' : 'FAIL ') + r.name + (r.ok ? '' : '\n     ' + r.err));
  lines.push('', `${results.length - failed.length}/${results.length} passed`);
  if (failed.length > 0) throw new Error(lines.join('\n'));
  return lines.join('\n');
}
