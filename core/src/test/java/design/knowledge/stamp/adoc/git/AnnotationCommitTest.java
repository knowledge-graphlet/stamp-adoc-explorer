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
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.CommitEvent;
import design.knowledge.stamp.adoc.model.KonceptRef;
import design.knowledge.stamp.adoc.model.LineRange;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampRecord;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationCommitTest {

    @TempDir
    Path root;

    @Test
    void argumentsCarryTextAndOneTrailerPerRangeAndSignOnRequest() {
        List<Annotation> ranges = List.of(
                Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("topics/x.adoc"), 2, 3, "3f2a91c0ffee", List.of("a", "b", "c", "d"))),
                Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("topics/x.adoc"), 4, 4, "3f2a91c0ffee", List.of("a", "b", "c", "d"))));

        List<String> signed = AnnotationCommit.arguments(ranges, "  Checked against the source.  ", true);
        List<String> unsigned = AnnotationCommit.arguments(ranges, "", false);

        assertEquals("Affirm topics/x.adoc 2-3, 4-4", AnnotationCommit.subject(ranges));
        assertEquals(List.of("commit", "--allow-empty", "--quiet", "-S", "-m", "Affirm topics/x.adoc 2-3, 4-4",
                "-m", "Checked against the source.",
                "--trailer", ranges.get(0).trailer(), "--trailer", ranges.get(1).trailer()), signed);
        assertFalse(unsigned.contains("-S"));
        assertTrue(unsigned.contains("--no-gpg-sign"));
        assertEquals(1, unsigned.stream().filter("-m"::equals).count(), "no text, no body");
    }

    @Test
    void mixedKindsOrTargetsReadAsAnnotate() {
        LineRange r = LineRange.of(Path.of("x.adoc"), 1, 1, "3f2a91c0ffee", List.of("a"));
        assertEquals("Refute x.adoc 1-1", AnnotationCommit.subject(List.of(Annotation.of(AnnotationKind.REFUTATION, r))));
        assertEquals("Annotate x.adoc 1-1, 1-1", AnnotationCommit.subject(List.of(
                Annotation.of(AnnotationKind.REFUTATION, r), Annotation.of(AnnotationKind.COMMENT, r))));
        assertEquals("Retract 3f2a91c", AnnotationCommit.subject(List.of(
                Annotation.of(AnnotationKind.RETRACTION, new design.knowledge.stamp.adoc.model.AnnotationTarget.Commit("3f2a91c0ffee")))));
    }

    @Test
    void findRootWalksUpToTheGitEntry() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Path nested = root.resolve("topics/src/docs");
            Files.createDirectories(nested);
            Files.writeString(nested.resolve("x.adoc"), "x\n");

            assertEquals(Optional.of(root.toRealPath()), GitRepo.findRoot(nested.resolve("x.adoc")).map(p -> {
                try { return p.toRealPath(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }));
            assertEquals(Optional.empty(), GitRepo.findRoot(Path.of("/")));
        }
    }

    @Test
    void writeRecordsAnUnsignedAnnotationThatTheWalkerReadsBack() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            Files.writeString(root.resolve("doc.adoc"), "one\ntwo\nthree\n");
            git.add().addFilepattern("doc.adoc").call();
            git.commit().setAuthor("alice", "alice@example.test").setCommitter("alice", "alice@example.test").setMessage("add").call();
            new ProcessBuilder("git", "config", "user.name", "Rita Reviewer").directory(root.toFile()).start().waitFor();
            new ProcessBuilder("git", "config", "user.email", "rita@example.test").directory(root.toFile()).start().waitFor();
            List<String> content;
            String head;
            try (GitRepo repo = GitRepo.open(root)) {
                content = repo.headLines(Path.of("doc.adoc"));
                head = repo.headRevision();
            }
            Annotation a = Annotation.of(AnnotationKind.REFUTATION, LineRange.of(Path.of("doc.adoc"), 2, 3, head, content));

            AnnotationCommit.Result result = AnnotationCommit.write(root, List.of(a), "Three is wrong.", false);

            assertTrue(result.ok(), result.output());
            try (GitRepo repo = GitRepo.open(root)) {
                StampFile after = repo.stampHistoryOf(Path.of("doc.adoc")).atHead();
                StampRecord refutation = after.stamps().values().stream().filter(r -> r.introduced().isEmpty()).findFirst().orElseThrow();
                assertEquals("Rita Reviewer", refutation.author().name());
                assertEquals(2, refutation.refuted().getCardinality());
                assertTrue(refutation.affirmed().isEmpty());
                String text = repo.stampHistoryOf(Path.of("doc.adoc")).events().getLast().annotations().get(0).text();
                assertEquals("Three is wrong.", text);
                assertTrue(after.problems().isEmpty(), after.problems().toString());
            }
        }
    }

    /**
     * A comment on a commit's STAMP, as the commit narrator writes it (IKE-Network/ike-issues#1355): the walker
     * hands it on with the file whose commit it is about, on no lines, carrying the commit, its text and its
     * identity, so a store rebuilt from git recreates it with the same identity. A comment on a commit that did
     * not touch the file is not the file's.
     */
    @Test
    void aCommentOnACommitsStampGoesWithTheFileItChanged() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            Files.writeString(root.resolve("doc.adoc"), "one\ntwo\n");
            git.add().addFilepattern("doc.adoc").call();
            String narrated = git.commit().setAuthor("alice", "alice@example.test").setCommitter("alice", "alice@example.test")
                    .setMessage("add").call().name();
            Files.writeString(root.resolve("other.adoc"), "elsewhere\n");
            git.add().addFilepattern("other.adoc").call();
            String elsewhere = git.commit().setAuthor("alice", "alice@example.test").setCommitter("alice", "alice@example.test")
                    .setMessage("other").call().name();
            new ProcessBuilder("git", "config", "user.name", "Rita Reviewer").directory(root.toFile()).start().waitFor();
            new ProcessBuilder("git", "config", "user.email", "rita@example.test").directory(root.toFile()).start().waitFor();
            KonceptRef narrator = new KonceptRef(UUID.fromString("06f3a6e6-3627-5115-94fb-238c789b8743"), "Komet Narrator (Claude)");
            UUID id = UUID.fromString("5e2a5d0c-1b7e-5c3a-9d4f-0a1b2c3d4e5f");
            Instant at = Instant.parse("2026-11-08T18:00:00Z");
            Annotation onDoc = new Annotation(AnnotationKind.COMMENT.ref(),
                    new AnnotationTarget.ChangeSet(new KonceptRef(HistoryWalkStampExtractor.stampOf(narrated), narrated.substring(0, 7))),
                    narrator, at, id);
            Annotation onOther = new Annotation(AnnotationKind.COMMENT.ref(),
                    new AnnotationTarget.ChangeSet(new KonceptRef(HistoryWalkStampExtractor.stampOf(elsewhere), elsewhere.substring(0, 7))),
                    narrator, at, UUID.fromString("6f3b6e1d-2c8f-5d4b-8e5a-1b2c3d4e5f60"));

            assertEquals("Comment on " + narrated.substring(0, 7), AnnotationCommit.subject(List.of(onDoc)));
            AnnotationCommit.Result result = AnnotationCommit.write(root, List.of(onDoc), "Alice added the first two lines.", false);
            assertTrue(result.ok(), result.output());
            assertTrue(AnnotationCommit.write(root, List.of(onOther), "Another file.", false).ok());

            try (GitRepo repo = GitRepo.open(root)) {
                List<CommitEvent.AnnotationEvent> comments = repo.stampHistoryOf(Path.of("doc.adoc")).events().stream()
                        .flatMap(event -> event.annotations().stream()).toList();
                assertEquals(1, comments.size(), "only the comment on a commit that changed doc.adoc");
                CommitEvent.AnnotationEvent comment = comments.getFirst();
                assertEquals(narrated, comment.reviewedSha(), "the commit it is about");
                assertTrue(comment.lines().isEmpty(), "on no lines");
                assertEquals("Alice added the first two lines.", comment.text());
                assertEquals(id, comment.annotation().id());
                assertEquals(narrator, comment.annotation().by());
                assertEquals(at, comment.annotation().at());
                assertEquals(AnnotationKind.COMMENT, comment.kind());
                assertTrue(repo.stampHistoryOf(Path.of("doc.adoc")).atHead().problems().isEmpty());
            }
        }
    }

    @Test
    void writeRefusesWhenTheIndexIsStaged() throws Exception {
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            Files.writeString(root.resolve("doc.adoc"), "one\n");
            git.add().addFilepattern("doc.adoc").call();
            git.commit().setAuthor("alice", "alice@example.test").setCommitter("alice", "alice@example.test").setMessage("add").call();
            Files.writeString(root.resolve("other.adoc"), "staged\n");
            git.add().addFilepattern("other.adoc").call();
            String head;
            try (GitRepo repo = GitRepo.open(root)) {
                head = repo.headRevision();
            }

            AnnotationCommit.Result result = AnnotationCommit.write(root,
                    List.of(Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("doc.adoc"), 1, 1, head, List.of("one")))), "", false);

            assertFalse(result.ok());
            assertTrue(result.output().contains("staged"), result.output());
        }
    }
}
