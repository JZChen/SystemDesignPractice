/**
 * Google Docs API Client Module
 * Provides unified access to Spring Boot 3 document and concurrency endpoints.
 */

const API_BASE = window.location.origin.includes(':5173')
  ? 'http://localhost:8080/api'
  : '/api';

/**
 * fetch() wrapper: a TypeError means the request never reached the server (down / network).
 * Report it so the "Server is down" screen appears right away, then rethrow for the caller.
 */
async function apiFetch(url, options) {
  try {
    return await fetch(url, options);
  } catch (err) {
    if (window.ServerStatus) window.ServerStatus.reportProblem();
    throw err;
  }
}

const Api = {
  /**
   * API 1: Creates a new document and receives shareable URL + session token.
   */
  async createDocument(title, engineType = 'OT') {
    const res = await apiFetch(`${API_BASE}/documents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, engineType })
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `Failed to create document (${res.status})`);
    }
    return res.json();
  },

  /**
   * API 2: Reads document state by URL / ID with session token.
   */
  async readDocument(docId, sessionId) {
    const headers = {};
    if (sessionId) {
      headers['X-Session-Id'] = sessionId;
    }
    const res = await apiFetch(`${API_BASE}/documents/${encodeURIComponent(docId)}`, {
      method: 'GET',
      headers
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `Failed to read document (${res.status})`);
    }
    return res.json();
  },

  /**
   * API 3: Applies a mutation.
   *  - OT documents (J2CL ACK queue): { sessionId, baseRevision, ops, clientOpId }
   *    where ops is a TextOperation in compact form, e.g. [5, "abc", -2].
   *  - CRDT documents (legacy): { sessionId, baseRevision, type, position, text, length }.
   * The HTTP response only confirms receipt; the authoritative ACK arrives on the SSE stream.
   */
  async applyOperation(docId, { sessionId, baseRevision, ops, clientOpId, type, position, text, length }) {
    const body = ops
      ? { sessionId, baseRevision: Number(baseRevision), ops: Array.from(ops), clientOpId }
      : {
          sessionId,
          baseRevision: Number(baseRevision),
          type,
          position: Number(position),
          text: text || '',
          length: Number(length || 0)
        };
    const res = await apiFetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/operations`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Session-Id': sessionId
      },
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `Failed to apply operation (${res.status})`);
    }
    return res.json();
  },

  /**
   * Catch-up endpoint: retrieves operation log since revision.
   */
  async getOperationsSince(docId, sinceRevision = 0) {
    const res = await apiFetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/operations?sinceRevision=${sinceRevision}`);
    if (!res.ok) return [];
    return res.json();
  }
};

window.Api = Api;
