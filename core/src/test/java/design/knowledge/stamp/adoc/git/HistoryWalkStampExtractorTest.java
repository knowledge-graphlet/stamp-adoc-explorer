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

import design.knowledge.stamp.adoc.model.Affirmation;
import design.knowledge.stamp.adoc.model.CommitEvent;
import design.knowledge.stamp.adoc.model.LineId;
import design.knowledge.stamp.adoc.model.StampHistory;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import design.knowledge.stamp.adoc.model.Status;
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
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void affirmationsAttachToLinesAndRideUnrelatedEdits() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit rita = affirm("rita", "doc.adoc", 2, 3, a);
        commit("doc.adoc", "bob", "one-b", "two", "three");            // an edit elsewhere leaves the affirmation alone
        RevCommit carol = commit("doc.adoc", "carol", "one-b", "two", "three-c");   // editing an affirmed line drops it

        StampFile head = stampsOf("doc.adoc");
        StampRecord affirmation = head.stamps().get(id(rita));

        assertEquals(Status.AFFIRMED, affirmation.status());
        assertEquals("rita", affirmation.author().name());
        assertEquals(Set.of(2), lines(affirmation.affirmed()));
        assertTrue(affirmation.introduced().isEmpty());
        assertFalse(affirmation.signed());
        assertEquals(Set.of(3), lines(head.stamps().get(id(carol)).introduced()));
        assertTrue(head.problems().isEmpty(), head.problems().toString());
    }

    @Test
    void affirmationAnchoredBeforeALaterInsertionMapsForward() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        commit("doc.adoc", "bob", "title", "one", "two", "three");
        RevCommit rita = affirm("rita", "doc.adoc", 2, 3, a);           // "two" and "three" as numbered at a

        assertEquals(Set.of(3, 4), lines(stampsOf("doc.adoc").stamps().get(id(rita)).affirmed()));
    }

    @Test
    void twoAffirmersOnOneLineAreBothKept() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit rita = affirm("rita", "doc.adoc", 1, 2, a);
        RevCommit sam = affirm("sam", "doc.adoc", 2, 2, a);

        StampFile head = stampsOf("doc.adoc");

        assertEquals(Set.of(1, 2), lines(head.stamps().get(id(rita)).affirmed()));
        assertEquals(Set.of(2), lines(head.stamps().get(id(sam)).affirmed()));
    }

    @Test
    void staleOrMalformedAffirmationsAreReportedNotApplied() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit wrongHash = emptyCommit("rita", "Affirm doc.adoc 1-2\n\nAffirms: doc.adoc:1-2@" + sha(a) + "#000000\n");
        RevCommit malformed = emptyCommit("sam", "Affirm\n\nAffirms: doc.adoc lines one and two\n");

        StampFile head = stampsOf("doc.adoc");

        assertFalse(head.stamps().containsKey(id(wrongHash)));
        assertFalse(head.stamps().containsKey(id(malformed)));
        assertEquals(2, head.problems().size(), head.problems().toString());
        assertEquals(List.of(sha(a), sha(a)), attribution("doc.adoc"));
    }

    @Test
    void eventsDescribeEachCommitWithStableLineIdentities() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit b = commit("doc.adoc", "bob", "title", "one", "two-b", "three");        // insert above, edit the middle
        RevCommit rita = affirm("rita", "doc.adoc", 1, 2, b);
        RevCommit c = commit("doc.adoc", "carol", "title", "one");                       // retire the last two

        List<CommitEvent> events = historyOf("doc.adoc").events();

        assertEquals(List.of(sha(a), sha(b), sha(rita), sha(c)), events.stream().map(e -> e.commit().sha().substring(0, 7)).toList());
        CommitEvent first = events.get(0);
        assertEquals(List.of("one", "two", "three"), first.introduced().stream().map(CommitEvent.Line::text).toList());
        assertEquals(List.of(new LineId(a.name(), 1), new LineId(a.name(), 2), new LineId(a.name(), 3)), first.manifest());
        CommitEvent second = events.get(1);
        assertEquals(List.of("title", "two-b"), second.introduced().stream().map(CommitEvent.Line::text).toList());
        assertEquals(List.of(new LineId(a.name(), 2)), second.retired(), "the edited line retires; its replacement is a new identity");
        assertEquals(List.of(new LineId(b.name(), 1), new LineId(a.name(), 1), new LineId(b.name(), 3), new LineId(a.name(), 3)), second.manifest(),
                "unchanged lines keep the identity they were introduced under, wherever they move");
        CommitEvent third = events.get(2);
        assertFalse(third.touchesFile());
        assertEquals(second.manifest(), third.manifest());
        assertEquals(1, third.affirmations().size());
        assertEquals(List.of(new LineId(b.name(), 1), new LineId(a.name(), 1)), third.affirmations().get(0).lines());
        assertEquals("rita", third.commit().author().name());
        CommitEvent fourth = events.get(3);
        assertEquals(List.of(new LineId(b.name(), 3), new LineId(a.name(), 3)), fourth.retired());
        assertEquals(List.of(new LineId(b.name(), 1), new LineId(a.name(), 1)), fourth.manifest());
    }

    @Test
    void ignoredReflowRetextsLinesWithoutNewIdentities() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit reflow = commit("doc.adoc", "formatter", "  one", "  two", "");
        Files.writeString(root.resolve(".git-blame-ignore-revs"), reflow.name() + "\n");

        CommitEvent event = historyOf("doc.adoc").events().get(1);

        assertTrue(event.ignored());
        assertEquals(List.of(new LineId(a.name(), 1), new LineId(a.name(), 2)), event.retexted().stream().map(CommitEvent.Line::id).toList());
        assertEquals(List.of("  one", "  two"), event.retexted().stream().map(CommitEvent.Line::text).toList());
        assertEquals(List.of(new LineId(reflow.name(), 3)), event.introduced().stream().map(CommitEvent.Line::id).toList(),
                "a line an ignored commit adds beyond what it replaced is a fresh identity");
        assertEquals(List.of(sha(a), sha(a), sha(a)), attribution("doc.adoc"), "attribution still ignores the reflow");
    }

    @Test
    void asOfReplaysToAnEarlierCommitInItsOwnLineNumbers() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit rita = affirm("rita", "doc.adoc", 2, 3, a);
        RevCommit b = commit("doc.adoc", "bob", "title", "one", "two", "three");
        git.checkout().setCreateBranch(true).setName("side").call();
        RevCommit off = commit("doc.adoc", "sam", "elsewhere");
        git.checkout().setName("main").call();

        StampHistory history = historyOf("doc.adoc");

        StampFile atA = history.asOf(a.name());
        assertEquals(Set.of(1, 2, 3), lines(atA.stamps().get(id(a)).introduced()));
        assertFalse(atA.stamps().containsKey(id(rita)), "the affirmation came after a");
        StampFile atRita = history.asOf(rita.name().substring(0, 7));
        assertEquals(Set.of(2, 3), lines(atRita.stamps().get(id(rita)).affirmed()));
        StampFile atB = history.asOf(b.name());
        assertEquals(Set.of(3, 4), lines(atB.stamps().get(id(rita)).affirmed()), "renumbered by the insertion above");
        assertEquals(atB.stamps().keySet(), history.atHead().stamps().keySet());
        assertThrows(IllegalArgumentException.class, () -> history.asOf(off.name()), "a commit off the first-parent chain");
        assertThrows(IllegalArgumentException.class, () -> history.asOf("no-such-rev"));
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
        return historyOf(file).atHead();
    }

    private StampHistory historyOf(String file) throws IOException {
        try (GitRepo repo = GitRepo.open(root)) {
            return repo.stampHistoryOf(Path.of(file));
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
        return new StampRecord(id(c), c.name(), null, null, null, null, null, null, null, false);
    }

    private static StampId id(RevCommit c) {
        return new StampId(c.name());
    }

    private static Set<Integer> lines(org.roaringbitmap.RoaringBitmap bitmap) {
        Set<Integer> set = new TreeSet<>();
        bitmap.forEach((int line) -> set.add(line));
        return set;
    }

    /** An empty commit by {@code author} affirming lines {@code from}–{@code to} of {@code file} as committed at {@code rev}. */
    private RevCommit affirm(String author, String file, int from, int to, RevCommit rev) throws Exception {
        List<String> content;
        try (GitRepo repo = GitRepo.open(root)) {
            content = repo.linesAt(rev.name(), Path.of(file));
        }
        Affirmation a = Affirmation.of(Path.of(file), from, to, rev.name(), content);
        return emptyCommit(author, "Affirm " + file + " " + from + "-" + to + "\n\n" + a.trailer() + "\n");
    }

    private RevCommit emptyCommit(String author, String message) throws Exception {
        PersonIdent who = ident(author);
        return git.commit().setAllowEmpty(true).setAuthor(who).setCommitter(who).setMessage(message).call();
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
