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

import design.knowledge.stamp.adoc.model.LineId;
import design.knowledge.stamp.adoc.model.Person;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GitIdentityAdapterTest {

    @Test
    void uuid5MatchesTheRfc4122TestVector() {
        UUID dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

        assertEquals(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"), Uuid5.of(dns, "www.example.com"));
        assertEquals(5, Uuid5.of(dns, "www.example.com").version());
        assertEquals(2, Uuid5.of(dns, "www.example.com").variant());
    }

    @Test
    void identitiesAreDeterministicAndKeyedAsFrozen() {
        GitIdentityAdapter adapter = GitIdentityAdapter.raw();
        Person kec = new Person("Keith E Campbell", "KEC@Knowledge.design");

        assertEquals(Uuid5.of(GitIdentityAdapter.NAMESPACE, "author:kec@knowledge.design"), adapter.author(kec).uuid());
        assertEquals("Keith E Campbell", adapter.author(kec).name());
        assertEquals(adapter.author(kec), adapter.author(new Person("K. Campbell", "kec@knowledge.design")).withName("Keith E Campbell"),
                "the same email is the same author whatever name the commit carried");
        assertEquals(Uuid5.of(GitIdentityAdapter.NAMESPACE, "stamp:3f2a91c0ffee"), adapter.stamp("3f2a91c0ffee").uuid());
        assertEquals("3f2a91c", adapter.stamp("3f2a91c0ffee").name());
        assertEquals(Uuid5.of(GitIdentityAdapter.NAMESPACE, "module:anf"), adapter.module("anf").uuid());
        assertEquals(Uuid5.of(GitIdentityAdapter.NAMESPACE, "path:main"), adapter.path("main").uuid());
        assertNotEquals(adapter.module("anf").uuid(), adapter.path("anf").uuid(), "kinds never collide");
        LineId medication = new LineId("3f2a91c0ffee", "topics/medication.adoc", 3);
        assertEquals(Uuid5.of(GitIdentityAdapter.NAMESPACE, "line:3f2a91c0ffee:topics/medication.adoc:3"), adapter.line(medication).uuid());
        assertEquals("3f2a91c:topics/medication.adoc:3", adapter.line(medication).name());
        assertNotEquals(adapter.line(medication).uuid(), adapter.line(new LineId("3f2a91c0ffee", "topics/integrity.adoc", 3)).uuid(),
                "line 3 of two files introduced by one commit are two lines");
    }

    @Test
    void mailmapConsolidatesCommitIdentities(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".mailmap"), """
                # people
                Keith E Campbell <kec@knowledge.design> <stamp.versioned@knowledge.design>
                <kec@knowledge.design> <keith@old.example>
                Kerry Goetz <kerry@example.test>
                """);
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(root);

        Identity viaStamp = adapter.author(new Person("Keith E Campbell", "stamp.versioned@knowledge.design"));
        Identity viaOld = adapter.author(new Person("keith", "KEITH@old.example"));
        Identity direct = adapter.author(new Person("Keith E Campbell", "kec@knowledge.design"));

        assertEquals(direct.uuid(), viaStamp.uuid());
        assertEquals(direct.uuid(), viaOld.uuid());
        assertEquals("Keith E Campbell", viaOld.name(), "a bare email mapping takes the name from the proper identity");
        assertEquals("Kerry Goetz", adapter.author(new Person("kgoetz", "kerry@example.test")).name());
        assertEquals("Someone Else", adapter.author(new Person("Someone Else", "other@example.test")).name());
    }

    @Test
    void malformedMailmapLinesAreSkipped() {
        Map<String, Person> into = new HashMap<>();
        GitIdentityAdapter.parseMailmapLine("Broken <no-close", into);
        GitIdentityAdapter.parseMailmapLine("", into);
        GitIdentityAdapter.parseMailmapLine("# only a comment", into);

        assertEquals(Map.of(), into);
    }
}
