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
import static org.junit.jupiter.api.Assertions.assertThrows;

class SegmentIdTest {

    @Test
    void keysRoundTrip() {
        SegmentId sentence = new SegmentId(SegmentId.Kind.SENTENCE, "abc123", "topics/a:b.adoc", 7);
        assertEquals("segment:abc123:topics/a:b.adoc:7", sentence.key());
        assertEquals(sentence, SegmentId.parse(sentence.key()));
        SegmentId inline = new SegmentId(SegmentId.Kind.INLINE, "abc123", "x.adoc", 2);
        assertEquals("inline:abc123:x.adoc:2", inline.key());
        assertEquals(inline, SegmentId.parse(inline.key()));
    }

    @Test
    void aLineKeyIsNoSegmentKey() {
        assertThrows(IllegalArgumentException.class, () -> SegmentId.parse("line:abc:x.adoc:1"));
    }
}
