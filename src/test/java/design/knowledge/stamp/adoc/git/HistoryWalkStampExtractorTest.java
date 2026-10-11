/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.git;

import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the history walk on small repositories built with JGit: plain
 * edits, a rename, an ignored formatting commit, a merge, and an unrelated
 * file, asserting the per-line STAMP each line of HEAD ends up with.
 */
class HistoryWalkStampExtractorTest {

    @TempDir
    Path root;

    private Git git;
    private int tick;

    @BeforeEach
    void init() throws Exception {
        git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call();
    }

    @AfterEach
    void close() {
        git.close();
    }

    @Test
    void everyLineNamesTheCommitThatIntroducedIt() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit b = commit("doc.adoc", "bob", "one", "two-b", "three", "four");
        RevCommit c = commit("other.adoc", "carol", "unrelated");

        List<String> byLine = attribution("doc.adoc");

        assertEquals(List.of(sha(a), sha(b), sha(a), sha(b)), byLine);
        assertTrue(stampsOf("doc.adoc").stamps().keySet().stream().noneMatch(id -> id.value().equals(c.name())),
                "a commit that never touched the file is not a STAMP of it");
    }

    @Test
    void renamesAreFollowedWithoutTakingAuthorship() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        move("doc.adoc", "topics/doc.adoc", "mover");
        RevCommit d = commit("topics/doc.adoc", "dave", "one", "two", "three", "four");

        assertEquals(List.of(sha(a), sha(a), sha(a), sha(d)), attribution("topics/doc.adoc"));
        assertEquals(Path.of("doc.adoc"), stampsOf("topics/doc.adoc").stamps().get(record(a).id()).module(),
                "a STAMP remembers the path the file had when it was made");
    }

    @Test
    void ignoredCommitsPassAttributionThroughPositionally() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit b = commit("doc.adoc", "bob", "one", "two-b", "three", "four");
        RevCommit reflow = commit("doc.adoc", "formatter", "  one", "  two-b", "  three", "  four");
        RevCommit e = commit("doc.adoc", "erin", "title", "  one", "  two-b", "  three", "  four");
        Files.writeString(root.resolve(".git-blame-ignore-revs"),
                "# formatting only\n" + reflow.name() + "   # reflow\n\n");

        StampFile head = stampsOf("doc.adoc");

        assertEquals(List.of(sha(e), sha(a), sha(b), sha(a), sha(b)), attribution("doc.adoc"));
        assertTrue(head.ignoredRevs().contains(reflow.name()));
        assertTrue(head.stamps().keySet().stream().noneMatch(id -> id.value().equals(reflow.name())),
                "an ignored commit owns no lines");
    }

    @Test
    void ignoredPureInsertionInheritsFromTheLineAbove() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit blank = commit("doc.adoc", "formatter", "one", "", "two");
        Files.writeString(root.resolve(".git-blame-ignore-revs"), blank.name() + "\n");

        assertEquals(List.of(sha(a), sha(a), sha(a)), attribution("doc.adoc"));
    }

    @Test
    void aMergeIsOneStampAlongTheFirstParentChain() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        git.checkout().setCreateBranch(true).setName("feature").call();
        commit("doc.adoc", "frank", "one", "two", "three-f");
        git.checkout().setName("main").call();
        RevCommit m = commit("doc.adoc", "mia", "one-m", "two", "three");
        setIdentity("merger");
        MergeResult merge = git.merge().include(git.getRepository().resolve("feature"))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true).setMessage("Merge feature").call();
        assertTrue(merge.getMergeStatus().isSuccessful(), merge.getMergeStatus().toString());
        RevCommit g = git.log().setMaxCount(1).call().iterator().next();

        assertEquals(List.of(sha(m), sha(a), sha(g)), attribution("doc.adoc"));
        assertEquals("merger", stampsOf("doc.adoc").stamps().get(record(g).id()).author().name());
    }

    @Test
    void emptyRepositoryYieldsNoStamps() throws Exception {
        Files.writeString(root.resolve("doc.adoc"), "uncommitted\n");

        StampFile head = stampsOf("doc.adoc");

        assertTrue(head.stamps().isEmpty());
        assertEquals("", head.revisionSha());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private StampFile stampsOf(String file) throws IOException {
        try (GitRepo repo = GitRepo.open(root)) {
            return repo.stampHistoryOf(Path.of(file)).atHead();
        }
    }

    /** Short SHA of the STAMP covering each line of HEAD, in line order. */
    private List<String> attribution(String file) throws IOException {
        StampFile head = stampsOf(file);
        int lines = head.stamps().values().stream().mapToInt(r -> r.introduced().isEmpty() ? 0 : r.introduced().last()).max().orElse(0);
        List<String> byLine = new ArrayList<>();
        for (int line = 1; line <= lines; line++) {
            String owner = null;
            for (StampRecord r : head.stamps().values()) {
                if (r.introduced().contains(line)) {
                    assertEquals(null, owner, "line " + line + " claimed twice");
                    owner = r.commitSha().substring(0, 7);
                }
            }
            byLine.add(owner);
        }
        return byLine;
    }

    private static StampRecord record(RevCommit c) {
        return new StampRecord(new design.knowledge.stamp.adoc.model.StampId(c.name()), c.name(), null, null, null, null, null, null);
    }

    private static String sha(RevCommit c) {
        return c.name().substring(0, 7);
    }

    private RevCommit commit(String file, String author, String... lines) throws Exception {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, String.join("\n", lines) + "\n");
        git.add().addFilepattern(file).call();
        PersonIdent who = ident(author);
        return git.commit().setAuthor(who).setCommitter(who).setMessage(author + " edits " + file).call();
    }

    private void move(String from, String to, String author) throws Exception {
        Path target = root.resolve(to);
        Files.createDirectories(target.getParent());
        Files.move(root.resolve(from), target);
        git.rm().addFilepattern(from).call();
        git.add().addFilepattern(to).call();
        PersonIdent who = ident(author);
        git.commit().setAuthor(who).setCommitter(who).setMessage(author + " moves " + from).call();
    }

    private void setIdentity(String name) throws IOException {
        var cfg = git.getRepository().getConfig();
        cfg.setString("user", null, "name", name);
        cfg.setString("user", null, "email", name + "@example.test");
        cfg.save();
    }

    private PersonIdent ident(String name) {
        Instant when = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60L * ++tick);
        return new PersonIdent(name, name + "@example.test", when, ZoneOffset.UTC);
    }
}
