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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationTest {

    private static final List<String> CONTENT = List.of("= Title", "", "one claim,", "another claim,", "and a third.");

    @Test
    void kindsAreTheStarterSetConcepts() {
        // The UUID the starter set minted for "Affirmation (IkeFoundation)" before annotation existed (IkeTerms.AFFIRMATION).
        assertEquals(UUID.fromString("6111dc03-6974-5a07-9b98-10cb7921e29a"), AnnotationKind.AFFIRMATION.uuid());
        assertEquals(AnnotationKind.REFUTATION, AnnotationKind.of(AnnotationKind.REFUTATION.uuid()).orElseThrow());
        assertTrue(AnnotationKind.REFUTATION.judgment());
        assertFalse(AnnotationKind.COMMENT.judgment());
    }

    @Test
    void aRetractionNamesAnEarlierAnnotationCommit() {
        Annotation a = Annotation.of(AnnotationKind.RETRACTION, new AnnotationTarget.Commit("3f2a91c0ffee0ffee0ffee0ffee0ffee0ffee0ff"));

        assertEquals("Annotation: " + AnnotationKind.RETRACTION.ref() + " @3f2a91c0ffee0ffee0ffee0ffee0ffee0ffee0ff", a.trailer());
        Annotation parsed = Annotation.parse(a.value());
        assertEquals(a, parsed);
        assertEquals(AnnotationKind.RETRACTION, parsed.knownKind().orElseThrow());
        assertFalse(AnnotationKind.RETRACTION.judgment(), "a retraction is not a stance; it withdraws one");
        assertEquals("3f2a91c", parsed.retractedCommit().orElseThrow().shortRevision());
        assertTrue(parsed.lineRange().isEmpty());
        assertInstanceOf(AnnotationTarget.Commit.class, AnnotationTarget.parse(" @3f2a91c "));
        assertThrows(IllegalArgumentException.class, () -> AnnotationTarget.parse("@3f2a9"), "too short to name a commit");
        assertThrows(IllegalArgumentException.class, () -> AnnotationTarget.parse("@3F2A91C"), "lowercase, as git prints it");
    }

    @Test
    void lineRangeCarriesPathRangeRevisionAndHash() {
        LineRange r = LineRange.of(Path.of("topics/dev/x.adoc"), 3, 5, "3f2a91c0ffee0ffee0ffee", CONTENT);

        assertEquals("topics/dev/x.adoc:3-5@3f2a91c#" + LineRange.hashOf(CONTENT.subList(2, 5)), r.value());
        assertEquals(3, r.lineCount());
        assertTrue(r.matches(CONTENT));
        assertFalse(r.matches(List.of("= Title", "", "one claim,", "an edited claim,", "and a third.")),
                "an edited line inside the range breaks the hash");
        assertTrue(r.matches(List.of("= Other title", "", "one claim,", "another claim,", "and a third.")),
                "lines outside the range do not take part");
    }

    @Test
    void trailerRoundTripsAKonceptKindAndALineRange() {
        Annotation a = Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("topics/dev/x.adoc"), 3, 4, "3f2a91c", CONTENT));

        assertEquals("Annotation: k:uuid=6111dc03-6974-5a07-9b98-10cb7921e29a[Affirmation] topics/dev/x.adoc:3-4@3f2a91c#"
                + LineRange.hashOf(CONTENT.subList(2, 4)), a.trailer());
        Annotation parsed = Annotation.parse(a.value());
        assertEquals(a, parsed);
        assertEquals(AnnotationKind.AFFIRMATION, parsed.knownKind().orElseThrow());
        assertNull(parsed.by());
    }

    @Test
    void exportedAnnotationsCarryAuthorTimeAndIdentity() {
        UUID component = UUID.fromString("0f5a7a8c-1d1b-5f43-9a0f-3c2d2b4e1a11");
        UUID stamp = UUID.fromString("7c9d0e44-63a4-5d2e-8f3a-7b8e2a1c0d55");
        KonceptRef kerry = new KonceptRef(UUID.fromString("a7b1c2d3-e4f5-5a6b-8c7d-9e0f1a2b3c4d"), "Kerry Example");
        Annotation a = new Annotation(AnnotationKind.REFUTATION.ref(),
                new AnnotationTarget.ComponentVersion(new KonceptRef(component, "Chest pain"), stamp),
                kerry, Instant.parse("2026-10-14T18:00:00Z"), UUID.fromString("11111111-2222-5333-8444-555555555555"));

        Annotation parsed = Annotation.parse(a.value());

        assertEquals(a, parsed);
        assertEquals("Kerry Example", parsed.by().label(), "a label with spaces survives");
        assertInstanceOf(AnnotationTarget.ComponentVersion.class, parsed.target());
        assertEquals(stamp, ((AnnotationTarget.ComponentVersion) parsed.target()).stamp());
        assertInstanceOf(AnnotationTarget.ChangeSet.class,
                Annotation.parse(AnnotationKind.COMMENT.ref() + " k:uuid=" + stamp + "[Commit 3f2a91c]").target());
    }

    @Test
    void labelsArePresentationAndNameKeysAreRefused() {
        assertEquals(AnnotationKind.AFFIRMATION.ref(), KonceptRef.parse("k:uuid=" + AnnotationKind.AFFIRMATION.uuid() + "[Affirmed!]"));
        assertThrows(IllegalArgumentException.class, () -> KonceptRef.parse("k:Affirmation[]"));
        assertThrows(IllegalArgumentException.class, () -> Annotation.parse("k:Affirmation[] x.adoc:1-2@3f2a91c#a1b2c3"));
    }

    @Test
    void rangesMustFitTheContent() {
        assertThrows(IllegalArgumentException.class, () -> LineRange.of(Path.of("x"), 0, 1, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> LineRange.of(Path.of("x"), 4, 6, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> LineRange.of(Path.of("x"), 3, 2, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> LineRange.parse("x.adoc:3-5#a1b2c3"));
    }
}
