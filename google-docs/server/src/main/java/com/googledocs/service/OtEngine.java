package com.googledocs.service;

import com.googledocs.model.*;
import com.googledocs.ot.OtException;
import com.googledocs.ot.TextOperation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Operational Transformation (OT) engine with a linear revision log.
 *
 * <p>Uses the shared {@code ot-core} {@link TextOperation} (retain / insert / delete), the exact same
 * transform the J2CL browser client runs. An incoming op based on an older revision is transformed
 * against every op committed since, always as the <em>first</em> operand, so insert tie-breaks match
 * the client's ACK queue and TP1 convergence holds.
 */
@Service
public class OtEngine implements CollaborativeEngine {

    static final int MAX_INSERT_CHARS = 32768;
    private static final int MAX_TRACE_OP_CHARS = 120;

    @Override
    public EngineType getEngineType() {
        return EngineType.OT;
    }

    @Override
    public OperationResult applyOperation(Document document, OperationRequest request) {
        StringBuilder buffer = document.getContentBuffer();
        List<CommittedOperation> history = document.getOtHistory();
        long currentRev = document.getRevision();
        long baseRev = request.getBaseRevision();

        if (baseRev > currentRev) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base revision is ahead of the server");
        }

        TextOperation op;
        try {
            op = request.hasOps()
                ? TextOperation.fromWire(request.getOps())
                : fromLegacy(request, lengthAtRevision(buffer.length(), history, baseRev, currentRev));
        } catch (OtException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed operation");
        }
        if (op.insertedChars() > MAX_INSERT_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Operation text exceeds 32KB");
        }

        String originalOp = abbreviate(op.toString());
        List<String> transformTrace = new ArrayList<>();
        int transformationsCount = 0;

        // Transform against every op committed after the client's base revision.
        // history.get(i) holds revision i + 1, so ops after baseRev live at [baseRev, currentRev).
        try {
            for (int i = (int) baseRev; i < (int) currentRev; i++) {
                CommittedOperation committed = history.get(i);
                TextOperation before = op;
                op = TextOperation.transform(op, committed.getOperation())[0];
                transformationsCount++;
                transformTrace.add(String.format("Rev %d (%s at %d): %s -> %s",
                    committed.getRevision(), committed.getType(), committed.getPosition(),
                    abbreviate(before.toString()), abbreviate(op.toString())));
            }
        } catch (OtException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operation does not match the document at its base revision");
        }

        if (op.getBaseLength() != buffer.length()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operation does not match the document length");
        }

        op.applyTo(buffer);

        long newRev = currentRev + 1;
        document.setRevision(newRev);
        document.markUpdated();

        Summary s = summarize(op);
        history.add(new CommittedOperation(
            newRev, request.getSessionId(), s.type, s.position, s.text, s.length, op, request.getClientOpId()
        ));

        Map<String, Object> debugInfo = new LinkedHashMap<>();
        debugInfo.put("engine", "OT");
        debugInfo.put("baseRevision", baseRev);
        debugInfo.put("committedRevision", newRev);
        debugInfo.put("transformationsApplied", transformationsCount);
        debugInfo.put("transformTrace", transformTrace);
        debugInfo.put("originalOps", originalOp);
        debugInfo.put("finalOps", abbreviate(op.toString()));
        debugInfo.put("originalPosition", request.hasOps() ? s.position : request.getPosition());
        debugInfo.put("finalPosition", s.position);

        return new OperationResult(
            true, document.getId(), EngineType.OT, newRev,
            s.type, s.position, s.text, s.length, buffer.toString(), debugInfo,
            op.toWireList(), request.getClientOpId()
        );
    }

    /** Converts a legacy single-span request (position is relative to the base revision document). */
    static TextOperation fromLegacy(OperationRequest request, int baseLength) {
        OperationType type = request.getType();
        if (type == null) {
            throw new OtException("either ops or type is required");
        }
        int pos = Math.max(0, Math.min(request.getPosition(), baseLength));
        String text = request.getText() != null ? request.getText() : "";
        int len = Math.max(0, Math.min(request.getLength(), baseLength - pos));
        return switch (type) {
            case INSERT -> TextOperation.ofSpan(baseLength, pos, 0, text);
            case DELETE -> TextOperation.ofSpan(baseLength, pos, len, "");
            case REPLACE -> TextOperation.ofSpan(baseLength, pos, len, text);
        };
    }

    /** Document length at {@code baseRev}, reconstructed from the current length and the log. */
    static int lengthAtRevision(int currentLength, List<CommittedOperation> history, long baseRev, long currentRev) {
        int length = currentLength;
        for (int i = (int) baseRev; i < (int) currentRev; i++) {
            TextOperation op = history.get(i).getOperation();
            length -= op.getTargetLength() - op.getBaseLength();
        }
        return length;
    }

    /** Single-span view of an op (first changed index, inserted text, deleted count) for the Inspector. */
    static Summary summarize(TextOperation op) {
        List<Object> ops = op.getOps();
        int position = 0;
        int start = 0;
        if (!ops.isEmpty() && TextOperation.isRetain(ops.get(0))) {
            position = (Integer) ops.get(0);
            start = 1;
        }
        StringBuilder inserted = new StringBuilder();
        int deleted = 0;
        for (int i = start; i < ops.size(); i++) {
            Object c = ops.get(i);
            if (TextOperation.isInsert(c)) {
                inserted.append((String) c);
            } else if (TextOperation.isDelete(c)) {
                deleted += -(Integer) c;
            }
        }
        OperationType type = deleted == 0 ? OperationType.INSERT
            : inserted.length() == 0 ? OperationType.DELETE
            : OperationType.REPLACE;
        return new Summary(type, position, inserted.toString(), deleted);
    }

    private static String abbreviate(String s) {
        return s.length() <= MAX_TRACE_OP_CHARS ? s : s.substring(0, MAX_TRACE_OP_CHARS) + "…";
    }

    record Summary(OperationType type, int position, String text, int length) {}
}
