/**
 * Real-Time Server-Sent Events (SSE) Stream Client
 * Delivers sub-second updates from remote collaborators across high-latency WAN.
 */

class SyncStream {
  constructor(docId, sessionId, callbacks = {}) {
    this.docId = docId;
    this.sessionId = sessionId;
    this.callbacks = callbacks;
    this.eventSource = null;
    this.retryTimeout = null;
  }

  connect() {
    this.disconnect();
    const streamUrl = `/api/documents/${encodeURIComponent(this.docId)}/events?sessionId=${encodeURIComponent(this.sessionId)}`;

    try {
      this.eventSource = new EventSource(streamUrl);

      this.eventSource.addEventListener('connected', (e) => {
        if (this.callbacks.onConnected) {
          this.callbacks.onConnected(JSON.parse(e.data));
        }
      });

      this.eventSource.addEventListener('operation', (e) => {
        if (this.callbacks.onOperation) {
          this.callbacks.onOperation(JSON.parse(e.data));
        }
      });

      this.eventSource.addEventListener('presence', (e) => {
        if (this.callbacks.onPresence) {
          this.callbacks.onPresence(JSON.parse(e.data));
        }
      });

      this.eventSource.onerror = () => {
        if (window.ServerStatus) window.ServerStatus.reportProblem();
        if (this.callbacks.onError) {
          this.callbacks.onError();
        }
        // Auto-reconnect after 3s
        this.disconnect();
        this.retryTimeout = setTimeout(() => this.connect(), 3000);
      };
    } catch (err) {
      console.warn('SSE connection failed, falling back to polling if needed');
    }
  }

  disconnect() {
    if (this.retryTimeout) {
      clearTimeout(this.retryTimeout);
      this.retryTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
}

window.SyncStream = SyncStream;
