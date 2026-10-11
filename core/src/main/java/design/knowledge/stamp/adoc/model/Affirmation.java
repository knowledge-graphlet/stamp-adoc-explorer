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
 * One {@code Affirms} trailer: a line range of a file at a revision, with a
 * short hash of the affirmed lines so a mistyped or stale range fails
 * loudly when the affirmation is written and again when it is walked.
 *
 * <pre>Affirms: topics/dev/stamp-adoc-attribution.adoc:14-22@3f2a91c#a1b2c3</pre>
 *
 * <p>Line numbers are 1-based and inclusive. The revision is the abbreviated
 * SHA the lines were read from. The hash is the first six hex digits of the
 * SHA-256 of the lines joined with {@code \n}, so a range is affirmed as the
 * reviewer saw it, trailing newline or not.
 *
 * @param file     path of the affirmed file relative to the repository root
 * @param from     first affirmed line, 1-based
 * @param to       last affirmed line, inclusive
 * @param revision abbreviated SHA of the commit the lines were read from
 * @param hash     six hex digits over the affirmed lines
 */
public record Affirmation(Path file, int from, int to, String revision, String hash) {

    /** The trailer token the walker looks for. */
    public static final String TRAILER = "Affirms";

    private static final int HASH_LENGTH = 6;
    private static final int REVISION_LENGTH = 7;
    private static final Pattern VALUE = Pattern.compile("(?<file>.+?):(?<from>\\d+)-(?<to>\\d+)@(?<rev>[0-9a-f]{7,40})#(?<hash>[0-9a-f]{6})");

    /**
     * Validates the record: a non-empty range, a revision, and a hash.
     */
    public Affirmation {
        if (from < 1 || to < from) {
            throw new IllegalArgumentException("affirmed range must be 1-based and non-empty: " + from + "-" + to);
        }
        if (revision == null || revision.length() < REVISION_LENGTH) {
            throw new IllegalArgumentException("an affirmation is anchored to a revision: " + revision);
        }
        if (hash == null || hash.length() != HASH_LENGTH) {
            throw new IllegalArgumentException("hash must be " + HASH_LENGTH + " hex digits: " + hash);
        }
    }

    /**
     * Build an affirmation of lines {@code from}–{@code to} of {@code file} as
     * they read at {@code revision}, hashing those lines of {@code content}.
     *
     * @param file     repository-relative path
     * @param from     first line, 1-based
     * @param to       last line, inclusive
     * @param revision full or abbreviated SHA the content was read from
     * @param content  every line of the file at that revision
     * @return the affirmation, hashed over the range
     * @throws IllegalArgumentException if the range does not fit the content
     */
    public static Affirmation of(Path file, int from, int to, String revision, List<String> content) {
        if (from < 1 || to > content.size() || to < from) {
            throw new IllegalArgumentException(file + " has " + content.size() + " lines; cannot affirm " + from + "-" + to);
        }
        String abbreviated = revision.length() > REVISION_LENGTH ? revision.substring(0, REVISION_LENGTH) : revision;
        return new Affirmation(file, from, to, abbreviated, hashOf(content.subList(from - 1, to)));
    }

    /**
     * Parse the value part of an {@code Affirms} trailer.
     *
     * @param value text after {@code Affirms:}, trimmed or not
     * @return the affirmation
     * @throws IllegalArgumentException if the text is not a trailer value
     */
    public static Affirmation parse(String value) {
        Matcher m = VALUE.matcher(value.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not an Affirms value: " + value);
        }
        return new Affirmation(Path.of(m.group("file")), Integer.parseInt(m.group("from")), Integer.parseInt(m.group("to")),
                m.group("rev"), m.group("hash"));
    }

    /**
     * @param lines the affirmed lines, in order
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
     * @param content every line of the file at {@link #revision()}
     * @return whether the hash still matches the range in {@code content}
     */
    public boolean matches(List<String> content) {
        return to <= content.size() && hash.equals(hashOf(content.subList(from - 1, to)));
    }

    /** @return the trailer value, {@code path:from-to@rev#hash} */
    public String value() {
        return file.toString().replace('\\', '/') + ":" + from + "-" + to + "@" + revision + "#" + hash;
    }

    /** @return the complete trailer line, {@code Affirms: path:from-to@rev#hash} */
    public String trailer() {
        return TRAILER + ": " + value();
    }

    /** @return the number of lines affirmed */
    public int lineCount() {
        return to - from + 1;
    }
}
