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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Writes the empty commit that records annotations: the annotation text as
 * the body, one {@code Annotation:} trailer per target, signed by default.
 * The commit is made by the {@code git} command line rather than JGit, so the
 * signing the user has configured — GPG, SSH, a password manager's agent —
 * applies exactly as it does to every other commit they sign. A staged index
 * blocks the write, since anything staged would ride along with the
 * annotation.
 */
public final class AnnotationCommit {

    private static final int TIMEOUT_SECONDS = 120;

    private AnnotationCommit() { }

    /**
     * The outcome of a commit attempt.
     *
     * @param ok     whether git exited 0
     * @param sha    the new commit's SHA when {@code ok}, else empty
     * @param output git's combined output, for the user when it failed
     */
    public record Result(boolean ok, String sha, String output) { }

    /**
     * The commit subject: the kind's verb when every annotation shares one
     * known kind, else {@code Annotate}, then the file and its ranges when every
     * target is a line range of one file.
     *
     * @param annotations one or more annotations
     * @return the subject, e.g. {@code Affirm topics/x.adoc 3-5, 9-9}
     */
    public static String subject(List<Annotation> annotations) {
        List<Optional<AnnotationKind>> kinds = annotations.stream().map(Annotation::knownKind).distinct().toList();
        String verb = kinds.size() == 1 && kinds.get(0).isPresent() ? kinds.get(0).get().verb() : "Annotate";
        List<LineRange> ranges = annotations.stream().flatMap(a -> a.lineRange().stream()).toList();
        if (ranges.size() == annotations.size() && ranges.stream().map(LineRange::gitPath).distinct().count() == 1) {
            return verb + " " + ranges.get(0).gitPath() + " "
                    + ranges.stream().map(r -> r.from() + "-" + r.to()).collect(Collectors.joining(", "));
        }
        return verb + " " + annotations.size() + " target(s)";
    }

    /**
     * The git arguments, without the executable.
     *
     * @param annotations the annotations
     * @param text        the annotation text, the commit body; blank for none
     * @param sign        whether to pass {@code -S}
     * @return the argument list
     */
    public static List<String> arguments(List<Annotation> annotations, String text, boolean sign) {
        List<String> args = new ArrayList<>(List.of("commit", "--allow-empty", "--quiet"));
        // Explicit either way: a global commit.gpgsign=true must not sign an "unsigned" annotation behind the user's back.
        args.add(sign ? "-S" : "--no-gpg-sign");
        args.add("-m");
        args.add(subject(annotations));
        if (text != null && !text.isBlank()) {
            args.add("-m");
            args.add(text.strip());
        }
        for (Annotation a : annotations) {
            args.add("--trailer");
            args.add(a.trailer());
        }
        return args;
    }

    /**
     * Write the annotation commit at the repository's HEAD.
     *
     * @param repoRoot    working-tree root
     * @param annotations the annotations
     * @param text        the annotation text; blank for none
     * @param sign        whether to sign the commit
     * @return the outcome
     * @throws IOException if the index cannot be read or git cannot be started
     */
    public static Result write(Path repoRoot, List<Annotation> annotations, String text, boolean sign) throws IOException {
        if (annotations.isEmpty()) {
            throw new IllegalArgumentException("nothing to annotate");
        }
        if (indexHasStagedChanges(repoRoot)) {
            return new Result(false, "", "The index has staged changes. Commit or unstage them first, so the annotation commit records nothing but the annotation.");
        }
        GitCli.Run run = GitCli.run(repoRoot, TIMEOUT_SECONDS, arguments(annotations, text, sign));
        if (!run.ok()) {
            return new Result(false, "", run.output().isEmpty() ? "git commit exited " + run.exit() : run.output());
        }
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            return new Result(true, repo.headRevision(), run.output());
        }
    }

    /** Whether anything is staged: added, changed or removed in the index relative to HEAD. */
    static boolean indexHasStagedChanges(Path repoRoot) throws IOException {
        try (GitRepo repo = GitRepo.open(repoRoot); Git git = new Git(repo.repository())) {
            Status status = git.status().call();
            return !status.getAdded().isEmpty() || !status.getChanged().isEmpty() || !status.getRemoved().isEmpty();
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }
}
