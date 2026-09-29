package com.googledocs.service;

import com.googledocs.model.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Handcrafted Operational Transformation (OT) Engine for real-time collaborative text editing.
 * Implements linear revision history tracking and character position transformation functions.
 */
@Service
public class OtEngine implements CollaborativeEngine {

    @Override
    public EngineType getEngineType() {
        return EngineType.OT;
    }

    @Override
    public OperationResult applyOperation(Document document, OperationRequest request) {
        StringBuilder buffer = document.getContentBuffer();
        long currentRev = document.getRevision();
        long baseRev = request.getBaseRevision();

        int transPos = request.getPosition();
        String transText = request.getText() != null ? request.getText() : "";
        int transLen = request.getLength();
        OperationType type = request.getType();

        int transformationsCount = 0;
        List<String> transformTrace = new ArrayList<>();

        // If client's base revision is behind current revision, transform against intervening operations
        if (baseRev < currentRev) {
            List<CommittedOperation> history = document.getOtHistory();
            int startIdx = (int) Math.max(0, baseRev);
            int endIdx = (int) Math.min(history.size(), currentRev);

            for (int i = startIdx; i < endIdx; i++) {
                CommittedOperation committed = history.get(i);
                int oldPos = transPos;
                int oldLen = transLen;

                TransformedOp transformed = transform(
                    type, transPos, transText, transLen, request.getSessionId(), committed
                );

                transPos = transformed.position;
                transLen = transformed.length;
                transText = transformed.text;
                transformationsCount++;

                transformTrace.add(String.format(
                    "Rev %d (%s at %d): pos %d->%d, len %d->%d",
                    committed.getRevision(), committed.getType(), committed.getPosition(),
                    oldPos, transPos, oldLen, transLen
                ));
            }
        }

        // Boundary safety clamps
        int bufLen = buffer.length();
        transPos = Math.max(0, Math.min(transPos, bufLen));
        if (type == OperationType.DELETE || type == OperationType.REPLACE) {
            transLen = Math.max(0, Math.min(transLen, bufLen - transPos));
        }

        // Apply mutation to content buffer
        switch (type) {
            case INSERT -> {
                buffer.insert(transPos, transText);
            }
            case DELETE -> {
                if (transLen > 0) {
                    buffer.delete(transPos, transPos + transLen);
                }
            }
            case REPLACE -> {
                buffer.replace(transPos, transPos + transLen, transText);
            }
        }

        long newRev = currentRev + 1;
        document.setRevision(newRev);
        document.markUpdated();

        // Record in revision log
        CommittedOperation committedOp = new CommittedOperation(
            newRev, request.getSessionId(), type, transPos, transText, transLen
        );
        document.getOtHistory().add(committedOp);

        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("engine", "OT");
        debugInfo.put("baseRevision", baseRev);
        debugInfo.put("committedRevision", newRev);
        debugInfo.put("transformationsApplied", transformationsCount);
        debugInfo.put("transformTrace", transformTrace);
        debugInfo.put("originalPosition", request.getPosition());
        debugInfo.put("finalPosition", transPos);

        return new OperationResult(
            true, document.getId(), EngineType.OT, newRev,
            type, transPos, transText, transLen, buffer.toString(), debugInfo
        );
    }

    /**
     * Core OT transformation function T(incoming, committed).
     */
    public TransformedOp transform(OperationType inType, int inPos, String inText, int inLen,
                                   String inSessionId, CommittedOperation committed) {
        OperationType comType = committed.getType();
        int comPos = committed.getPosition();
        String comText = committed.getText();
        int comLen = committed.getLength();
        String comSessionId = committed.getSessionId();

        int outPos = inPos;
        int outLen = inLen;
        String outText = inText;

        if (inType == OperationType.INSERT) {
            if (comType == OperationType.INSERT) {
                // INSERT vs INSERT
                if (inPos < comPos) {
                    outPos = inPos;
                } else if (inPos > comPos) {
                    outPos = inPos + comText.length();
                } else {
                    // Tie-breaker: deterministic order based on session ID
                    if (inSessionId != null && inSessionId.compareTo(comSessionId) < 0) {
                        outPos = inPos;
                    } else {
                        outPos = inPos + comText.length();
                    }
                }
            } else if (comType == OperationType.DELETE) {
                // INSERT vs DELETE
                if (inPos <= comPos) {
                    outPos = inPos;
                } else if (inPos >= comPos + comLen) {
                    outPos = inPos - comLen;
                } else {
                    // Insert occurred inside deleted range; snap to delete start
                    outPos = comPos;
                }
            } else if (comType == OperationType.REPLACE) {
                // INSERT vs REPLACE
                int delta = comText.length() - comLen;
                if (inPos <= comPos) {
                    outPos = inPos;
                } else if (inPos >= comPos + comLen) {
                    outPos = inPos + delta;
                } else {
                    outPos = comPos + comText.length();
                }
            }
        } else if (inType == OperationType.DELETE) {
            if (comType == OperationType.INSERT) {
                // DELETE vs INSERT
                if (inPos + inLen <= comPos) {
                    // Delete is strictly before insert
                    outPos = inPos;
                    outLen = inLen;
                } else if (inPos >= comPos) {
                    // Delete is at or after insert
                    outPos = inPos + comText.length();
                    outLen = inLen;
                } else {
                    // Insert fell within the deleted range: delete range expands to engulf insert
                    outPos = inPos;
                    outLen = inLen + comText.length();
                }
            } else if (comType == OperationType.DELETE) {
                // DELETE vs DELETE
                int s1 = inPos, e1 = inPos + inLen;
                int s2 = comPos, e2 = comPos + comLen;

                if (e1 <= s2) {
                    // in is strictly before com
                    outPos = inPos;
                    outLen = inLen;
                } else if (s1 >= e2) {
                    // in is strictly after com
                    outPos = inPos - comLen;
                    outLen = inLen;
                } else if (s1 <= s2 && e1 >= e2) {
                    // in completely covers com
                    outPos = inPos;
                    outLen = inLen - comLen;
                } else if (s1 >= s2 && e1 <= e2) {
                    // in is completely inside com (already deleted)
                    outPos = s2;
                    outLen = 0;
                } else if (s1 < s2 && e1 <= e2) {
                    // left overlap
                    outPos = s1;
                    outLen = s2 - s1;
                } else if (s1 >= s2 && e1 > e2) {
                    // right overlap
                    outPos = s2;
                    outLen = e1 - e2;
                }
            } else if (comType == OperationType.REPLACE) {
                // Handle as com DELETE then com INSERT
                int delta = comText.length() - comLen;
                if (inPos + inLen <= comPos) {
                    outPos = inPos;
                    outLen = inLen;
                } else if (inPos >= comPos + comLen) {
                    outPos = inPos + delta;
                    outLen = inLen;
                } else {
                    outPos = comPos + comText.length();
                    outLen = Math.max(0, (inPos + inLen) - (comPos + comLen));
                }
            }
        } else if (inType == OperationType.REPLACE) {
            // Transform REPLACE as DELETE + INSERT offset adjustment
            TransformedOp delPart = transform(OperationType.DELETE, inPos, "", inLen, inSessionId, committed);
            outPos = delPart.position;
            outLen = delPart.length;
        }

        return new TransformedOp(outPos, outText, outLen);
    }

    public record TransformedOp(int position, String text, int length) {}
}
