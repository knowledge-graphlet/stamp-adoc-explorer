/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.model;

import java.util.Objects;

/**
 * The stable identity of a unit of text below the line (IKE-Network/ike-issues#1313; decision 1 of
 * {@code design-2026-10-21-stored-text-segments}): a sentence, or an inline element. It has the
 * shape of a {@link LineId}: the commit that introduced the unit, the file's path at that commit,
 * and the unit's 1-based position at that commit among the document's sentences, or among its
 * inline elements in preorder. It names no line, so a reflow neither introduces a sentence nor
 * changes its identity, and a revision keeps it, since the walker pairs a revised unit with its
 * predecessor.
 *
 * @param kind           a sentence or an inline element
 * @param introducingSha full SHA of the commit that introduced the unit
 * @param path           the file's path at the introducing commit, relative to the repository root, with forward slashes
 * @param ordinal        1-based position among the units of its kind at that commit
 */
public record SegmentId(Kind kind, String introducingSha, String path, int ordinal) {

    /** What a segment identity names. */
    public enum Kind {
        /** A sentence: {@code segment:<sha>:<path>:<ordinal>}. */
        SENTENCE("segment:"),
        /** An inline element: {@code inline:<sha>:<path>:<ordinal>}. */
        INLINE("inline:");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        /** @return the key prefix */
        public String prefix() {
            return prefix;
        }
    }

    public SegmentId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(introducingSha, "introducingSha");
        path = Objects.requireNonNull(path, "path").replace('\\', '/');
    }

    /** @return the frozen key form, {@code segment:<sha>:<path>:<ordinal>} or {@code inline:…}, for identity derivation */
    public String key() {
        return kind.prefix() + introducingSha + ":" + path + ":" + ordinal;
    }

    /**
     * The inverse of {@link #key()}. The SHA holds no colon and the ordinal is the last field, so a
     * path containing colons parses back unchanged.
     *
     * @param key a key as {@link #key()} renders it
     * @return the identity the key names
     * @throws IllegalArgumentException if the key is not a segment key
     */
    public static SegmentId parse(String key) {
        for (Kind kind : Kind.values()) {
            if (key != null && key.startsWith(kind.prefix())) {
                int shaEnd = key.indexOf(':', kind.prefix().length());
                int ordinalStart = key.lastIndexOf(':');
                if (shaEnd < 0 || ordinalStart <= shaEnd) {
                    break;
                }
                try {
                    return new SegmentId(kind, key.substring(kind.prefix().length(), shaEnd), key.substring(shaEnd + 1, ordinalStart),
                            Integer.parseInt(key.substring(ordinalStart + 1)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("not a segment key: " + key, e);
                }
            }
        }
        throw new IllegalArgumentException("not a segment key: " + key);
    }

    @Override
    public String toString() {
        return key();
    }
}
