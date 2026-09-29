package com.googledocs.model;

import java.util.Map;

public class OperationResult {
    private final boolean success;
    private final String docId;
    private final EngineType engineType;
    private final long revision;
    private final OperationType type;
    private final int position;
    private final String text;
    private final int length;
    private final String content;
    private final Map<String, Object> debugInfo;

    public OperationResult(boolean success, String docId, EngineType engineType, long revision,
                           OperationType type, int position, String text, int length,
                           String content, Map<String, Object> debugInfo) {
        this.success = success;
        this.docId = docId;
        this.engineType = engineType;
        this.revision = revision;
        this.type = type;
        this.position = position;
        this.text = text != null ? text : "";
        this.length = length;
        this.content = content;
        this.debugInfo = debugInfo;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDocId() {
        return docId;
    }

    public EngineType getEngineType() {
        return engineType;
    }

    public long getRevision() {
        return revision;
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

    public String getContent() {
        return content;
    }

    public Map<String, Object> getDebugInfo() {
        return debugInfo;
    }
}
