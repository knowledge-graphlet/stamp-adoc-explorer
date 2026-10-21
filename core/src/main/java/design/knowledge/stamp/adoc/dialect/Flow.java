/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One run of a block's text, the unit sentences are found in (IKE-Network/ike-issues#1313): a
 * paragraph's content lines, a list item's text, an admonition paragraph, a table cell, a title
 * or a description term. Its text is the content part of each physical line it covers, joined by
 * {@code \n}, so every character knows the line and column it came from. The word is borrowed
 * from page layout, where a story flows through frames; here a flow is one block's text.
 *
 * @param block the block whose text it is
 * @param kind  what kind of text
 * @param text  the parts joined by {@code \n}
 * @param parts the physical line parts, in order
 */
public record Flow(Block block, Kind kind, String text, List<Part> parts) {

    /** What a flow is the text of. */
    public enum Kind {
        /** A paragraph's, list item's, admonition's or bibliography entry's content. */
        TEXT,
        /** A section heading or a block title. */
        TITLE,
        /** A description list item's term. */
        TERM,
        /** A table cell. */
        CELL
    }

    /**
     * The part of one physical line a flow holds.
     *
     * @param line   the 1-based line number
     * @param from   the first column it holds, 0-based
     * @param to     one past the last column it holds
     * @param offset where it starts in the flow's text
     */
    public record Part(int line, int from, int to, int offset) { }

    public Flow {
        Objects.requireNonNull(kind, "kind");
        parts = List.copyOf(parts);
    }

    /** @return the first line the flow holds a part of */
    public int firstLine() {
        return parts.getFirst().line();
    }

    /**
     * The part a character of the text came from.
     *
     * @param offset an offset into {@link #text()}
     * @return the index of its part
     */
    public int partAt(int offset) {
        int index = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).offset() <= offset) {
                index = i;
            }
        }
        return index;
    }

    /**
     * The lines characters {@code [start, end)} of the text came from.
     *
     * @param start the first offset
     * @param end   one past the last
     * @return their 1-based line numbers, in order
     */
    public List<Integer> lines(int start, int end) {
        int first = partAt(start);
        int last = partAt(Math.max(start, end - 1));
        List<Integer> lines = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            lines.add(parts.get(i).line());
        }
        return lines;
    }

    /**
     * The column of a line that a character of the text came from.
     *
     * @param offset an offset into {@link #text()}
     * @return the 0-based column on its line
     */
    public int columnAt(int offset) {
        Part part = parts.get(partAt(offset));
        return part.from() + offset - part.offset();
    }

    /** Builds a flow part by part. */
    static final class Builder {
        private final Block block;
        private final Kind kind;
        private final StringBuilder text = new StringBuilder();
        private final List<Part> parts = new ArrayList<>();

        Builder(Block block, Kind kind) {
            this.block = block;
            this.kind = kind;
        }

        Builder add(int line, String lineText, int from, int to) {
            if (!parts.isEmpty()) {
                text.append('\n');
            }
            int start = Math.min(from, to);
            parts.add(new Part(line, start, to, text.length()));
            text.append(lineText, start, to);
            return this;
        }

        boolean isEmpty() {
            return parts.isEmpty();
        }

        Flow build() {
            return new Flow(block, kind, text.toString(), parts);
        }
    }
}
