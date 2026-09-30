package com.googledocs.ot;

/**
 * Converts an editor state change (previous text -> current text) into a {@link TextOperation}.
 *
 * <p>Uses a common-prefix / common-suffix scan, which yields a single contiguous span change.
 * This is exact for normal typing, deleting, pasting and selection-replace; if several disjoint
 * edits land within one coalescing window they are merged into one covering span (still correct,
 * just a larger replace).
 */
public final class OperationDiff {

    private OperationDiff() {
    }

    public static TextOperation diff(String oldText, String newText) {
        int oldLen = oldText.length();
        int newLen = newText.length();

        int prefix = 0;
        int maxPrefix = Math.min(oldLen, newLen);
        while (prefix < maxPrefix && oldText.charAt(prefix) == newText.charAt(prefix)) {
            prefix++;
        }
        // Never split a UTF-16 surrogate pair (emoji etc.).
        if (prefix > 0 && prefix < maxPrefix && Character.isHighSurrogate(oldText.charAt(prefix - 1))) {
            prefix--;
        }

        int suffix = 0;
        int maxSuffix = Math.min(oldLen, newLen) - prefix;
        while (suffix < maxSuffix
                && oldText.charAt(oldLen - 1 - suffix) == newText.charAt(newLen - 1 - suffix)) {
            suffix++;
        }
        if (suffix > 0 && suffix < maxSuffix && Character.isLowSurrogate(oldText.charAt(oldLen - suffix))) {
            suffix--;
        }

        int deleted = oldLen - prefix - suffix;
        String inserted = newText.substring(prefix, newLen - suffix);

        return new TextOperation()
                .retain(prefix)
                .insert(inserted)
                .delete(deleted)
                .retain(suffix);
    }
}
