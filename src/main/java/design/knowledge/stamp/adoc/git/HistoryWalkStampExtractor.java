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
import design.knowledge.stamp.adoc.model.StampHistory;
import org.eclipse.jgit.lib.Repository;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Level-2 STAMP extractor: walks the commit history backward from HEAD,
 * computes per-commit diffs against parents for the target file, and
 * accumulates per-line provenance into a {@link StampHistory}.
 *
 * <p>This is the substrate that makes time-travel (v1.1) and withdrawal /
 * cancellation tracking (v1.1+) additive rather than requiring a rewrite —
 * unlike blame, a history walk sees every transition including deletions.
 *
 * <p>v1 implementation is a skeleton — wiring forthcoming.
 */
final class HistoryWalkStampExtractor {

    private final Repository repository;
    private final Path repoRoot;
    private final Path file;

    HistoryWalkStampExtractor(Repository repository, Path repoRoot, Path file) {
        this.repository = repository;
        this.repoRoot = repoRoot;
        this.file = file;
    }

    /**
     * Compute the {@link StampHistory} for the configured file.
     *
     * <p>v1 implementation pending. The intended shape:
     * <ol>
     *   <li>Build a {@link org.eclipse.jgit.revwalk.RevWalk} from HEAD.</li>
     *   <li>For each commit, use {@link org.eclipse.jgit.diff.DiffFormatter}
     *       to diff the file against each parent.</li>
     *   <li>Accumulate per-line provenance into per-commit
     *       {@link design.knowledge.stamp.adoc.model.StampRecord} builders,
     *       backed by {@link org.roaringbitmap.RoaringBitmap}.</li>
     *   <li>Honor {@code .git-blame-ignore-revs} by skipping listed commits
     *       in the walk.</li>
     * </ol>
     *
     * @return a {@code StampHistory} whose {@link StampHistory#atHead()}
     *         reflects the accumulated state at HEAD
     */
    StampHistory extract() {
        // TODO: implement Level-2 history walk + per-commit diff accumulation.
        Path relativeFile = file.isAbsolute() ? repoRoot.relativize(file) : file;
        return new StampHistory() {
            @Override public Path file() { return relativeFile; }
            @Override public StampFile atHead() {
                return new StampFile(relativeFile, "TODO", Set.of(), Map.of());
            }
        };
    }
}
