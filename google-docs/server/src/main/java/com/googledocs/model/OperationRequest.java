package com.googledocs.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Client mutation payload. Two accepted forms:
 * <ul>
 *   <li><b>TextOperation</b> (J2CL client, OT documents): {@code ops} = compact components
 *       {@code [5, "abc", -2]} plus a {@code clientOpId} used to recognize the ACK on the SSE stream.</li>
 *   <li><b>Legacy single span</b> (CRDT documents, curl, tests): {@code type/position/text/length}.</li>
 * </ul>
 * Exactly one form must be present; the service layer enforces that.
 */
public class OperationRequest {
    public static final int MAX_OP_COMPONENTS = 4096;

    @NotNull(message = "Session ID is required")
    private String sessionId;

    @Min(value = 0, message = "Base revision must be non-negative")
    private long baseRevision;

    /** Legacy form. Optional when {@code ops} is present. */
    private OperationType type;

    @Min(value = 0, message = "Position must be non-negative")
    private int position;

    @Size(max = 32768, message = "Text payload cannot exceed 32KB")
    private String text;

    @Min(value = 0, message = "Length cannot be negative")
    private int length;

    /** TextOperation components: positive int = retain, negative int = delete, string = insert. */
    @Size(max = MAX_OP_COMPONENTS, message = "Operation has too many components")
    private List<Object> ops;

    @Pattern(regexp = "[A-Za-z0-9-]{1,64}", message = "clientOpId must be 1-64 alphanumeric or '-' characters")
    private String clientOpId;

    public OperationRequest() {}

    public OperationRequest(String sessionId, long baseRevision, OperationType type, int position, String text, int length) {
        this.sessionId = sessionId;
        this.baseRevision = baseRevision;
        this.type = type;
        this.position = position;
        this.text = text != null ? text : "";
        this.length = length;
    }

    public static OperationRequest ofOps(String sessionId, long baseRevision, List<?> ops, String clientOpId) {
        OperationRequest r = new OperationRequest();
        r.sessionId = sessionId;
        r.baseRevision = baseRevision;
        r.ops = ops != null ? new java.util.ArrayList<Object>(ops) : null;
        r.clientOpId = clientOpId;
        return r;
    }

    public boolean hasOps() {
        return ops != null;
    }

    /** Total characters this request would insert (for the document size cap). */
    public int insertedCharCount() {
        if (ops == null) {
            return text != null ? text.length() : 0;
        }
        int total = 0;
        for (Object c : ops) {
            if (c instanceof String s) {
                total += s.length();
            }
        }
        return total;
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

    public List<Object> getOps() {
        return ops;
    }

    public void setOps(List<Object> ops) {
        this.ops = ops;
    }

    public String getClientOpId() {
        return clientOpId;
    }

    public void setClientOpId(String clientOpId) {
        this.clientOpId = clientOpId;
    }
}
