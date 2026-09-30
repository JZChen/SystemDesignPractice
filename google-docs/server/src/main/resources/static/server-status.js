/**
 * Server connectivity monitor.
 *
 * Polls GET /api/health and shows a full-page "Server is down" screen when the server stops
 * answering. When it comes back, the page reloads, because documents live only in server memory
 * and an open tab cannot continue on a restarted server. A changed bootId (quick restart that
 * happened between polls) triggers the same reload.
 *
 * Other modules call ServerStatus.reportProblem() on network errors to trigger an immediate check.
 * Note: this only helps tabs that are already open; a fresh navigation while the server is down
 * never loads this script (the browser shows its own error page).
 */
(function () {
  const HEALTH_URL = '/api/health';
  const POLL_UP_MS = 15000;      // background check while healthy
  const POLL_DOWN_MS = 3000;     // retry interval while down
  const PROBE_TIMEOUT_MS = 3000;
  const FAILURES_BEFORE_DOWN = 2; // avoid flashing the screen on a single blip

  const overlayEl = document.getElementById('serverDownOverlay');
  const titleEl = document.getElementById('serverDownTitle');
  const detailEl = document.getElementById('serverDownDetail');
  const retryBtn = document.getElementById('btnServerRetry');

  let bootId = null;
  let failures = 0;
  let down = false;
  let reloading = false;
  let timer = null;
  let probing = false;

  function schedule(ms) {
    clearTimeout(timer);
    timer = setTimeout(probe, ms);
  }

  function showDown() {
    down = true;
    titleEl.textContent = 'Server is down';
    overlayEl.classList.remove('hidden');
    document.body.classList.add('server-down');
    if (document.activeElement && document.activeElement !== document.body) {
      document.activeElement.blur(); // stop keystrokes reaching the editor behind the overlay
    }
    retryBtn.focus();
  }

  function reloadSoon(message) {
    if (reloading) return;
    reloading = true;
    clearTimeout(timer);
    titleEl.textContent = message;
    detailEl.textContent = 'Reloading…';
    overlayEl.classList.remove('hidden');
    setTimeout(() => window.location.reload(), 800);
  }

  async function probe() {
    if (probing || reloading) return;
    probing = true;
    const controller = new AbortController();
    const abortTimer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
    try {
      const res = await fetch(HEALTH_URL, { cache: 'no-store', signal: controller.signal });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const body = await res.json();
      failures = 0;
      if (down) {
        reloadSoon('Server is back');
      } else if (bootId !== null && body.bootId !== bootId) {
        reloadSoon('Server restarted');
      } else {
        bootId = body.bootId;
        schedule(POLL_UP_MS);
      }
    } catch (err) {
      failures++;
      if (failures >= FAILURES_BEFORE_DOWN && !down) showDown();
      if (down) {
        detailEl.textContent =
          'Cannot reach the server. Last attempt ' + new Date().toLocaleTimeString() +
          ', retrying every ' + POLL_DOWN_MS / 1000 + 's.';
      }
      schedule(down ? POLL_DOWN_MS : 1000);
    } finally {
      clearTimeout(abortTimer);
      probing = false;
    }
  }

  retryBtn.addEventListener('click', () => {
    detailEl.textContent = 'Checking…';
    probe();
  });

  window.ServerStatus = {
    /** Called on SSE / fetch network errors: check right away instead of waiting for the next poll. */
    reportProblem() {
      if (!down && !reloading) schedule(0);
    },
    isDown() {
      return down;
    }
  };

  probe();
})();
