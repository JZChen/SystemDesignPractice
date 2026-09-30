package com.googledocs.ot;

/**
 * Thrown when an operation is malformed or does not fit the document it is applied to
 * (for example, a base-length mismatch). Callers treat this as "reject / resync".
 */
public class OtException extends RuntimeException {
    public OtException(String message) {
        super(message);
    }
}
