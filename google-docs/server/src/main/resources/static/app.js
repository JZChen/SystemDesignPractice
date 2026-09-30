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
  let previousContent = '';
  let operationLog = [];

  // J2CL client core (OT documents). The Java side owns the typing window, the ACK queue and
  // remote-op merging; this file only renders, runs timers and talks to the network.
  let otClient = null;
  let flushTimer = null;
  let composing = false;
  let queuedServerEvents = [];
  let resyncing = false;

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

      // Preload initial welcome text
      setTimeout(() => {
        applyLocalChange(
          'Welcome to Collaborative Google Docs!\n\nThis prototype demonstrates real-time distributed text editing backed by Spring Boot 3.\n\nHighlights:\n• Concurrency Engine: Pluggable Operational Transformation (OT) & Sequence CRDT (LSeq).\n• Sub-second global sync: Push stream with Server-Sent Events (SSE).\n• Open the Concurrency Inspector (top-right icon) to see live transformations in action!\n• Click "Share Link" to collaborate with friends globally across the internet.\n'
        );
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
    previousContent = doc.content || '';

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
    saveStatusEl.textContent = 'All changes saved';
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
    // If we authored this operation, we already applied it locally
    if (data.authorSessionId && data.authorSessionId === currentSessionId) {
      currentRevision = data.revision;
      revisionBadgeEl.textContent = `Rev ${currentRevision}`;
      return;
    }

    isApplyingRemoteChange = true;
    try {
      const cursorStart = docEditorEl.selectionStart;
      const cursorEnd = docEditorEl.selectionEnd;

      // Update editor text safely
      docEditorEl.value = data.content;
      previousContent = data.content;
      currentRevision = data.revision;
      revisionBadgeEl.textContent = `Rev ${currentRevision}`;

      // Adjust cursor position if remote edit happened before our cursor
      let newCursor = cursorStart;
      if (data.type === 'INSERT' && data.position <= cursorStart) {
        newCursor = cursorStart + (data.text ? data.text.length : 0);
      } else if (data.type === 'DELETE' && data.position < cursorStart) {
        newCursor = Math.max(data.position, cursorStart - (data.length || 0));
      }
      docEditorEl.setSelectionRange(newCursor, newCursor);

      updateEditorStats();

      // Log in inspector
      addOperationToFeed({
        type: data.type,
        position: data.position,
        text: data.text,
        length: data.length,
        revision: data.revision,
        isRemote: true,
        debugInfo: data.debugInfo
      });
    } finally {
      isApplyingRemoteChange = false;
    }
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

  function handleEditorInput() {
    if (isApplyingRemoteChange || !currentDoc) return;

    const newContent = docEditorEl.value;
    applyLocalChange(newContent);
  }

  async function applyLocalChange(newContent) {
    const oldContent = previousContent;
    if (newContent === oldContent) return;

    // Diff oldContent and newContent to find single mutation
    const op = calculateDiffOperation(oldContent, newContent);
    if (!op) return;

    previousContent = newContent;
    saveStatusEl.textContent = 'Saving...';
    updateEditorStats();

    try {
      const result = await Api.applyOperation(currentDoc.docId, {
        sessionId: currentSessionId,
        baseRevision: currentRevision,
        type: op.type,
        position: op.position,
        text: op.text,
        length: op.length
      });

      currentRevision = result.revision;
      revisionBadgeEl.textContent = `Rev ${currentRevision}`;
      saveStatusEl.textContent = 'All changes saved';

      // Log to inspector
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
      saveStatusEl.textContent = 'Sync error';
      showToast('Mutation error: ' + err.message);
    }
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
