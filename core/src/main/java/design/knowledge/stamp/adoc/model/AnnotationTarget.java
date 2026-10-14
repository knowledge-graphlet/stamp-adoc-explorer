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

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an annotation is about: a line range of a committed document, one
 * version of a graph component, or a whole change set (a STAMP).
 *
 * <pre>
 * topics/dev/x.adoc:14-22@3f2a91c#a1b2c3              a line range
 * k:uuid=&lt;component&gt;[label]@&lt;stamp-uuid&gt;           one component version
 * k:uuid=&lt;stamp-uuid&gt;[label]                         a change set
 * </pre>
 */
public sealed interface AnnotationTarget permits LineRange, AnnotationTarget.ComponentVersion, AnnotationTarget.ChangeSet {

    /** @return the target as it is written in a trailer */
    String value();

    /**
     * One version of a graph component: the component and the STAMP of the
     * version the annotator saw. No hash is needed, since a version is immutable.
     *
     * @param component the component
     * @param stamp     the PublicId of the reviewed version's STAMP
     */
    record ComponentVersion(KonceptRef component, UUID stamp) implements AnnotationTarget {
        /** Validates the record. */
        public ComponentVersion {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(stamp, "stamp");
        }

        @Override
        public String value() {
            return component + "@" + stamp;
        }
    }

    /**
     * A whole change set: every version written under one STAMP.
     *
     * @param stamp the STAMP, as a reference
     */
    record ChangeSet(KonceptRef stamp) implements AnnotationTarget {
        /** Validates the record. */
        public ChangeSet {
            Objects.requireNonNull(stamp, "stamp");
        }

        @Override
        public String value() {
            return stamp.toString();
        }
    }

    /**
     * @param text a target as written in a trailer
     * @return the target
     * @throws IllegalArgumentException if the text is no target form
     */
    static AnnotationTarget parse(String text) {
        String t = text.strip();
        if (t.startsWith("k:")) {
            Matcher m = Pattern.compile("(?<ref>" + KonceptRef.REGEX + ")(?:@(?<stamp>[0-9a-fA-F-]{36}))?").matcher(t);
            if (!m.matches()) {
                throw new IllegalArgumentException("not a component or change-set target: " + text);
            }
            KonceptRef ref = KonceptRef.parse(m.group("ref"));
            return m.group("stamp") == null ? new ChangeSet(ref) : new ComponentVersion(ref, UUID.fromString(m.group("stamp")));
        }
        return LineRange.parse(t);
    }
}
