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

import design.knowledge.stamp.adoc.model.StampHistory;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Top-level accessor for a local git repository.
 *
 * <p>Acts as the root of the {@code ike-git} capability family. v1 exposes
 * {@link #stampHistoryOf(Path)} as its sole capability. Future siblings —
 * {@code commitsTouching}, {@code identity}, {@code historyBetween}, etc. —
 * will land as additional methods without restructuring this surface.
 *
 * <p>{@code GitRepo} owns the underlying {@link Repository} and must be
 * closed when done.
 */
public final class GitRepo implements AutoCloseable {

    private final Path repoRoot;
    private final Repository repository;

    private GitRepo(Path repoRoot, Repository repository) {
        this.repoRoot = repoRoot;
        this.repository = repository;
    }

    /**
     * Open a git repository rooted at {@code repoRoot}.
     *
     * @param repoRoot working-tree root of an initialized git repository
     * @return a {@code GitRepo} ready for capability access; caller is
     *         responsible for {@link #close() closing} it
     * @throws IOException if the repository cannot be opened
     */
    public static GitRepo open(Path repoRoot) throws IOException {
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(repoRoot.resolve(".git").toFile())
                .readEnvironment()
                .findGitDir()
                .build();
        return new GitRepo(repoRoot, repo);
    }

    /**
     * Produce a time-traveling view of STAMP attribution for one file.
     *
     * <p>v1 implements the returned {@link StampHistory} via a Level-2 history
     * walk plus per-commit diff (see {@link HistoryWalkStampExtractor}).
     *
     * @param file path to the file within the repository's working tree;
     *             may be absolute or relative to {@link #repoRoot()}
     * @return a {@code StampHistory} backed by this repository
     */
    public StampHistory stampHistoryOf(Path file) {
        return new HistoryWalkStampExtractor(repository, repoRoot, file).extract();
    }

    /**
     * @return the working-tree root passed to {@link #open(Path)}
     */
    public Path repoRoot() {
        return repoRoot;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        repository.close();
    }
}
