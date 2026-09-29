package com.googledocs.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class OperationRequest {
    @NotNull(message = "Session ID is required")
    private String sessionId;

    @Min(value = 0, message = "Base revision must be non-negative")
    private long baseRevision;

    @NotNull(message = "Operation type is required")
    private OperationType type;

    @Min(value = 0, message = "Position must be non-negative")
    private int position;

    @Size(max = 32768, message = "Text payload cannot exceed 32KB")
    private String text;

    @Min(value = 0, message = "Length cannot be negative")
    private int length;

    public OperationRequest() {}

    public OperationRequest(String sessionId, long baseRevision, OperationType type, int position, String text, int length) {
        this.sessionId = sessionId;
        this.baseRevision = baseRevision;
        this.type = type;
        this.position = position;
        this.text = text != null ? text : "";
        this.length = length;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(long baseRevision) {
        this.baseRevision = baseRevision;
    }

    public OperationType getType() {
        return type;
    }

    public void setType(OperationType type) {
        this.type = type;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }
}
