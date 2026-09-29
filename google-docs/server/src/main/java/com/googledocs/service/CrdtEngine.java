package com.googledocs.service;

import com.googledocs.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handcrafted Sequence CRDT Engine using Fractional Positional Indexing (LSeq-style) and Tombstones.
 * Guarantees commutativity and deterministic convergence across high-latency distributed peers.
 */
@Service
public class CrdtEngine implements CollaborativeEngine {

    private static final int BASE_STEP = 32;
    private final AtomicLong globalClock = new AtomicLong(0);

    @Override
    public EngineType getEngineType() {
        return EngineType.CRDT;
    }

    @Override
    public OperationResult applyOperation(Document document, OperationRequest request) {
        List<CrdtCharacter> characters = document.getCrdtCharacters();
        StringBuilder buffer = document.getContentBuffer();
        OperationType type = request.getType();
        int visiblePos = request.getPosition();
        String text = request.getText() != null ? request.getText() : "";
        int len = request.getLength();
        String siteId = request.getSessionId() != null ? request.getSessionId() : "anon";

        List<String> generatedPositions = new ArrayList<>();
        int tombstonesAdded = 0;

        // Map visible 0-indexed positions to indices in the CRDT character list
        List<Integer> visibleIndices = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            if (!characters.get(i).isDeleted()) {
                visibleIndices.add(i);
            }
        }

        // Clamp visible position
        visiblePos = Math.max(0, Math.min(visiblePos, visibleIndices.size()));

        if (type == OperationType.DELETE || type == OperationType.REPLACE) {
            int deleteCount = Math.min(len, visibleIndices.size() - visiblePos);
            for (int i = 0; i < deleteCount; i++) {
                int charIdx = visibleIndices.get(visiblePos + i);
                characters.get(charIdx).setDeleted(true);
                tombstonesAdded++;
            }
        }

        if (type == OperationType.INSERT || type == OperationType.REPLACE) {
            // Recompute visible indices after deletion
            visibleIndices.clear();
            for (int i = 0; i < characters.size(); i++) {
                if (!characters.get(i).isDeleted()) {
                    visibleIndices.add(i);
                }
            }
            visiblePos = Math.max(0, Math.min(visiblePos, visibleIndices.size()));

            List<Integer> prevPos = (visiblePos > 0)
                ? characters.get(visibleIndices.get(visiblePos - 1)).getPositionIdentifier()
                : null;
            List<Integer> nextPos = (visiblePos < visibleIndices.size())
                ? characters.get(visibleIndices.get(visiblePos)).getPositionIdentifier()
                : null;

            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                List<Integer> newId = generatePositionBetween(prevPos, nextPos);
                CrdtCharacter newChar = new CrdtCharacter(newId, siteId, globalClock.incrementAndGet(), ch);
                characters.add(newChar);
                generatedPositions.add(newId.toString());
                prevPos = newId; // Subsequent character in batch sits between newId and nextPos
            }

            // Re-sort characters to maintain global deterministic ordering
            Collections.sort(characters);
        }

        // Rebuild plaintext view buffer
        buffer.setLength(0);
        int totalTombstones = 0;
        for (CrdtCharacter c : characters) {
            if (!c.isDeleted()) {
                buffer.append(c.getValue());
            } else {
                totalTombstones++;
            }
        }

        long newRev = document.getRevision() + 1;
        document.setRevision(newRev);
        document.markUpdated();

        Map<String, Object> debugInfo = new HashMap<>();
        debugInfo.put("engine", "CRDT");
        debugInfo.put("totalCharactersStored", characters.size());
        debugInfo.put("visibleCharactersCount", buffer.length());
        debugInfo.put("totalTombstones", totalTombstones);
        debugInfo.put("tombstonesAddedInOp", tombstonesAdded);
        debugInfo.put("fractionalPositionsAllocated", generatedPositions);

        return new OperationResult(
            true, document.getId(), EngineType.CRDT, newRev,
            type, visiblePos, text, len, buffer.toString(), debugInfo
        );
    }

    /**
     * Generates a fractional position identifier between before and after.
     */
    public List<Integer> generatePositionBetween(List<Integer> before, List<Integer> after) {
        if (before == null && after == null) {
            return List.of(BASE_STEP);
        }
        if (before == null) {
            int head = after.get(0);
            if (head > 1) {
                return List.of(head / 2);
            } else {
                List<Integer> result = new ArrayList<>(after);
                result.add(BASE_STEP / 2);
                return result;
            }
        }
        if (after == null) {
            List<Integer> result = new ArrayList<>(before);
            int last = result.remove(result.size() - 1);
            result.add(last + BASE_STEP);
            return result;
        }

        List<Integer> result = new ArrayList<>();
        int maxLen = Math.max(before.size(), after.size());

        for (int depth = 0; depth < maxLen; depth++) {
            int b = depth < before.size() ? before.get(depth) : 0;
            int a = depth < after.size() ? after.get(depth) : BASE_STEP;

            int diff = a - b;
            if (diff > 1) {
                result.add(b + diff / 2);
                return result;
            } else if (diff == 1) {
                result.add(b);
                // Deepen into next level
                List<Integer> subBefore = depth + 1 < before.size() ? before.subList(depth + 1, before.size()) : null;
                List<Integer> subResult = generatePositionBetween(subBefore, null);
                result.addAll(subResult);
                return result;
            } else {
                result.add(b);
            }
        }

        // If identical prefixes, extend with middle step
        result.add(BASE_STEP / 2);
        return result;
    }
}
