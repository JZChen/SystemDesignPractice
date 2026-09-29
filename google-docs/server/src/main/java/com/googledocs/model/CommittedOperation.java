package com.googledocs.model;

import java.time.Instant;

/**
 * An operation that has been committed to the document's OT revision history.
 */
public class CommittedOperation {
    private final long revision;
    private final String sessionId;
    private final OperationType type;
    private final int position;
    private final String text;
    private final int length;
    private final Instant timestamp;

    public CommittedOperation(long revision, String sessionId, OperationType type, int position, String text, int length) {
        this.revision = revision;
        this.sessionId = sessionId;
        this.type = type;
        this.position = position;
        this.text = text != null ? text : "";
        this.length = length;
        this.timestamp = Instant.now();
    }

    public long getRevision() {
        return revision;
    }

    public String getSessionId() {
        return sessionId;
    }

    public OperationType getType() {
        return type;
    }

    public int getPosition() {
        return position;
    }

    public String getText() {
        return text;
    }

    public int getLength() {
        return length;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
