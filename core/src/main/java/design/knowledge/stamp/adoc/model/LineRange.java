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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A line range of a file at a revision, with a short hash of the lines so a
 * mistyped or stale range fails loudly when an annotation is written and
 * again when it is walked.
 *
 * <pre>topics/dev/stamp-adoc-attribution.adoc:14-22@3f2a91c#a1b2c3</pre>
 *
 * <p>Line numbers are 1-based and inclusive. The revision is the abbreviated
 * SHA the lines were read from. The hash is the first six hex digits of the
 * SHA-256 of the lines joined with {@code \n}, so a range is annotated as the
 * reviewer saw it, trailing newline or not.
 *
 * <p>A range may name columns too, {@code path:52.19-55.19@rev#hash}: from line 52's column 19 to
 * line 55's column 19, both 1-based and inclusive, the characters a reviewer selected; a sentence
 * annotated in Komet's Reading view is named so (IKE-Network/ike-issues#1313). Its hash is over the
 * selected characters with every run of whitespace collapsed to one space, so a reflow that keeps the
 * words keeps the hash, and its annotation lands on the sentences the range covers whole.
 *
 * @param file       path of the file relative to the repository root
 * @param from       first line, 1-based
 * @param to         last line, inclusive
 * @param revision   abbreviated SHA of the commit the lines were read from
 * @param hash       six hex digits over the lines, or over the selected characters
 * @param fromColumn the first column on {@code from}, 1-based; 0 for the whole line
 * @param toColumn   the last column on {@code to}, inclusive; 0 for the whole line
 */
public record LineRange(Path file, int from, int to, String revision, String hash, int fromColumn, int toColumn)
        implements AnnotationTarget {

    private static final int HASH_LENGTH = 6;
    private static final int REVISION_LENGTH = 7;
    private static final Pattern VALUE = Pattern.compile(
            "(?<file>.+?):(?<from>\\d+)(?:\\.(?<fromColumn>\\d+))?-(?<to>\\d+)(?:\\.(?<toColumn>\\d+))?@(?<rev>[0-9a-f]{7,40})#(?<hash>[0-9a-f]{6})");

    /**
     * Validates the record: a non-empty range, a revision, and a hash.
     */
    public LineRange {
        if (from < 1 || to < from) {
            throw new IllegalArgumentException("a line range must be 1-based and non-empty: " + from + "-" + to);
        }
        if (revision == null || revision.length() < REVISION_LENGTH) {
            throw new IllegalArgumentException("a line range is anchored to a revision: " + revision);
        }
        if (hash == null || hash.length() != HASH_LENGTH) {
            throw new IllegalArgumentException("hash must be " + HASH_LENGTH + " hex digits: " + hash);
        }
        if ((fromColumn == 0) != (toColumn == 0) || fromColumn < 0 || toColumn < 0 || (from == to && toColumn > 0 && toColumn < fromColumn)) {
            throw new IllegalArgumentException("a column range names both columns, in order: " + fromColumn + "-" + toColumn);
        }
    }

    /**
     * A range of whole lines.
     *
     * @param file     path of the file relative to the repository root
     * @param from     first line, 1-based
     * @param to       last line, inclusive
     * @param revision abbreviated SHA of the commit the lines were read from
     * @param hash     six hex digits over the lines
     */
    public LineRange(Path file, int from, int to, String revision, String hash) {
        this(file, from, to, revision, hash, 0, 0);
    }

    /**
     * The characters from {@code from}.{@code fromColumn} to {@code to}.{@code toColumn} of
     * {@code file} as they read at {@code revision}, hashing them whitespace normalized.
     *
     * @param file       repository-relative path
     * @param from       first line, 1-based
     * @param fromColumn its first column, 1-based
     * @param to         last line, inclusive
     * @param toColumn   its last column, inclusive
     * @param revision   full or abbreviated SHA the content was read from
     * @param content    every line of the file at that revision
     * @return the range, hashed
     * @throws IllegalArgumentException if the range does not fit the content
     */
    public static LineRange of(Path file, int from, int fromColumn, int to, int toColumn, String revision, List<String> content) {
        if (from < 1 || to > content.size() || to < from || fromColumn < 1 || toColumn < 1
                || fromColumn > content.get(from - 1).length() || toColumn > content.get(to - 1).length()) {
            throw new IllegalArgumentException(file + " has no characters " + from + "." + fromColumn + "-" + to + "." + toColumn);
        }
        String abbreviated = revision.length() > REVISION_LENGTH ? revision.substring(0, REVISION_LENGTH) : revision;
        LineRange shape = new LineRange(file, from, to, abbreviated, "000000", fromColumn, toColumn);
        return new LineRange(file, from, to, abbreviated, hashOfText(shape.selected(content)), fromColumn, toColumn);
    }

    /** @return whether the range names columns: a selection of characters rather than of whole lines */
    public boolean hasColumns() {
        return fromColumn > 0;
    }

    /**
     * The characters the range names, lines joined by {@code \n}.
     *
     * @param content every line of the file at {@link #revision()}
     * @return the selected text; whole lines for a range without columns
     */
    public String selected(List<String> content) {
        List<String> lines = content.subList(from - 1, to);
        if (!hasColumns()) {
            return String.join("\n", lines);
        }
        if (from == to) {
            return lines.getFirst().substring(fromColumn - 1, toColumn);
        }
        StringBuilder text = new StringBuilder(lines.getFirst().substring(fromColumn - 1));
        for (int i = 1; i < lines.size() - 1; i++) {
            text.append('\n').append(lines.get(i));
        }
        return text.append('\n').append(lines.getLast(), 0, toColumn).toString();
    }

    /**
     * The range of lines {@code from}–{@code to} of {@code file} as they read at
     * {@code revision}, hashing those lines of {@code content}.
     *
     * @param file     repository-relative path
     * @param from     first line, 1-based
     * @param to       last line, inclusive
     * @param revision full or abbreviated SHA the content was read from
     * @param content  every line of the file at that revision
     * @return the range, hashed
     * @throws IllegalArgumentException if the range does not fit the content
     */
    public static LineRange of(Path file, int from, int to, String revision, List<String> content) {
        if (from < 1 || to > content.size() || to < from) {
            throw new IllegalArgumentException(file + " has " + content.size() + " lines; cannot annotate " + from + "-" + to);
        }
        String abbreviated = revision.length() > REVISION_LENGTH ? revision.substring(0, REVISION_LENGTH) : revision;
        return new LineRange(file, from, to, abbreviated, hashOf(content.subList(from - 1, to)));
    }

    /**
     * @param value {@code path:from-to@rev#hash}, trimmed or not
     * @return the range
     * @throws IllegalArgumentException if the text is not a line range
     */
    public static LineRange parse(String value) {
        Matcher m = VALUE.matcher(value.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a line range: " + value);
        }
        return new LineRange(Path.of(m.group("file")), Integer.parseInt(m.group("from")), Integer.parseInt(m.group("to")),
                m.group("rev"), m.group("hash"), m.group("fromColumn") == null ? 0 : Integer.parseInt(m.group("fromColumn")),
                m.group("toColumn") == null ? 0 : Integer.parseInt(m.group("toColumn")));
    }

    /**
     * @param lines the lines, in order
     * @return the first six hex digits of SHA-256 over the lines joined with {@code \n}
     */
    public static String hashOf(List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param text the selected characters
     * @return the first six hex digits of SHA-256 over the text with each run of whitespace collapsed to one space
     */
    public static String hashOfText(String text) {
        return hashOf(List.of(text.strip().replaceAll("\\s+", " ")));
    }

    /**
     * @param content every line of the file at {@link #revision()}
     * @return whether the hash still matches the range in {@code content}
     */
    public boolean matches(List<String> content) {
        if (to > content.size()) {
            return false;
        }
        if (!hasColumns()) {
            return hash.equals(hashOf(content.subList(from - 1, to)));
        }
        if (fromColumn > content.get(from - 1).length() || toColumn > content.get(to - 1).length()) {
            return false;
        }
        return hash.equals(hashOfText(selected(content)));
    }

    /** @return {@code path:from-to@rev#hash}, or {@code path:from.column-to.column@rev#hash} */
    @Override
    public String value() {
        return hasColumns()
                ? gitPath() + ":" + from + "." + fromColumn + "-" + to + "." + toColumn + "@" + revision + "#" + hash
                : gitPath() + ":" + from + "-" + to + "@" + revision + "#" + hash;
    }

    /** @return the file path with forward slashes, as git names it */
    public String gitPath() {
        return file.toString().replace('\\', '/');
    }

    /** @return the number of lines in the range */
    public int lineCount() {
        return to - from + 1;
    }
}
