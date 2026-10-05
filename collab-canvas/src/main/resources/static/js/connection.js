// WebSocket with automatic reconnect (exponential backoff + jitter). Pending ops are NOT queued
// here: BoardState keeps them and the app resends them after each welcome snapshot.

const MIN_DELAY_MS = 500;
const MAX_DELAY_MS = 10000;

export class Connection {
  /**
   * @param {string} url ws(s):// URL
   * @param {{onMessage: function(object), onStatus: function(string), onGiveUp: function(): Promise<boolean>}} handlers
   *        onGiveUp is asked after repeated failures; resolve true to stop reconnecting.
   */
  constructor(url, handlers) {
    this.url = url;
    this.handlers = handlers;
    this.ws = null;
    this.delay = MIN_DELAY_MS;
    this.failures = 0;
    this.stopped = false;
    this.timer = null;
  }

  start() {
    this._open();
  }

  get isOpen() {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  send(obj) {
    if (!this.isOpen) return false;
    this.ws.send(JSON.stringify(obj));
    return true;
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    if (this.ws) this.ws.close(1000, 'bye');
  }

  _open() {
    if (this.stopped) return;
    this.handlers.onStatus(this.failures === 0 ? 'connecting' : 'reconnecting');
    const ws = new WebSocket(this.url);
    this.ws = ws;
    let welcomed = false;
    ws.addEventListener('message', (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch {
        return;
      }
      if (msg && msg.t === 'welcome') {
        welcomed = true;
        this.failures = 0;
        this.delay = MIN_DELAY_MS;
        this.handlers.onStatus('connected');
      }
      this.handlers.onMessage(msg);
    });
    ws.addEventListener('close', (ev) => {
      if (this.ws !== ws) return;
      this.ws = null;
      if (this.stopped) return;
      if (ev.code === 1008) {
        // policy violation: the server decided this client misbehaved; don't hammer it
        this.stopped = true;
        this.handlers.onStatus('refused');
        return;
      }
      if (!welcomed) this.failures++;
      this.handlers.onStatus('reconnecting');
      this._schedule();
    });
  }

  async _schedule() {
    if (this.failures > 0 && this.failures % 3 === 0 && (await this.handlers.onGiveUp())) {
      this.stopped = true;
      return;
    }
    const jitter = this.delay * (0.5 + Math.random() * 0.5);
    this.timer = setTimeout(() => this._open(), jitter);
    this.delay = Math.min(MAX_DELAY_MS, this.delay * 2);
  }
}
