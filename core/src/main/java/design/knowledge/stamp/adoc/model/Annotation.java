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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code Annotation:} trailer: an affirmation, a refutation, or a comment
 * about a target, recorded without touching it (ike-lab-documents topic
 * {@code dev-annotation-trailer}, IKE-Network/ike-issues#1300).
 *
 * <pre>
 * Annotation: &lt;kind&gt; &lt;target&gt; [by &lt;author&gt;] [at &lt;instant&gt;] [id &lt;uuid&gt;]
 * Annotation: k:uuid=6111dc03-…[Affirmation] topics/dev/x.adoc:14-22@3f2a91c#a1b2c3
 * </pre>
 *
 * <p>The kind and the author are Koncept references by PublicId. {@code by},
 * {@code at} and {@code id} are absent for an annotation made in git, whose
 * annotator and time are the commit's author and author time; they are
 * written when an annotation made in the graph is exported, because then the
 * commit's author or signer is not the annotator. The commit body is the
 * annotation's text, shared by every trailer of the commit.
 *
 * @param kind   the annotation-kind concept
 * @param target what is annotated
 * @param by     the annotator's author concept, or null for the commit author
 * @param at     when the annotation was made, or null for the commit's author time
 * @param id     the annotation's own PublicId, or null when it was made in git
 */
public record Annotation(KonceptRef kind, AnnotationTarget target, KonceptRef by, Instant at, UUID id) {

    /** The trailer token the walker looks for. */
    public static final String TRAILER = "Annotation";

    private static final Pattern VALUE = Pattern.compile(
            "(?<kind>" + KonceptRef.REGEX + ")\\s+(?<target>.+?)"
                    + "(?:\\s+by\\s+(?<by>" + KonceptRef.REGEX + "))?"
                    + "(?:\\s+at\\s+(?<at>\\S+))?"
                    + "(?:\\s+id\\s+(?<id>[0-9a-fA-F-]{36}))?");

    /**
     * Validates the record.
     */
    public Annotation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
    }

    /**
     * An annotation made in git: the commit supplies annotator and time.
     *
     * @param kind   a known kind
     * @param target what is annotated
     * @return the annotation
     */
    public static Annotation of(AnnotationKind kind, AnnotationTarget target) {
        return new Annotation(kind.ref(), target, null, null, null);
    }

    /**
     * Parse the value part of an {@code Annotation} trailer.
     *
     * @param value text after {@code Annotation:}, trimmed or not
     * @return the annotation
     * @throws IllegalArgumentException if the text is not a trailer value
     */
    public static Annotation parse(String value) {
        Matcher m = VALUE.matcher(value.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not an Annotation value: " + value);
        }
        Instant at = null;
        if (m.group("at") != null) {
            try {
                at = Instant.parse(m.group("at"));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("not an ISO instant after 'at': " + m.group("at"), e);
            }
        }
        return new Annotation(KonceptRef.parse(m.group("kind")), AnnotationTarget.parse(m.group("target")),
                m.group("by") == null ? null : KonceptRef.parse(m.group("by")), at,
                m.group("id") == null ? null : UUID.fromString(m.group("id")));
    }

    /** @return the known kind, empty when the kind concept is not one this engine knows */
    public Optional<AnnotationKind> knownKind() {
        return AnnotationKind.of(kind.uuid());
    }

    /** @return the line range, when the target is one */
    public Optional<LineRange> lineRange() {
        return target instanceof LineRange r ? Optional.of(r) : Optional.empty();
    }

    /** @return the trailer value, {@code <kind> <target> [by …] [at …] [id …]} */
    public String value() {
        StringBuilder s = new StringBuilder().append(kind).append(' ').append(target.value());
        if (by != null) {
            s.append(" by ").append(by);
        }
        if (at != null) {
            s.append(" at ").append(at);
        }
        if (id != null) {
            s.append(" id ").append(id);
        }
        return s.toString();
    }

    /** @return the complete trailer line, {@code Annotation: <value>} */
    public String trailer() {
        return TRAILER + ": " + value();
    }
}
