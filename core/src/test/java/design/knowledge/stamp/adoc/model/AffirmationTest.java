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

class AffirmationTest {

    private static final List<String> CONTENT = List.of("= Title", "", "one claim,", "another claim,", "and a third.");

    @Test
    void trailerCarriesPathRangeRevisionAndHash() {
        Affirmation a = Affirmation.of(Path.of("topics/dev/x.adoc"), 3, 5, "3f2a91c0ffee0ffee0ffee", CONTENT);

        assertEquals("topics/dev/x.adoc:3-5@3f2a91c#" + Affirmation.hashOf(CONTENT.subList(2, 5)), a.value());
        assertEquals("Affirms: " + a.value(), a.trailer());
        assertEquals(3, a.lineCount());
    }

    @Test
    void parseRoundTripsAndChecksContent() {
        Affirmation a = Affirmation.of(Path.of("topics/dev/x.adoc"), 3, 4, "3f2a91c", CONTENT);

        Affirmation parsed = Affirmation.parse(a.value());

        assertEquals(a, parsed);
        assertTrue(parsed.matches(CONTENT));
        assertFalse(parsed.matches(List.of("= Title", "", "one claim,", "an edited claim,", "and a third.")),
                "an edited line inside the range breaks the hash");
        assertTrue(parsed.matches(List.of("= Other title", "", "one claim,", "another claim,")),
                "lines outside the range do not take part");
    }

    @Test
    void rangesMustFitTheContent() {
        assertThrows(IllegalArgumentException.class, () -> Affirmation.of(Path.of("x"), 0, 1, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> Affirmation.of(Path.of("x"), 4, 6, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> Affirmation.of(Path.of("x"), 3, 2, "3f2a91c", CONTENT));
        assertThrows(IllegalArgumentException.class, () -> Affirmation.parse("x.adoc:3-5#a1b2c3"));
    }
}
