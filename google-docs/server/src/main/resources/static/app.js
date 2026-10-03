/**
 * Main Google Docs Application Logic
 * Coordinates Document Lifecycle, Keystroke Operation Extraction,
 * Concurrency Synchronizer, and the Visual Engine Inspector.
 */

(function () {
  'use strict';

  // State
  let currentDoc = null;
  let currentSessionId = localStorage.getItem('google_docs_session_id') || null; // TODO(security): move to HttpOnly cookie + CSRF
  let currentRevision = 0;
  let syncStream = null;
  let isApplyingRemoteChange = false;
  let operationLog = [];

  // Local edit buffer (legacy path, used until the J2CL OtClient ships; same policy as the Java
  // client). Keystrokes stay in the textarea and go out as ONE op after a 2 s typing pause, or
  // after 10 s of continuous typing. At most one op is in flight; its SSE echo is the ACK.
  // Every send is diffed against serverContent, so whatever the server is missing gets sent.
  const IDLE_SEND_MS = 2000;
  const MAX_BUFFER_MS = 10000;
  let serverContent = '';     // document text at serverRevision, as last seen from the server
  let serverRevision = 0;
  let firstUnsentAt = null;   // performance.now() of the first keystroke not yet sent
  let inflight = null;        // { clientOpId, op } waiting for its SSE echo
  let sendRequested = false;  // a pause was reached while an op was in flight: send on ACK
  let opCounter = 0;
  let flushTimer = null;      // pending typing-pause send
  let composing = false;      // IME composition in progress: never send a half-composed char
  const instanceId = Math.random().toString(36).slice(2, 10);

  // DOM Elements
  const docTitleEl = document.getElementById('docTitle');
  const saveStatusEl = document.getElementById('saveStatus');
  const engineBadgeEl = document.getElementById('engineBadge');
  const revisionBadgeEl = document.getElementById('revisionBadge');
  const avatarListEl = document.getElementById('avatarList');
  const docEditorEl = document.getElementById('docEditor');
  const cursorPosIndicatorEl = document.getElementById('cursorPosIndicator');
  const charCountIndicatorEl = document.getElementById('charCountIndicator');
  const sessionIdIndicatorEl = document.getElementById('sessionIdIndicator');

  // Buttons & Modals
  const btnNewDocEl = document.getElementById('btnNewDoc');
  const btnShareEl = document.getElementById('btnShare');
  const btnShareTextEl = document.getElementById('btnShareText');
  const btnToggleInspectorEl = document.getElementById('btnToggleInspector');
  const btnCloseInspectorEl = document.getElementById('btnCloseInspector');
  const inspectorDrawerEl = document.getElementById('inspectorDrawer');
  const inspectorEngineTagEl = document.getElementById('inspectorEngineTag');
  const engineSummaryTextEl = document.getElementById('engineSummaryText');
  const operationFeedListEl = document.getElementById('operationFeedList');
  const diagnosticStateViewEl = document.getElementById('diagnosticStateView');
  const opFeedCountEl = document.getElementById('opFeedCount');
  const syncStateBadgeEl = document.getElementById('syncStateBadge');
  const ackQueueViewEl = document.getElementById('ackQueueView');

  const modalBackdropEl = document.getElementById('modalBackdrop');
  const btnCloseModalEl = document.getElementById('btnCloseModal');
  const btnCancelModalEl = document.getElementById('btnCancelModal');
  const createDocFormEl = document.getElementById('createDocForm');
  const newDocTitleInputEl = document.getElementById('newDocTitle');
  const toastNotificationEl = document.getElementById('toastNotification');

  // --------------------------------------------------------------------------
  // Document Initialization & URL Routing
  // --------------------------------------------------------------------------

  function getDocIdFromUrl() {
    const params = new URLSearchParams(window.location.search);
    if (params.get('docId')) return params.get('docId');

    const pathParts = window.location.pathname.split('/').filter(Boolean);
    if (pathParts.length >= 2 && pathParts[0] === 'docs') {
      return pathParts[1];
    }
    return null;
  }

  function setDocUrl(docId) {
    const newUrl = `${window.location.origin}/docs/${docId}`;
    window.history.pushState({ docId }, '', newUrl);
  }

  async function initApp() {
    setupEventListeners();

    const docId = getDocIdFromUrl();
    if (docId) {
      await loadExistingDocument(docId);
    } else {
      await createInitialDemoDocument();
    }
  }

  async function createInitialDemoDocument() {
    try {
      saveStatusEl.textContent = 'Initializing...';
      const doc = await Api.createDocument('Collaborative System Design Notes', 'OT');
      currentSessionId = doc.sessionId;
      localStorage.setItem('google_docs_session_id', currentSessionId);
      setDocUrl(doc.docId);
      renderDocument(doc);
      connectRealTimeSync(doc.docId);

      // Preload initial welcome text (programmatic content: sent right away, no typing window)
      setTimeout(() => {
        docEditorEl.value =
          'Welcome to Collaborative Google Docs!\n\nThis prototype demonstrates real-time distributed text editing backed by Spring Boot 3.\n\nHighlights:\n• Concurrency Engine: Pluggable Operational Transformation (OT) & Sequence CRDT (LSeq).\n• Sub-second global sync: Push stream with Server-Sent Events (SSE).\n• Open the Concurrency Inspector (top-right icon) to see live transformations in action!\n• Click "Share Link" to collaborate with friends globally across the internet.\n';
        updateEditorStats();
        requestSend();
      }, 300);
    } catch (err) {
      showToast('Error initializing document: ' + err.message);
    }
  }

  async function loadExistingDocument(docId) {
    try {
      saveStatusEl.textContent = 'Connecting...';
      const doc = await Api.readDocument(docId, currentSessionId);
      if (doc.sessionId) {
        currentSessionId = doc.sessionId;
        localStorage.setItem('google_docs_session_id', currentSessionId);
      }
      renderDocument(doc);
      connectRealTimeSync(docId);
    } catch (err) {
      showToast('Could not load document: ' + err.message);
      // Fallback to fresh doc
      await createInitialDemoDocument();
    }
  }

  function renderDocument(doc) {
    currentDoc = doc;
    currentRevision = doc.revision || 0;
    serverRevision = currentRevision;
    serverContent = doc.content || '';
    clearTimeout(flushTimer);
    flushTimer = null;
    firstUnsentAt = null;
    inflight = null;
    sendRequested = false;

    docTitleEl.textContent = doc.title || 'Untitled Document';
    docEditorEl.value = doc.content || '';
    revisionBadgeEl.textContent = `Rev ${currentRevision}`;
    engineBadgeEl.textContent = `${doc.engineType || 'OT'} Engine`;
    inspectorEngineTagEl.textContent = `${doc.engineType || 'OT'} Strategy`;

    if (doc.engineType === 'CRDT') {
      engineBadgeEl.className = 'badge badge-purple';
      inspectorEngineTagEl.className = 'badge badge-purple';
      engineSummaryTextEl.textContent =
        'Sequence CRDT (LSeq): Characters carry globally unique, totally ordered fractional position identifiers with tombstones for deletion. Commutative across distributed networks.';
    } else {
      engineBadgeEl.className = 'badge badge-engine';
      inspectorEngineTagEl.className = 'badge badge-engine';
      engineSummaryTextEl.textContent =
        'Operational Transformation (OT): Centralized revision log with transformation matrix. Client mutations crossing in flight are adjusted by index offset recalculations.';
    }

    sessionIdIndicatorEl.textContent = currentSessionId ? currentSessionId.slice(0, 12) + '...' : 'Guest';
    updateEditorStats();
    updatePresence(doc.activeUsers || []);
    renderSyncState();
  }

  // --------------------------------------------------------------------------
  // Real-Time Collaboration Sync (SSE)
  // --------------------------------------------------------------------------

  function connectRealTimeSync(docId) {
    if (syncStream) {
      syncStream.disconnect();
    }

    syncStream = new SyncStream(docId, currentSessionId, {
      onConnected: () => {
        saveStatusEl.textContent = 'Live Synced';
      },
      onOperation: (data) => {
        handleRemoteOperation(data);
      },
      onPresence: (data) => {
        if (data.activeUsers) {
          updatePresence(data.activeUsers);
        }
      },
      onError: () => {
        saveStatusEl.textContent = 'Reconnecting...';
      }
    });

    syncStream.connect();
  }

  function handleRemoteOperation(data) {
    if (typeof data.revision !== 'number' || data.revision <= serverRevision) return; // duplicate / stale

    const oldServer = serverContent;
    const newServer = typeof data.content === 'string' ? data.content : oldServer;
    serverContent = newServer;
    serverRevision = data.revision;
    currentRevision = data.revision;
    revisionBadgeEl.textContent = `Rev ${currentRevision}`;

    // ACK = the SSE echo of our in-flight op (clientOpId match; session match if the engine
    // doesn't echo clientOpId). Other tabs of the same session are treated as remote peers.
    const isOwnAck = inflight !== null && (data.clientOpId
      ? data.clientOpId === inflight.clientOpId
      : data.authorSessionId === currentSessionId);

    if (isOwnAck) {
      inflight = null;
      const hasUnsent = flushTimer !== null || sendRequested;
      if (!hasUnsent && !composing && docEditorEl.value !== newServer) {
        // Nothing typed since the send, but the editor disagrees with the server: server wins.
        const fix = toSpan(calculateDiffOperation(docEditorEl.value, newServer));
        renderEditor(newServer, (i) => mapIndex(i, fix));
      }
      if (sendRequested) {
        sendBuffer();
      } else {
        renderSyncState();
      }
      return;
    }

    // Remote op. Keep everything the server doesn't have yet (in-flight + unsent typing) and
    // re-apply it on top of the new server text instead of overwriting it.
    const remote = remoteSpan(data, oldServer, newServer);
    const local = toSpan(calculateDiffOperation(oldServer, docEditorEl.value));
    if (remote) {
      if (!local) {
        renderEditor(newServer, (i) => mapIndex(i, remote));
      } else {
        const rebased = transformSpan(local, remote);
        const merged = applySpan(newServer, rebased);
        renderEditor(merged, (i) => mapCursorThroughRebase(i, local, remote, rebased));
      }
    }

    addOperationToFeed({
      type: data.type || 'REPLACE',
      position: data.position,
      text: data.text || '',
      length: data.length,
      revision: data.revision,
      isRemote: true,
      debugInfo: data.debugInfo
    });
    renderSyncState();
  }

  /** Replace the textarea text (if changed) and remap the selection with mapFn. */
  function renderEditor(text, mapFn) {
    if (docEditorEl.value === text) return;
    const start = docEditorEl.selectionStart;
    const end = docEditorEl.selectionEnd;
    isApplyingRemoteChange = true;
    try {
      docEditorEl.value = text;
      const clamp = (i) => Math.max(0, Math.min(text.length, i));
      docEditorEl.setSelectionRange(clamp(mapFn(start)), clamp(mapFn(end)));
      updateEditorStats();
    } finally {
      isApplyingRemoteChange = false;
    }
  }

  // --------------------------------------------------------------------------
  // Single-span helpers: a span is { pos, del, ins } = "delete del chars at pos, insert ins".
  // --------------------------------------------------------------------------

  function toSpan(op) {
    return op ? { pos: op.position, del: op.length || 0, ins: op.text || '' } : null;
  }

  /**
   * The committed remote edit as a span: the server's own single-span fields when they turn
   * oldServer into newServer exactly (a text diff is ambiguous, e.g. "a two three" -> "a three"),
   * otherwise a prefix/suffix diff. Null if the text didn't change.
   */
  function remoteSpan(data, oldServer, newServer) {
    if (oldServer === newServer) return null;
    if (typeof data.position === 'number' && data.type) {
      const s = {
        pos: data.position,
        del: data.type === 'INSERT' ? 0 : (data.length || 0),
        ins: data.type === 'DELETE' ? '' : (data.text || '')
      };
      if (s.pos >= 0 && s.pos + s.del <= oldServer.length && applySpan(oldServer, s) === newServer) {
        return s;
      }
    }
    return toSpan(calculateDiffOperation(oldServer, newServer));
  }

  function applySpan(text, s) {
    return text.slice(0, s.pos) + s.ins + text.slice(s.pos + s.del);
  }

  /** Index in the text before s -> index after s (an insert at i pushes i to the right). */
  function mapIndex(i, s) {
    if (!s || i < s.pos) return i;
    if (i >= s.pos + s.del) return i + s.ins.length - s.del;
    return s.pos + s.ins.length; // inside the deleted range
  }

  /**
   * Rebase local span l onto remote span r (both relative to the same base text). Ties: a local
   * insert at the same index goes before the remote one. If the local delete wraps the whole
   * remote edit, the remote text is deleted too (single-span limitation; the next send diffs
   * against the server text, so the server stays authoritative).
   */
  function transformSpan(l, r) {
    const d = r.ins.length - r.del;
    const rEnd = r.pos + r.del;
    const lEnd = l.pos + l.del;
    if (lEnd <= r.pos) return { pos: l.pos, del: l.del, ins: l.ins };
    if (l.pos >= rEnd) return { pos: l.pos + d, del: l.del, ins: l.ins };
    const start = l.pos <= r.pos ? l.pos : r.pos + r.ins.length;
    const end = lEnd >= rEnd ? lEnd + d : r.pos;
    return { pos: start, del: Math.max(0, end - start), ins: l.ins };
  }

  /** Cursor index in the editor (base + l) -> index in the merged text (base + r + rebased l). */
  function mapCursorThroughRebase(i, l, r, rebased) {
    if (i < l.pos) return mapIndex(i, r);
    if (i <= l.pos + l.ins.length) return rebased.pos + (i - l.pos);
    const inBase = i - l.ins.length + l.del;
    return Math.max(rebased.pos + l.ins.length, mapIndex(inBase, r) + l.ins.length - rebased.del);
  }

  function updatePresence(users) {
    avatarListEl.replaceChildren();
    if (!users || !users.length) return;

    users.forEach((user) => {
      const avatar = document.createElement('div');
      avatar.className = 'avatar-item';
      avatar.style.backgroundColor = user.color || '#3b82f6';
      avatar.textContent = (user.displayName || 'Anon').charAt(0).toUpperCase();
      avatar.title = `${user.displayName || 'Anonymous User'} (${user.sessionId === currentSessionId ? 'You' : 'Remote'})`;
      avatarListEl.appendChild(avatar);
    });
  }

  // --------------------------------------------------------------------------
  // Editor Keystroke & Character Mutation Extraction
  // --------------------------------------------------------------------------

  function setupEventListeners() {
    docEditorEl.addEventListener('input', handleEditorInput);
    docEditorEl.addEventListener('compositionstart', () => { composing = true; });
    docEditorEl.addEventListener('compositionend', () => {
      composing = false;
      handleEditorInput();
    });
    docEditorEl.addEventListener('keyup', updateEditorStats);
    docEditorEl.addEventListener('click', updateEditorStats);

    // Inspector toggle
    btnToggleInspectorEl.addEventListener('click', () => {
      inspectorDrawerEl.classList.toggle('closed');
    });
    btnCloseInspectorEl.addEventListener('click', () => {
      inspectorDrawerEl.classList.add('closed');
    });

    // Share link button
    btnShareEl.addEventListener('click', handleShareLink);

    // New Document modal
    btnNewDocEl.addEventListener('click', () => {
      modalBackdropEl.classList.remove('hidden');
      newDocTitleInputEl.focus();
    });
    btnCloseModalEl.addEventListener('click', () => modalBackdropEl.classList.add('hidden'));
    btnCancelModalEl.addEventListener('click', () => modalBackdropEl.classList.add('hidden'));
    createDocFormEl.addEventListener('submit', handleCreateDocSubmit);

    // Save title changes on blur
    docTitleEl.addEventListener('blur', () => {
      if (currentDoc) {
        currentDoc.title = docTitleEl.textContent.trim() || 'Untitled Document';
      }
    });
  }

  /** Keystroke: the text stays in the textarea (the local buffer); only the send timer moves. */
  function handleEditorInput() {
    if (isApplyingRemoteChange || !currentDoc) return;
    sendRequested = false; // still typing: wait for the next pause, even if an ACK arrives first
    scheduleSend();
    updateEditorStats();
    renderSyncState();
  }

  /** (Re)arm the send timer: 2 s after the last keystroke, but no later than 10 s after the first. */
  function scheduleSend() {
    const now = performance.now();
    if (firstUnsentAt === null) firstUnsentAt = now;
    const delay = Math.max(0, Math.min(IDLE_SEND_MS, firstUnsentAt + MAX_BUFFER_MS - now));
    clearTimeout(flushTimer);
    flushTimer = setTimeout(onTypingPause, delay);
  }

  function onTypingPause() {
    flushTimer = null;
    if (composing) {
      // Never send a half-composed IME character; try again after the next pause.
      flushTimer = setTimeout(onTypingPause, IDLE_SEND_MS);
      return;
    }
    firstUnsentAt = null;
    requestSend();
  }

  /** Send now if nothing is in flight; otherwise send as soon as the in-flight op is ACKed. */
  function requestSend() {
    if (inflight) {
      sendRequested = true;
      renderSyncState();
      return;
    }
    sendBuffer();
  }

  async function sendBuffer() {
    sendRequested = false;
    if (!currentDoc) return;
    const docId = currentDoc.docId;
    const op = calculateDiffOperation(serverContent, docEditorEl.value);
    if (!op) {
      renderSyncState();
      return;
    }
    const clientOpId = `L${instanceId}-${++opCounter}`;
    inflight = { clientOpId, op };
    renderSyncState();

    try {
      const result = await Api.applyOperation(docId, {
        sessionId: currentSessionId,
        baseRevision: serverRevision,
        clientOpId,
        type: op.type,
        position: op.position,
        text: op.text,
        length: op.length
      });

      // Not the ACK: the SSE echo is (it is the only channel ordered by revision).
      addOperationToFeed({
        type: op.type,
        position: result.position,
        text: result.text,
        length: result.length,
        revision: result.revision,
        isRemote: false,
        debugInfo: result.debugInfo
      });
    } catch (err) {
      if (inflight && inflight.clientOpId === clientOpId) {
        inflight = null; // the text is still in the editor; the next pause retries it
      }
      renderSyncState();
      saveStatusEl.textContent = 'Sync error';
      showToast('Mutation error: ' + err.message);
    }
  }

  /** Header status + Inspector ACK-queue view for the local buffer. */
  function renderSyncState() {
    const buffering = flushTimer !== null || sendRequested;
    let state;
    if (inflight && buffering) state = 'AwaitingWithBuffer';
    else if (inflight) state = 'AwaitingConfirm';
    else if (buffering) state = 'Buffering';
    else if (docEditorEl.value !== serverContent) state = 'Unsaved';
    else state = 'Synchronized';

    syncStateBadgeEl.textContent = state;
    if (state === 'Buffering' || state === 'AwaitingWithBuffer') {
      saveStatusEl.textContent = 'Editing… saves 2 s after you stop typing';
    } else if (state === 'AwaitingConfirm') {
      saveStatusEl.textContent = 'Saving...';
    } else if (state === 'Unsaved') {
      saveStatusEl.textContent = 'Unsaved changes';
    } else {
      saveStatusEl.textContent = 'All changes saved';
    }

    const describe = (op) => op
      ? `${op.type} @${op.position}` + (op.length ? ` -${op.length}` : '') +
        (op.text ? ` "${op.text.length > 20 ? op.text.slice(0, 20) + '…' : op.text}"` : '')
      : '-';
    ackQueueViewEl.textContent =
      `inflight: ${inflight ? inflight.clientOpId + ' ' + describe(inflight.op) : '-'}\n` +
      `unsaved:  ${describe(calculateDiffOperation(serverContent, docEditorEl.value))}` +
      (sendRequested ? '  (send on ACK)' : '');
  }

  /**
   * Fast diff algorithm extracting character-level INSERT, DELETE, or REPLACE operation.
   */
  function calculateDiffOperation(oldStr, newStr) {
    let start = 0;
    const oldLen = oldStr.length;
    const newLen = newStr.length;

    // Find common prefix
    while (start < oldLen && start < newLen && oldStr[start] === newStr[start]) {
      start++;
    }

    // Find common suffix
    let oldEnd = oldLen - 1;
    let newEnd = newLen - 1;
    while (oldEnd >= start && newEnd >= start && oldStr[oldEnd] === newStr[newEnd]) {
      oldEnd--;
      newEnd--;
    }

    const deleteLen = oldEnd - start + 1;
    const insertText = newStr.slice(start, newEnd + 1);

    if (deleteLen === 0 && insertText.length > 0) {
      return { type: 'INSERT', position: start, text: insertText, length: 0 };
    } else if (deleteLen > 0 && insertText.length === 0) {
      return { type: 'DELETE', position: start, text: '', length: deleteLen };
    } else if (deleteLen > 0 && insertText.length > 0) {
      return { type: 'REPLACE', position: start, text: insertText, length: deleteLen };
    }
    return null;
  }

  function updateEditorStats() {
    const pos = docEditorEl.selectionStart || 0;
    const val = docEditorEl.value || '';
    charCountIndicatorEl.textContent = val.length.toLocaleString();

    // Calculate line and col
    const lines = val.slice(0, pos).split('\n');
    const lineNum = lines.length;
    const colNum = lines[lines.length - 1].length;
    cursorPosIndicatorEl.textContent = `Line ${lineNum}, Col ${colNum} (Idx ${pos})`;
  }

  // --------------------------------------------------------------------------
  // Inspector Drawer & Diagnostics
  // --------------------------------------------------------------------------

  function addOperationToFeed(op) {
    operationLog.unshift(op);
    if (operationLog.length > 50) operationLog.pop();

    opFeedCountEl.textContent = `${operationLog.length} events`;
    operationFeedListEl.replaceChildren();

    operationLog.slice(0, 20).forEach((item) => {
      const card = document.createElement('div');
      card.className = 'op-feed-item';

      const topRow = document.createElement('div');
      topRow.className = 'op-feed-top';

      const tag = document.createElement('span');
      tag.className = `op-tag ${item.type.toLowerCase()}`;
      tag.textContent = `${item.type} [Rev ${item.revision}]`;

      const time = document.createElement('span');
      time.className = 'op-time';
      time.textContent = item.isRemote ? 'Remote Peer' : 'You';

      topRow.appendChild(tag);
      topRow.appendChild(time);

      const detail = document.createElement('div');
      detail.className = 'op-detail';

      if (item.type === 'INSERT') {
        const preview = item.text.length > 25 ? item.text.slice(0, 25) + '...' : item.text;
        detail.textContent = `pos ${item.position}: "${preview.replace(/\n/g, '↵')}"`;
      } else if (item.type === 'DELETE') {
        detail.textContent = `pos ${item.position}, len ${item.length}`;
      } else {
        detail.textContent = `pos ${item.position}, replace ${item.length} with "${item.text}"`;
      }

      card.appendChild(topRow);
      card.appendChild(detail);
      operationFeedListEl.appendChild(card);
    });

    // Update diagnostic state view with telemetry
    if (op.debugInfo) {
      diagnosticStateViewEl.textContent = JSON.stringify(op.debugInfo, null, 2);
    }
  }

  // --------------------------------------------------------------------------
  // Sharing & Creation Handlers
  // --------------------------------------------------------------------------

  function handleShareLink() {
    if (!currentDoc) return;
    const shareUrl = `${window.location.origin}/docs/${currentDoc.docId}`;

    navigator.clipboard.writeText(shareUrl).then(() => {
      btnShareTextEl.textContent = 'Copied!';
      showToast('Global document link copied to clipboard!');
      setTimeout(() => {
        btnShareTextEl.textContent = 'Share Link';
      }, 2000);
    }).catch(() => {
      prompt('Copy this document URL to share with friends:', shareUrl);
    });
  }

  async function handleCreateDocSubmit(e) {
    e.preventDefault();
    const title = newDocTitleInputEl.value.trim() || 'Untitled Document';
    const engineType = document.querySelector('input[name="engineSelection"]:checked').value;

    modalBackdropEl.classList.add('hidden');
    saveStatusEl.textContent = 'Creating...';

    try {
      const doc = await Api.createDocument(title, engineType);
      currentSessionId = doc.sessionId;
      localStorage.setItem('google_docs_session_id', currentSessionId);
      setDocUrl(doc.docId);
      renderDocument(doc);
      connectRealTimeSync(doc.docId);
      operationLog = [];
      operationFeedListEl.replaceChildren();
      showToast(`Created new ${engineType} document!`);
    } catch (err) {
      showToast('Failed to create document: ' + err.message);
    }
  }

  function showToast(message) {
    toastNotificationEl.textContent = message;
    toastNotificationEl.classList.remove('hidden');
    setTimeout(() => {
      toastNotificationEl.classList.add('hidden');
    }, 3500);
  }

  // Start the application when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initApp);
  } else {
    initApp();
  }
})();
