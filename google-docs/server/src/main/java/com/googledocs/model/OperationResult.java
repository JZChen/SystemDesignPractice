package com.googledocs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    private final List<Object> ops;
    private final String clientOpId;

    public OperationResult(boolean success, String docId, EngineType engineType, long revision,
                           OperationType type, int position, String text, int length,
                           String content, Map<String, Object> debugInfo) {
        this(success, docId, engineType, revision, type, position, text, length, content, debugInfo, null, null);
    }

    public OperationResult(boolean success, String docId, EngineType engineType, long revision,
                           OperationType type, int position, String text, int length,
                           String content, Map<String, Object> debugInfo,
                           List<Object> ops, String clientOpId) {
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
        this.ops = ops;
        this.clientOpId = clientOpId;
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

    /**
     * Full document text after this revision. Used server-side (SSE for CRDT docs) and in tests;
     * not serialized in the HTTP response.
     */
    @JsonIgnore
    public String getContent() {
        return content;
    }

    public int getContentLength() {
        return content != null ? content.length() : 0;
    }

    public Map<String, Object> getDebugInfo() {
        return debugInfo;
    }

    public List<Object> getOps() {
        return ops;
    }

    public String getClientOpId() {
        return clientOpId;
    }
}
