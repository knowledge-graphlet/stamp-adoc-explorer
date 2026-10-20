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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineIdTest {

    private static final String SHA = "3f2a91c0ffee3f2a91c0ffee3f2a91c0ffee3f2a";

    @Test
    void keyNamesCommitPathAndPosition() {
        LineId id = new LineId(SHA, "topics/anf/medication.adoc", 12);

        assertEquals("line:" + SHA + ":topics/anf/medication.adoc:12", id.key());
        assertEquals(id.key(), id.toString());
    }

    @Test
    void theSamePositionInAnotherFileIsAnotherLine() {
        assertNotEquals(new LineId(SHA, "a.adoc", 1).key(), new LineId(SHA, "b.adoc", 1).key());
        assertNotEquals(new LineId(SHA, "a.adoc", 1), new LineId(SHA, "b.adoc", 1));
    }

    @Test
    void pathsAreNormalizedToForwardSlashes() {
        assertEquals(new LineId(SHA, "topics/doc.adoc", 3), new LineId(SHA, "topics\\doc.adoc", 3));
    }

    @Test
    void parseInvertsKeyEvenWhenThePathHoldsColons() {
        for (LineId id : new LineId[] {new LineId(SHA, "doc.adoc", 1), new LineId(SHA, "topics/odd:name.adoc", 42)}) {
            assertEquals(id, LineId.parse(id.key()));
        }
        assertThrows(IllegalArgumentException.class, () -> LineId.parse("stamp:" + SHA));
        assertThrows(IllegalArgumentException.class, () -> LineId.parse("line:" + SHA + ":7"), "the pre-path form is not a line key");
        assertThrows(IllegalArgumentException.class, () -> LineId.parse("line:" + SHA + ":doc.adoc:x"));
    }
}
