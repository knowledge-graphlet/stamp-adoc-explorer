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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Writes the empty commit that records affirmations: one {@code Affirms:}
 * trailer per range, signed by default. The commit is made by the
 * {@code git} command line rather than JGit, so the signing the user has
 * configured — GPG, SSH, a password manager's agent — applies exactly as
 * it does to every other commit they sign. A staged index blocks the
 * write, since anything staged would ride along with the affirmation.
 */
public final class AffirmationCommit {

    private static final List<String> GIT_CANDIDATES = List.of("git", "/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git");
    private static final int TIMEOUT_SECONDS = 120;

    private AffirmationCommit() { }

    /**
     * The outcome of a commit attempt.
     *
     * @param ok     whether git exited 0
     * @param sha    the new commit's SHA when {@code ok}, else empty
     * @param output git's combined output, for the user when it failed
     */
    public record Result(boolean ok, String sha, String output) { }

    /**
     * @param affirmations one or more ranges, all of the same file
     * @return the commit subject, {@code Affirm <file> <from-to>, ...}
     */
    public static String subject(List<Affirmation> affirmations) {
        return "Affirm " + affirmations.get(0).file().toString().replace('\\', '/') + " "
                + affirmations.stream().map(a -> a.from() + "-" + a.to()).collect(Collectors.joining(", "));
    }

    /**
     * The git arguments, without the executable.
     *
     * @param affirmations the ranges
     * @param sign         whether to pass {@code -S}
     * @return the argument list
     */
    public static List<String> arguments(List<Affirmation> affirmations, boolean sign) {
        List<String> args = new ArrayList<>(List.of("commit", "--allow-empty", "--quiet"));
        // Explicit either way: a global commit.gpgsign=true must not sign an "unsigned" affirmation behind the user's back.
        args.add(sign ? "-S" : "--no-gpg-sign");
        args.add("-m");
        args.add(subject(affirmations));
        for (Affirmation a : affirmations) {
            args.add("--trailer");
            args.add(a.trailer());
        }
        return args;
    }

    /**
     * Write the affirmation commit at the repository's HEAD.
     *
     * @param repoRoot     working-tree root
     * @param affirmations the ranges, all of one file
     * @param sign         whether to sign the commit
     * @return the outcome
     * @throws IOException if the index cannot be read or git cannot be started
     */
    public static Result write(Path repoRoot, List<Affirmation> affirmations, boolean sign) throws IOException {
        if (affirmations.isEmpty()) {
            throw new IllegalArgumentException("nothing to affirm");
        }
        if (indexHasStagedChanges(repoRoot)) {
            return new Result(false, "", "The index has staged changes. Commit or unstage them first, so the affirmation commit records nothing but the trailers.");
        }
        List<String> command = new ArrayList<>();
        command.add(gitExecutable());
        command.addAll(arguments(affirmations, sign));
        Process process = new ProcessBuilder(command).directory(repoRoot.toFile()).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "", "git commit did not finish within " + TIMEOUT_SECONDS + " seconds (a signing prompt waiting?)" + (output.isEmpty() ? "" : "\n" + output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(false, "", "interrupted while waiting for git");
        }
        if (process.exitValue() != 0) {
            return new Result(false, "", output.isEmpty() ? "git commit exited " + process.exitValue() : output);
        }
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            return new Result(true, repo.headRevision(), output);
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

    private static String gitExecutable() {
        for (String candidate : GIT_CANDIDATES) {
            if (candidate.contains("/") ? Files.isExecutable(Path.of(candidate)) : onPath(candidate)) {
                return candidate;
            }
        }
        return "git";
    }

    private static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }
}
