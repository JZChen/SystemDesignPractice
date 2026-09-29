package com.googledocs.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable character atom in a fractional sequence CRDT (LSeq-style).
 */
public class CrdtCharacter implements Comparable<CrdtCharacter> {
    private final List<Integer> positionIdentifier;
    private final String siteId;
    private final long clock;
    private final char value;
    private boolean deleted;

    public CrdtCharacter(List<Integer> positionIdentifier, String siteId, long clock, char value) {
        this.positionIdentifier = Collections.unmodifiableList(new ArrayList<>(positionIdentifier));
        this.siteId = siteId != null ? siteId : "";
        this.clock = clock;
        this.value = value;
        this.deleted = false;
    }

    public List<Integer> getPositionIdentifier() {
        return positionIdentifier;
    }

    public String getSiteId() {
        return siteId;
    }

    public long getClock() {
        return clock;
    }

    public char getValue() {
        return value;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public int compareTo(CrdtCharacter other) {
        if (other == null) return 1;
        List<Integer> p1 = this.positionIdentifier;
        List<Integer> p2 = other.positionIdentifier;
        int minLen = Math.min(p1.size(), p2.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = Integer.compare(p1.get(i), p2.get(i));
            if (cmp != 0) return cmp;
        }
        if (p1.size() != p2.size()) {
            return Integer.compare(p1.size(), p2.size());
        }
        int siteCmp = this.siteId.compareTo(other.siteId);
        if (siteCmp != 0) return siteCmp;
        return Long.compare(this.clock, other.clock);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrdtCharacter that)) return false;
        return clock == that.clock &&
               value == that.value &&
               Objects.equals(positionIdentifier, that.positionIdentifier) &&
               Objects.equals(siteId, that.siteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(positionIdentifier, siteId, clock, value);
    }
}
