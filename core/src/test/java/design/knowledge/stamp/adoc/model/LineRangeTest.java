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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line ranges, and the column ranges a sentence selection is named by (IKE-Network/ike-issues#1313). */
class LineRangeTest {

    private static final List<String> CONTENT = List.of("The lease is a loan. A machine takes it, renews", "it while working.");

    @Test
    void aColumnRangeRoundTripsAndHashesTheSelectedWords() {
        LineRange range = LineRange.of(Path.of("doc.adoc"), 1, 22, 2, 17, "abcdef0123", CONTENT);
        assertEquals("A machine takes it, renews\nit while working.", range.selected(CONTENT));
        assertEquals("doc.adoc:1.22-2.17@abcdef0#" + range.hash(), range.value());
        assertEquals(range, LineRange.parse(range.value()));
        assertTrue(range.matches(CONTENT));
        assertTrue(range.matches(List.of("The lease is a loan. A machine takes it,", "renews it while working.").subList(0, 2)) == false,
                "columns are positions in the file the annotator read");
        assertEquals(LineRange.hashOfText("A machine takes it, renews it while working."), range.hash(), "whitespace collapsed");
    }

    @Test
    void aLineRangeIsUnchanged() {
        LineRange range = LineRange.of(Path.of("doc.adoc"), 1, 2, "abcdef0123", CONTENT);
        assertFalse(range.hasColumns());
        assertEquals(range, LineRange.parse(range.value()));
        assertEquals("doc.adoc:1-2@abcdef0#" + range.hash(), range.value());
    }

    @Test
    void aColumnRangeNamesBothColumns() {
        assertThrows(IllegalArgumentException.class, () -> new LineRange(Path.of("a"), 1, 1, "abcdef0", "000000", 3, 0));
        assertThrows(IllegalArgumentException.class, () -> LineRange.of(Path.of("a"), 1, 60, 1, 61, "abcdef0", CONTENT));
    }
}
