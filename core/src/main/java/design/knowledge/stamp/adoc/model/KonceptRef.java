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
 * A Koncept reference in the text form the AsciiDoc {@code k:} macro renders
 * as a badge: {@code k:uuid=<uuid>[<label>]}. The UUID is the identity; the
 * label is for whoever reads the commit message and takes no part in equality,
 * so a concept renamed after the reference was written still resolves.
 *
 * <p>Only the PublicId form is accepted. A name key ({@code k:Affirmation[]})
 * resolves through a {@code koncepts.yml} that may have changed since the
 * commit was written, and commit messages are permanent.
 *
 * @param uuid  the referent's PublicId
 * @param label a display label, possibly empty; never contains {@code ]}
 */
public record KonceptRef(UUID uuid, String label) {

    /** The pattern of one reference, for embedding in larger grammars. */
    public static final String REGEX = "k:uuid=[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\[[^\\]\\n]*\\]";

    private static final Pattern FORM = Pattern.compile("k:uuid=(?<uuid>[0-9a-fA-F-]{36})\\[(?<label>[^\\]\\n]*)\\]");

    /**
     * Validates the record.
     */
    public KonceptRef {
        Objects.requireNonNull(uuid, "uuid");
        label = label == null ? "" : label.strip();
        if (label.indexOf(']') >= 0 || label.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("a Koncept label cannot contain ']' or a line break: " + label);
        }
    }

    /**
     * @param text {@code k:uuid=<uuid>[<label>]}, surrounding whitespace allowed
     * @return the reference
     * @throws IllegalArgumentException if the text is not a PublicId reference
     */
    public static KonceptRef parse(String text) {
        Matcher m = FORM.matcher(text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a k:uuid=…[label] reference: " + text);
        }
        return new KonceptRef(UUID.fromString(m.group("uuid")), m.group("label"));
    }

    /** @return {@code k:uuid=<uuid>[<label>]} */
    @Override
    public String toString() {
        return "k:uuid=" + uuid + "[" + label + "]";
    }

    /** Equality is identity: the label is presentation. */
    @Override
    public boolean equals(Object o) {
        return o instanceof KonceptRef other && uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
