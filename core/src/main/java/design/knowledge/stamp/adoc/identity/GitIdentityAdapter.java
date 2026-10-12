/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.identity;

import design.knowledge.stamp.adoc.model.Person;
import design.knowledge.stamp.adoc.model.StampRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps git coordinates to IKE STAMP identities, so the same commit yields
 * the same STAMP id in documents, code and graph, and the same person the
 * same author concept on every machine.
 *
 * <p>Every identity is a version-5 UUID of {@link #NAMESPACE} and a frozen
 * name key: {@code stamp:<sha>}, {@code author:<email>},
 * {@code module:<component>}, {@code path:<branch>}. <em>The namespace and
 * the key forms are frozen</em>: changing either forks every identity
 * already written to a store.
 *
 * <p>v1 keys people by author email, lower-cased, after a mailmap-style
 * consolidation: {@link #fromMailmap(Path)} reads the repository's
 * {@code .mailmap} in git's own format, so an identity merged there is
 * merged here, and no second registry is needed until one is wanted.
 */
public final class GitIdentityAdapter {

    /** Frozen 2026-10-11. See the class comment before touching it. */
    public static final UUID NAMESPACE = UUID.fromString("2398b4ac-4e8a-4141-92a7-636d10f01e97");

    private static final String MAILMAP = ".mailmap";

    private final Map<String, Person> canonical;

    /**
     * @param canonical commit email (lower-cased) to the canonical person; a
     *                  missing entry leaves the commit identity as it is
     */
    public GitIdentityAdapter(Map<String, Person> canonical) {
        // A bare-email mapping (<proper@email> <commit@email>) names no one; give it the name
        // any full mapping or name fix recorded for that proper email, so one person reads
        // under one name whichever commit identity they arrive by.
        Map<String, String> names = new HashMap<>();
        for (Person p : canonical.values()) {
            if (!p.name().isEmpty()) {
                names.putIfAbsent(p.email(), p.name());
            }
        }
        Map<String, Person> filled = new HashMap<>();
        for (Map.Entry<String, Person> e : canonical.entrySet()) {
            Person p = e.getValue();
            filled.put(e.getKey(), p.name().isEmpty() && names.containsKey(p.email()) ? new Person(names.get(p.email()), p.email()) : p);
        }
        this.canonical = Map.copyOf(filled);
    }

    /** An adapter with no consolidation: every commit email is its own person. */
    public static GitIdentityAdapter raw() {
        return new GitIdentityAdapter(Collections.emptyMap());
    }

    /**
     * An adapter consolidated by the repository's {@code .mailmap}, if any.
     * Handled forms: {@code Proper Name <proper@email> <commit@email>},
     * {@code <proper@email> <commit@email>}, and
     * {@code Proper Name <commit@email>} (a name fix for that email).
     * Lines that follow another form, and comments, are skipped.
     *
     * @param repoRoot working-tree root
     * @return the adapter
     * @throws IOException if {@code .mailmap} exists but cannot be read
     */
    public static GitIdentityAdapter fromMailmap(Path repoRoot) throws IOException {
        Path file = repoRoot.resolve(MAILMAP);
        if (!Files.isRegularFile(file)) {
            return raw();
        }
        Map<String, Person> map = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            parseMailmapLine(line, map);
        }
        return new GitIdentityAdapter(map);
    }

    static void parseMailmapLine(String raw, Map<String, Person> into) {
        String line = raw.strip();
        int comment = line.indexOf('#');
        if (comment >= 0) {
            line = line.substring(0, comment).strip();
        }
        if (line.isEmpty()) {
            return;
        }
        List<String> emails = new java.util.ArrayList<>();
        StringBuilder outside = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int open = line.indexOf('<', i);
            if (open < 0) {
                outside.append(line, i, line.length());
                break;
            }
            int close = line.indexOf('>', open);
            if (close < 0) {
                return;                                          // malformed: skip the line
            }
            outside.append(line, i, open);
            emails.add(line.substring(open + 1, close).strip().toLowerCase(Locale.ROOT));
            i = close + 1;
        }
        String properName = outside.toString().strip();
        if (emails.size() == 2) {
            String commitEmail = emails.get(1);
            Person existing = into.get(emails.get(0));
            String name = !properName.isEmpty() ? properName : existing != null ? existing.name() : null;
            into.put(commitEmail, new Person(name == null ? "" : name, emails.get(0)));
        } else if (emails.size() == 1 && !properName.isEmpty()) {
            into.put(emails.get(0), new Person(properName, emails.get(0)));
        }
    }

    /** @return the person a commit identity resolves to after consolidation */
    public Person canonical(Person commitIdentity) {
        Person mapped = canonical.get(commitIdentity.email().toLowerCase(Locale.ROOT));
        if (mapped == null) {
            return commitIdentity;
        }
        return mapped.name().isEmpty() ? new Person(commitIdentity.name(), mapped.email()) : mapped;
    }

    /** @return the STAMP identity of a commit */
    public Identity stamp(String commitSha) {
        return identity("stamp:" + commitSha.toLowerCase(Locale.ROOT), commitSha.substring(0, Math.min(7, commitSha.length())));
    }

    /** @return the author identity of a commit identity, after consolidation */
    public Identity author(Person commitIdentity) {
        Person person = canonical(commitIdentity);
        return identity("author:" + person.email().toLowerCase(Locale.ROOT), person.name().isEmpty() ? person.email() : person.name());
    }

    /** @return the module identity of a repository or Kompendium component, by name */
    public Identity module(String component) {
        return identity("module:" + component, component);
    }

    /** @return the path identity of a branch */
    public Identity path(String branch) {
        return identity("path:" + branch, branch);
    }

    /**
     * @param record    a STAMP as the history walk produced it
     * @param component the repository or component the file belongs to
     * @param branch    the branch the snapshot was read from
     * @return the five coordinates, every concept-valued one as an identity
     */
    public StampCoordinates coordinatesOf(StampRecord record, String component, String branch) {
        return new StampCoordinates(stamp(record.commitSha()), record.status(), record.time(),
                author(record.author()), module(component), path(branch));
    }

    private static Identity identity(String key, String name) {
        return new Identity(Uuid5.of(NAMESPACE, key), key, Objects.requireNonNull(name));
    }
}
