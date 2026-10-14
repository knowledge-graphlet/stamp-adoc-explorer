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

import design.knowledge.stamp.adoc.identity.Uuid5;

import java.util.Optional;
import java.util.UUID;

/**
 * The annotation kinds this engine knows, each the IKE starter-set concept of
 * the same name: {@code T5(d890e06f…, "<name> (IkeFoundation)")}, the identity
 * rule the starter set mints every concept by. The fully qualified names are
 * therefore frozen: commit messages carry these UUIDs.
 *
 * <p>An {@code Annotation:} trailer whose kind is none of these is reported by
 * the walker rather than guessed at; a new kind is a new starter-set concept
 * and a new constant here.
 */
public enum AnnotationKind {

    /** Free commentary with no stance: the default kind. */
    COMMENT("Uncategorized comment", "Comment on", false),

    /** The annotator has reviewed the content and stands behind it. */
    AFFIRMATION("Affirmation", "Affirm", true),

    /** The annotator has reviewed the content and disputes it. */
    REFUTATION("Refutation", "Refute", true);

    /** The IKE starter set's knowledge-set namespace. */
    public static final UUID STARTER_SET_NAMESPACE = Namespace.STARTER_SET;

    private final String label;
    private final String verb;
    private final boolean judgment;
    private final UUID uuid;

    AnnotationKind(String label, String verb, boolean judgment) {
        this.label = label;
        this.verb = verb;
        this.judgment = judgment;
        this.uuid = Uuid5.of(Namespace.STARTER_SET, label + " (IkeFoundation)");
    }

    /** @return the concept's PublicId */
    public UUID uuid() {
        return uuid;
    }

    /** @return the concept's synonym, used as the reference label */
    public String label() {
        return label;
    }

    /** @return the imperative a commit subject opens with, {@code Affirm}, {@code Refute}, {@code Comment on} */
    public String verb() {
        return verb;
    }

    /**
     * @return whether this kind is a judgment, a stance on the content: a later
     *         judgment by the same annotator on the same line supersedes it
     */
    public boolean judgment() {
        return judgment;
    }

    /** @return the reference written into a trailer */
    public KonceptRef ref() {
        return new KonceptRef(uuid, label);
    }

    /**
     * @param uuid a kind concept's PublicId
     * @return the known kind with that identity, if any
     */
    public static Optional<AnnotationKind> of(UUID uuid) {
        for (AnnotationKind k : values()) {
            if (k.uuid.equals(uuid)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** Holds the namespace apart, since enum constants cannot read their own class's static fields while initializing. */
    private static final class Namespace {
        static final UUID STARTER_SET = UUID.fromString("d890e06f-ec35-429a-b541-d0ead19695e2");
    }
}
