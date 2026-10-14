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
import design.knowledge.stamp.adoc.model.LineRange;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampRecord;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
