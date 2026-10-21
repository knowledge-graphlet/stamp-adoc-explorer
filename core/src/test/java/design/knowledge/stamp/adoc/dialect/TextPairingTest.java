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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Decision 2's pairing, on the cases the five reference histories showed. */
class TextPairingTest {

    @Test
    void equalTextsKeepTheirPlace() {
        assertArrayEquals(new int[] {0, 1, -1, 2}, TextPairing.pair(List.of("a b c.", "d e f.", "g h i."),
                List.of("a b c.", "d e f.", "new one here.", "g h i.")));
    }

    @Test
    void aRevisionThatExtendsKeepsItsIdentity() {
        String before = "Exact TTL value (10 minutes proposed) and renewal cadence.";
        String after = "Exact TTL value (10 minutes, in force since 2026-08-12) and renewal cadence — the hook renews at half-life, "
                + "so the record is not rewritten per keystroke.";
        assertArrayEquals(new int[] {0}, TextPairing.pair(List.of(before), List.of(after)));
    }

    @Test
    void unrelatedTextIsIntroducedNotPaired() {
        int[] map = TextPairing.pair(List.of("Ownership transfers at this instant.", "Lease directory name (leases/ proposed)."),
                List.of("Ownership transfers at this instant, and the writer asks Syncthing to scan.", "A bus with an hour of latency is not a bus.",
                        "Whether confirmed acquisition should be the default."));
        assertArrayEquals(new int[] {0, -1, -1}, map);
    }

    @Test
    void aLoneLeftoverBetweenPairsPairsByPosition() {
        int[] map = TextPairing.pair(List.of("Verb", "Acquisition behavior", "Meaning"), List.of("Verb", "Take behavior", "Meaning"));
        assertArrayEquals(new int[] {0, 1, 2}, map, "a short cell whose words changed is the same cell");
    }

    @Test
    void aMovedSentenceIsTheSameSentence() {
        int[] map = TextPairing.pair(List.of("First one here.", "Second one here.", "Third one here."),
                List.of("Second one here.", "Third one here.", "First one here."));
        assertArrayEquals(new int[] {1, 2, 0}, map);
    }

    @Test
    void compatibilityConfinesRevisions() {
        int[] map = TextPairing.pair(List.of("STRONG x y z"), List.of("EMPHASIS x y z"), (i, j) -> false);
        assertArrayEquals(new int[] {-1}, map);
    }

    @Test
    void overlapIsOverTheShorter() {
        assertEquals(1.0, TextPairing.overlap("Implementation is tracked", "Implementation is tracked, and closed"));
        assertEquals(0.0, TextPairing.overlap("a b", "c d"));
    }
}
