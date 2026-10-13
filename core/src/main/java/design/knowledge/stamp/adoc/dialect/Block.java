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
import java.util.Collections;
import java.util.List;

/**
 * One structural element of a parsed document: the document itself, a section, or a block.
 * Every block owns a contiguous range of lines, {@link #firstLine()} to {@link #lastLine()}
 * inclusive and 1-based like the STAMP line positions; a leaf block's {@link #lines()} are the
 * lines it owns directly (delimiters, attribute lines and titles included, so the structure
 * accounts for every line), a container's {@link #children()} are the blocks it contains in
 * order. A section or the document owns its heading line through {@link #headingLine()} and
 * its metadata lines (anchor, title, attribute lines) through {@link #lines()}.
 */
public final class Block {

    /** The kinds a parse distinguishes, each a concept of the AsciiDoc document model. */
    public enum Kind {
        DOCUMENT, SECTION, PARAGRAPH, LISTING, LITERAL, EXAMPLE, QUOTE, SIDEBAR, OPEN, ADMONITION,
        TABLE, TABLE_ROW, TABLE_CELL, LIST, LIST_ITEM, DIAGRAM, IMAGE, BIBLIOGRAPHY_ENTRY, OPAQUE
    }

    private final Kind kind;
    private final int level;
    private String title;
    private String parameters;
    private String anchor;
    private int headingLine;
    private int firstLine;
    private int lastLine;
    private final List<Integer> lines = new ArrayList<>();
    private final List<Block> children = new ArrayList<>();

    Block(Kind kind, int level, int firstLine) {
        this.kind = kind;
        this.level = level;
        this.firstLine = firstLine;
        this.lastLine = firstLine;
        this.title = "";
        this.parameters = "";
        this.anchor = "";
    }

    /** @return the element kind */
    public Kind kind() {
        return kind;
    }

    /** @return a section's heading level (0 for the document title), a list's nesting depth, else 0 */
    public int level() {
        return level;
    }

    /** @return the heading text, the {@code .Title}, or a description item's term; empty when none */
    public String title() {
        return title;
    }

    /** @return the block attribute line as written ({@code [source,yaml]}), or empty */
    public String parameters() {
        return parameters;
    }

    /** @return the {@code [[anchor]]} id attached to the block, or empty */
    public String anchor() {
        return anchor;
    }

    /** @return the 1-based line of a section's heading or the document title, else 0 */
    public int headingLine() {
        return headingLine;
    }

    /** @return the first 1-based line this block spans */
    public int firstLine() {
        return firstLine;
    }

    /** @return the last 1-based line this block spans */
    public int lastLine() {
        return lastLine;
    }

    /** @return the 1-based lines this block owns directly, in order */
    public List<Integer> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** @return the blocks this one contains, in order */
    public List<Block> children() {
        return Collections.unmodifiableList(children);
    }

    /** @return whether the block contains other blocks */
    public boolean isContainer() {
        return !children.isEmpty();
    }

    /** @return this block and every descendant in document order */
    public List<Block> flatten() {
        List<Block> all = new ArrayList<>();
        collect(all);
        return all;
    }

    private void collect(List<Block> into) {
        into.add(this);
        for (Block child : children) {
            child.collect(into);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(kind.name().toLowerCase());
        if (level > 0) {
            sb.append('/').append(level);
        }
        sb.append(' ').append(firstLine).append('-').append(lastLine);
        if (!anchor.isEmpty()) {
            sb.append(" [[").append(anchor).append("]]");
        }
        if (!title.isEmpty()) {
            sb.append(" \"").append(title).append('"');
        }
        if (!parameters.isEmpty()) {
            sb.append(' ').append(parameters);
        }
        return sb.toString();
    }

    // ── mutation, package-private: the parser builds blocks, readers only read them ──

    void title(String title) {
        this.title = title == null ? "" : title;
    }

    void parameters(String parameters) {
        this.parameters = parameters == null ? "" : parameters;
    }

    void anchor(String anchor) {
        this.anchor = anchor == null ? "" : anchor;
    }

    void headingLine(int line) {
        this.headingLine = line;
        own(line);
    }

    void own(int line) {
        lines.add(line);
        extend(line);
    }

    void add(Block child) {
        children.add(child);
        extend(child.firstLine);
        extend(child.lastLine);
    }

    void extend(int line) {
        if (line < firstLine) {
            firstLine = line;
        }
        if (line > lastLine) {
            lastLine = line;
        }
    }
}
