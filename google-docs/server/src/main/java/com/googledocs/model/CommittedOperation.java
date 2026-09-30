package com.googledocs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.googledocs.ot.TextOperation;

import java.time.Instant;
import java.util.List;

/**
 * One entry of the OT revision log. {@code operation} is the committed (already transformed)
 * {@link TextOperation} and is what later concurrent operations are transformed against.
 * The legacy {@code type/position/text/length} fields summarize it for the Inspector UI.
 */
public class CommittedOperation {
    private final long revision;
    private final String sessionId;
    private final OperationType type;
    private final int position;
    private final String text;
    private final int length;
    private final Instant timestamp;
    private final TextOperation operation;
    private final String clientOpId;

    public CommittedOperation(long revision, String sessionId, OperationType type, int position, String text, int length) {
        this(revision, sessionId, type, position, text, length, null, null);
    }

    public CommittedOperation(long revision, String sessionId, OperationType type, int position, String text, int length,
                              TextOperation operation, String clientOpId) {
        this.revision = revision;
        this.sessionId = sessionId;
        this.type = type;
        this.position = position;
        this.text = text != null ? text : "";
        this.length = length;
        this.timestamp = Instant.now();
        this.operation = operation;
        this.clientOpId = clientOpId;
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

    @JsonIgnore
    public TextOperation getOperation() {
        return operation;
    }

    /** Wire form of the committed op (for the catch-up endpoint). */
    public List<Object> getOps() {
        return operation != null ? operation.toWireList() : null;
    }

    public String getClientOpId() {
        return clientOpId;
    }
}
