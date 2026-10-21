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

import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.CommitEvent;
import design.knowledge.stamp.adoc.model.LineId;
import design.knowledge.stamp.adoc.model.SegmentId;
import design.knowledge.stamp.adoc.model.StampHistory;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import design.knowledge.stamp.adoc.model.LineRange;
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

        assertEquals(Status.ACTIVE, affirmation.status(), "an annotation is a role on lines, not a status");
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
        RevCommit wrongHash = emptyCommit("rita", "Affirm doc.adoc 1-2\n\nAnnotation: " + AnnotationKind.AFFIRMATION.ref()
                + " doc.adoc:1-2@" + sha(a) + "#000000\n");
        RevCommit malformed = emptyCommit("sam", "Affirm\n\nAnnotation: doc.adoc lines one and two\n");
        RevCommit unknownKind = emptyCommit("tess", "Annotate\n\nAnnotation: k:uuid=00000000-0000-5000-8000-000000000000[Doubt] "
                + LineRange.of(Path.of("doc.adoc"), 1, 1, a.name(), List.of("one", "two")).value() + "\n");

        StampFile head = stampsOf("doc.adoc");

        assertFalse(head.stamps().containsKey(id(wrongHash)));
        assertFalse(head.stamps().containsKey(id(malformed)));
        assertFalse(head.stamps().containsKey(id(unknownKind)));
        assertEquals(3, head.problems().size(), head.problems().toString());
        assertTrue(head.problems().get(0).contains("unknown annotation kind"), "newest commit first: " + head.problems());
        assertEquals(List.of(sha(a), sha(a)), attribution("doc.adoc"));
    }

    @Test
    void aLaterJudgmentBySameAnnotatorSupersedesAndCommentsAccumulate() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit affirmed = affirm("rita", "doc.adoc", 1, 3, a);
        RevCommit refuted = annotate(AnnotationKind.REFUTATION, "rita", "doc.adoc", 2, 2, a, "Two is overstated.");
        RevCommit samAffirms = affirm("sam", "doc.adoc", 2, 2, a);
        RevCommit comment = annotate(AnnotationKind.COMMENT, "rita", "doc.adoc", 3, 3, a, "");

        StampFile head = stampsOf("doc.adoc");

        assertEquals(Set.of(1, 3), lines(head.stamps().get(id(affirmed)).affirmed()),
                "rita's refutation of line 2 replaces her affirmation; her later comment on line 3 leaves it alone");
        assertEquals(Set.of(2), lines(head.stamps().get(id(refuted)).refuted()));
        assertEquals(Set.of(2), lines(head.stamps().get(id(samAffirms)).affirmed()), "sam's stance is his own");
        assertEquals(Set.of(3), lines(head.stamps().get(id(comment)).commented()));
        CommitEvent.AnnotationEvent refutation = historyOf("doc.adoc").events().stream()
                .filter(e -> e.commit().sha().equals(refuted.name())).findFirst().orElseThrow().annotations().get(0);
        assertEquals("Two is overstated.", refutation.text());
    }

    @Test
    void annotationsOfAnotherFileOrOfTheGraphAreNotThisWalksConcern() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit o = commit("other.adoc", "olga", "elsewhere");
        affirm("rita", "other.adoc", 1, 1, o);
        emptyCommit("sam", "Affirm a concept\n\nAnnotation: " + AnnotationKind.AFFIRMATION.ref()
                + " k:uuid=6111dc03-6974-5a07-9b98-10cb7921e29a[Affirmation]@0e9d5f31-35c5-5bd4-a3b6-6fd0e0bb12e5\n");

        StampHistory history = historyOf("doc.adoc");

        assertEquals(List.of(sha(a)), history.events().stream().map(e -> e.commit().sha().substring(0, 7)).toList());
        assertTrue(history.atHead().problems().isEmpty(), history.atHead().problems().toString());
    }

    @Test
    void bodyIsTheTextBetweenSubjectAndTrailers() throws Exception {
        commit("doc.adoc", "alice", "one");
        RevCommit c = emptyCommit("rita", "Refute doc.adoc 1-1\n\nFirst paragraph.\n\nSecond paragraph.\n\nAnnotation: x\nRefs: o/r#1\n");

        assertEquals("First paragraph.\n\nSecond paragraph.", HistoryWalkStampExtractor.bodyOf(c));
        assertEquals("", HistoryWalkStampExtractor.bodyOf(emptyCommit("sam", "Affirm\n\nAnnotation: x\n")));
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
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 2), new LineId(a.name(), "doc.adoc", 3)), first.manifest());
        CommitEvent second = events.get(1);
        assertEquals(List.of("title"), second.introduced().stream().map(CommitEvent.Line::text).toList());
        assertEquals(List.of(new CommitEvent.Line(new LineId(a.name(), "doc.adoc", 2), "two-b")), second.retexted(),
                "the edited line keeps its identity: a revision, re-texted under bob's commit");
        assertTrue(second.retired().isEmpty(), "nothing retires when a line is revised in place");
        assertEquals(List.of(new LineId(b.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 2), new LineId(a.name(), "doc.adoc", 3)), second.manifest(),
                "every line keeps the identity it was introduced under, wherever it moves and however it is revised");
        CommitEvent third = events.get(2);
        assertFalse(third.touchesFile());
        assertEquals(second.manifest(), third.manifest());
        assertEquals(1, third.annotations().size());
        CommitEvent.AnnotationEvent affirmation = third.annotations().get(0);
        assertEquals(List.of(new LineId(b.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 1)), affirmation.lines());
        assertEquals(AnnotationKind.AFFIRMATION, affirmation.kind());
        assertEquals(0, affirmation.index());
        assertEquals(b.name(), affirmation.reviewedSha(), "the version rita saw is the one b committed");
        assertEquals("rita", third.commit().author().name());
        CommitEvent fourth = events.get(3);
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 2), new LineId(a.name(), "doc.adoc", 3)), fourth.retired());
        assertEquals(List.of(new LineId(b.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 1)), fourth.manifest());
    }

    @Test
    void aRevisedLineKeepsItsIdentityAndTakesAuthorshipOfItsText() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two", "three");
        RevCommit b = commit("doc.adoc", "bob", "one", "two, revised", "three");

        List<CommitEvent> events = historyOf("doc.adoc").events();
        CommitEvent revision = events.get(1);
        assertTrue(revision.introduced().isEmpty(), "nothing new");
        assertTrue(revision.retired().isEmpty(), "nothing retired");
        assertEquals(List.of(new CommitEvent.Line(new LineId(a.name(), "doc.adoc", 2), "two, revised")), revision.retexted());
        assertEquals(events.get(0).manifest(), revision.manifest(), "the same three lines");
        assertEquals(List.of(sha(a), sha(b), sha(a)), attribution("doc.adoc"), "bob wrote the revised text");
    }

    @Test
    void aHunkPairsInOrderAndWhatIsLeftOverIsIntroducedOrRetired() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "keep", "x1", "x2", "x3", "end");
        RevCommit b = commit("doc.adoc", "bob", "keep", "y1", "y2", "end");               // three replaced by two
        RevCommit c = commit("doc.adoc", "carol", "keep", "z1", "z2", "z3", "z4", "end"); // two replaced by four

        List<CommitEvent> events = historyOf("doc.adoc").events();
        CommitEvent fewer = events.get(1);
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 2), new LineId(a.name(), "doc.adoc", 3)),
                fewer.retexted().stream().map(CommitEvent.Line::id).toList(), "the first two pair off");
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 4)), fewer.retired(), "the third retires");
        CommitEvent more = events.get(2);
        assertEquals(List.of("z1", "z2"), more.retexted().stream().map(CommitEvent.Line::text).toList(), "two pair off");
        assertEquals(List.of(new CommitEvent.Line(new LineId(c.name(), "doc.adoc", 4), "z3"), new CommitEvent.Line(new LineId(c.name(), "doc.adoc", 5), "z4")),
                more.introduced(), "the rest are new lines");
        assertTrue(more.retired().isEmpty());
    }

    @Test
    void aLineMovedUnchangedKeepsItsIdentityAndItsAnnotations() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "Alpha claim.", "Beta claim.", "----", "Gamma claim.");
        RevCommit rita = affirm("rita", "doc.adoc", 1, 1, a);
        RevCommit b = commit("doc.adoc", "bob", "Beta claim.", "Gamma claim.", "Alpha claim.");   // alpha moves to the end, the delimiter goes

        StampHistory history = historyOf("doc.adoc");
        CommitEvent move = history.events().get(2);
        assertTrue(move.introduced().isEmpty(), "the moved line is not new: " + move.introduced());
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 3)), move.retired(), "only the delimiter retires; it is too common to follow");
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 2), new LineId(a.name(), "doc.adoc", 4), new LineId(a.name(), "doc.adoc", 1)),
                move.manifest(), "alpha keeps the identity it was introduced under, now last");
        assertEquals(List.of(sha(a), sha(a), sha(a)), attribution("doc.adoc"), "moving is not authoring");
        StampFile head = stampsOf("doc.adoc");
        assertEquals(Set.of(3), lines(head.stamps().get(id(rita)).affirmed()), "rita's affirmation moved with the line");
    }

    @Test
    void linesIntroducedTogetherInTwoFilesAreDistinctIdentities() throws Exception {
        // One commit introduces both files: without the path in the identity, line n of each was one line.
        Files.createDirectories(root.resolve("topics"));
        Files.writeString(root.resolve("topics/medication.adoc"), "= Medication\n\nA dose.\n");
        Files.writeString(root.resolve("topics/integrity.adoc"), "= Integrity\n\nA reference.\n");
        git.add().addFilepattern("topics/medication.adoc").addFilepattern("topics/integrity.adoc").call();
        PersonIdent who = ident("alice");
        RevCommit a = git.commit().setAuthor(who).setCommitter(who).setMessage("alice adds two topics").call();

        List<LineId> medication = historyOf("topics/medication.adoc").events().get(0).manifest();
        List<LineId> integrity = historyOf("topics/integrity.adoc").events().get(0).manifest();

        assertEquals(List.of(new LineId(a.name(), "topics/medication.adoc", 1), new LineId(a.name(), "topics/medication.adoc", 2),
                new LineId(a.name(), "topics/medication.adoc", 3)), medication);
        assertEquals(List.of(new LineId(a.name(), "topics/integrity.adoc", 1), new LineId(a.name(), "topics/integrity.adoc", 2),
                new LineId(a.name(), "topics/integrity.adoc", 3)), integrity);
        Set<LineId> shared = new java.util.HashSet<>(medication);
        shared.retainAll(integrity);
        assertEquals(Set.of(), shared, "no line identity is shared between the two files");
        assertEquals(6, java.util.stream.Stream.concat(medication.stream(), integrity.stream()).map(LineId::key).distinct().count(),
                "six lines, six keys");
    }

    @Test
    void aLineKeepsThePathItWasIntroducedUnderAcrossARename() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        move("doc.adoc", "topics/doc.adoc", "mover");
        RevCommit d = commit("topics/doc.adoc", "dave", "one", "two", "three");

        List<CommitEvent> events = historyOf("topics/doc.adoc").events();

        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 2), new LineId(d.name(), "topics/doc.adoc", 3)),
                events.get(events.size() - 1).manifest(),
                "a line's identity names the path at its introducing commit; a later rename does not re-mint it");
    }

    @Test
    void ignoredReflowRetextsLinesWithoutNewIdentities() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "one", "two");
        RevCommit reflow = commit("doc.adoc", "formatter", "  one", "  two", "");
        Files.writeString(root.resolve(".git-blame-ignore-revs"), reflow.name() + "\n");

        CommitEvent event = historyOf("doc.adoc").events().get(1);

        assertTrue(event.ignored());
        assertEquals(List.of(new LineId(a.name(), "doc.adoc", 1), new LineId(a.name(), "doc.adoc", 2)), event.retexted().stream().map(CommitEvent.Line::id).toList());
        assertEquals(List.of("  one", "  two"), event.retexted().stream().map(CommitEvent.Line::text).toList());
        assertEquals(List.of(new LineId(reflow.name(), "doc.adoc", 3)), event.introduced().stream().map(CommitEvent.Line::id).toList(),
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

    // ── sentences and inline elements below the line (IKE-Network/ike-issues#1313) ──

    @Test
    void aReflowKeepsEverySentence() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "The lease is a loan. A machine takes a working set", "and returns it when done.");
        commit("doc.adoc", "bob", "The lease is a loan.", "A machine takes a working set and returns it when done.");
        List<CommitEvent> events = historyOf("doc.adoc").events();
        CommitEvent.Segments first = events.get(0).segments();
        CommitEvent.Segments reflow = events.get(1).segments();
        assertEquals(List.of(new SegmentId(SegmentId.Kind.SENTENCE, a.name(), "doc.adoc", 1),
                new SegmentId(SegmentId.Kind.SENTENCE, a.name(), "doc.adoc", 2)), first.manifest());
        assertTrue(events.get(1).touchesFile(), "the lines changed");
        assertFalse(reflow.changed(), "no sentence did");
        assertEquals(first.manifest(), reflow.manifest());
    }

    @Test
    void aRevisedSentenceKeepsItsIdentityAndANewOneIsIntroducedWhereItStands() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "Exact TTL value and renewal cadence are proposed. Directory name is open.");
        RevCommit b = commit("doc.adoc", "bob", "A bus with an hour of latency is not a bus.",
                "Exact TTL value and renewal cadence are in force since August. Directory name is open.");
        CommitEvent.Segments segments = historyOf("doc.adoc").events().get(1).segments();
        SegmentId ttl = new SegmentId(SegmentId.Kind.SENTENCE, a.name(), "doc.adoc", 1);
        assertEquals(List.of(new CommitEvent.Segment(ttl, "Exact TTL value and renewal cadence are in force since August.")), segments.retexted());
        assertEquals(List.of(new CommitEvent.Segment(new SegmentId(SegmentId.Kind.SENTENCE, b.name(), "doc.adoc", 1),
                "A bus with an hour of latency is not a bus.")), segments.introduced());
        assertEquals(List.of(), segments.retired());
        assertEquals(ttl, segments.manifest().get(1));
    }

    @Test
    void inlineElementsPairWithinTheirSentence() throws Exception {
        RevCommit a = commit("doc.adoc", "alice", "See <<old-target>> for *more*. Then stop.");
        RevCommit b = commit("doc.adoc", "bob", "See <<new-target>> for *more* and _less_. Then stop.");
        List<CommitEvent> events = historyOf("doc.adoc").events();
        CommitEvent.Segments first = events.get(0).segments();
        CommitEvent.Segments second = events.get(1).segments();
        assertEquals(2, first.inlineManifest().size());
        SegmentId xref = new SegmentId(SegmentId.Kind.INLINE, a.name(), "doc.adoc", 1);
        assertEquals(List.of(new CommitEvent.Segment(xref, "<<new-target>>")), second.inlineRetexted(), "a changed target is a new version");
        assertEquals(List.of(new CommitEvent.Segment(new SegmentId(SegmentId.Kind.INLINE, b.name(), "doc.adoc", 3), "_less_")),
                second.inlineIntroduced());
        assertEquals(1, second.retexted().size(), "the sentence is revised, not replaced");
    }

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
        return new StampRecord(id(c), c.name(), null, null, null, null, null, null, null, null, null, false);
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
        return annotate(AnnotationKind.AFFIRMATION, author, file, from, to, rev, "");
    }

    /** An empty commit by {@code author} annotating lines {@code from}–{@code to} of {@code file} as committed at {@code rev}. */
    private RevCommit annotate(AnnotationKind kind, String author, String file, int from, int to, RevCommit rev, String text) throws Exception {
        List<String> content;
        try (GitRepo repo = GitRepo.open(root)) {
            content = repo.linesAt(rev.name(), Path.of(file));
        }
        Annotation a = Annotation.of(kind, LineRange.of(Path.of(file), from, to, rev.name(), content));
        return emptyCommit(author, kind.verb() + " " + file + " " + from + "-" + to + "\n\n" + (text.isEmpty() ? "" : text + "\n\n")
                + a.trailer() + "\n");
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
