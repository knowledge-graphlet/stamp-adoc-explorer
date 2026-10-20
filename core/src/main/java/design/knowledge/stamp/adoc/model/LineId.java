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
 * The stable identity of one line: the commit that introduced it, the
 * repository-relative path the file had at that commit, and the line's
 * position in the file at that commit. The identity rides along as the line
 * moves through later edits and renames and is replaced when the line itself
 * is edited, so a line entity in a graph has one introducer and, through
 * ignored formatting commits, possibly several text versions.
 *
 * <p>The path is part of the identity because one commit may introduce line
 * {@code n} of several files at once: without it, those lines were one
 * identity and the last file written into a store won (the cross-file
 * contamination seen when an assembly of 35 topics with a shared two-commit
 * history was materialized, 2026-10-19).
 *
 * @param introducingSha full SHA of the commit that introduced the line
 * @param path           the file's path at the introducing commit, relative to the
 *                       repository root, with forward slashes
 * @param position       1-based line number at that commit
 */
public record LineId(String introducingSha, String path, int position) {

    private static final String PREFIX = "line:";

    public LineId {
        Objects.requireNonNull(introducingSha, "introducingSha");
        path = Objects.requireNonNull(path, "path").replace('\\', '/');
    }

    /** @return the frozen key form, {@code line:<sha>:<path>:<position>}, for identity derivation */
    public String key() {
        return PREFIX + introducingSha + ":" + path + ":" + position;
    }

    /**
     * The inverse of {@link #key()}. The SHA holds no colon and the position is
     * the last field, so a path containing colons parses back unchanged.
     *
     * @param key a key as {@link #key()} renders it
     * @return the identity the key names
     * @throws IllegalArgumentException if the key is not a line key
     */
    public static LineId parse(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a line key: " + key);
        }
        int shaEnd = key.indexOf(':', PREFIX.length());
        int positionStart = key.lastIndexOf(':');
        if (shaEnd < 0 || positionStart <= shaEnd) {
            throw new IllegalArgumentException("not a line key: " + key);
        }
        try {
            return new LineId(key.substring(PREFIX.length(), shaEnd), key.substring(shaEnd + 1, positionStart),
                    Integer.parseInt(key.substring(positionStart + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a line key: " + key, e);
        }
    }

    @Override
    public String toString() {
        return key();
    }
}
