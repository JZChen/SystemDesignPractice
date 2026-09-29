/**
 * Google Docs API Client Module
 * Provides unified access to Spring Boot 3 document and concurrency endpoints.
 */

const API_BASE = window.location.origin.includes(':5173')
  ? 'http://localhost:8080/api'
  : '/api';

const Api = {
  /**
   * API 1: Creates a new document and receives shareable URL + session token.
   */
  async createDocument(title, engineType = 'OT') {
    const res = await fetch(`${API_BASE}/documents`, {
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
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(docId)}`, {
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
   * API 3: Applies a character-level mutation operation (INSERT, DELETE, REPLACE).
   */
  async applyOperation(docId, { sessionId, baseRevision, type, position, text, length }) {
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/operations`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Session-Id': sessionId
      },
      body: JSON.stringify({
        sessionId,
        baseRevision: Number(baseRevision),
        type,
        position: Number(position),
        text: text || '',
        length: Number(length || 0)
      })
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
    const res = await fetch(`${API_BASE}/documents/${encodeURIComponent(docId)}/operations?sinceRevision=${sinceRevision}`);
    if (!res.ok) return [];
    return res.json();
  }
};

window.Api = Api;
