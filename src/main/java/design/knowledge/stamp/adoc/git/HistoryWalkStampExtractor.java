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

import design.knowledge.stamp.adoc.model.Person;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampHistory;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import design.knowledge.stamp.adoc.model.Status;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Level-2 STAMP extractor: walks the first-parent history of one file from
 * HEAD, following renames, then replays each commit's edits oldest-first over
 * a per-line attribution so that every line of the HEAD snapshot names the
 * STAMP that introduced it.
 *
 * <p>Unlike blame, the walk sees every transition, which is what later lets
 * {@code asOf(revision)} and withdrawal tracking land as lookups over the
 * same accumulated state. Commits listed in {@code .git-blame-ignore-revs}
 * (a formatting-only reflow, say) never take authorship: the lines they
 * touch inherit the attribution of the lines they replaced, positionally,
 * and a pure insertion inherits from the line above it.
 *
 * <p>v1 attributes along the first-parent chain: a merge that changes the
 * file is one STAMP of its own. Squash-merged feature work therefore lands
 * as the developer's STAMP, and a true merge commit as the merger's.
 */
final class HistoryWalkStampExtractor {

    private static final String IGNORE_REVS_FILE = ".git-blame-ignore-revs";

    private final Repository repository;
    private final Path repoRoot;
    private final Path file;

    HistoryWalkStampExtractor(Repository repository, Path repoRoot, Path file) {
        this.repository = repository;
        this.repoRoot = repoRoot;
        this.file = file;
    }

    /** One commit that touched the file, with the file's path as of that commit. */
    private record Touch(RevCommit commit, String pathAtCommit, List<Edit> edits, boolean ignored) { }

    /**
     * Compute the {@link StampHistory} for the configured file.
     *
     * @return a {@code StampHistory} whose {@link StampHistory#atHead()}
     *         reflects the accumulated state at HEAD
     */
    StampHistory extract() {
        Path relativeFile = file.isAbsolute() ? repoRoot.relativize(file) : file;
        String gitPath = relativeFile.toString().replace('\\', '/');
        try {
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null) {
                return history(relativeFile, new StampFile(relativeFile, "", Set.of(), Map.of()));
            }
            Set<String> ignored = ignoredRevs();
            List<Touch> touches = collectTouches(head, gitPath, ignored);
            Collections.reverse(touches);                       // oldest first
            List<StampRecord> replayed = replay(touches);
            int headLines = lineCount(head, gitPath);
            Map<StampId, StampRecord> stamps = fold(replayed, headLines);
            return history(relativeFile, new StampFile(relativeFile, head.name(), ignored, stamps));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static StampHistory history(Path relativeFile, StampFile atHead) {
        return new StampHistory() {
            @Override public Path file() { return relativeFile; }
            @Override public StampFile atHead() { return atHead; }
        };
    }

    // ── 1. the walk: which commits touched the file, under which name ──────────

    private List<Touch> collectTouches(ObjectId head, String gitPath, Set<String> ignored) throws IOException {
        List<Touch> touches = new ArrayList<>();
        DiffConfig diffConfig = repository.getConfig().get(DiffConfig.KEY);
        try (RevWalk walk = new RevWalk(repository);
             ObjectReader reader = repository.newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository);
            df.setDetectRenames(true);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            walk.setFirstParent(true);
            walk.setTreeFilter(FollowFilter.create(gitPath, diffConfig));
            walk.markStart(walk.parseCommit(head));
            String path = gitPath;                               // the name the file has at the commit being examined
            for (RevCommit commit : walk) {
                RevCommit parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0)) : null;
                List<DiffEntry> entries = parent == null
                        ? df.scan(new EmptyTreeIterator(), new CanonicalTreeParser(null, reader, commit.getTree()))
                        : df.scan(parent.getTree(), commit.getTree());
                DiffEntry entry = null;
                for (DiffEntry e : entries) {
                    if (path.equals(e.getNewPath())) { entry = e; break; }
                }
                if (entry == null) {
                    continue;                                   // the filter matched on content we do not track (should not happen)
                }
                List<Edit> edits = new ArrayList<>(df.toFileHeader(entry).toEditList());
                touches.add(new Touch(commit, path, edits, ignored.contains(commit.name())));
                if (entry.getChangeType() == DiffEntry.ChangeType.RENAME
                        || entry.getChangeType() == DiffEntry.ChangeType.COPY) {
                    path = entry.getOldPath();                   // older commits know the file by its earlier name
                }
            }
        }
        return touches;
    }

    // ── 2. the replay: oldest first, every line ends up naming one STAMP ───────

    /** Per-line attribution after replaying all touches; index i holds line i+1's record. */
    private List<StampRecord> replay(List<Touch> touches) {
        List<StampRecord> lines = new ArrayList<>();
        Map<String, StampRecord> bySha = new LinkedHashMap<>();
        for (Touch t : touches) {
            StampRecord stamp = bySha.computeIfAbsent(t.commit().name(), sha -> record(t.commit(), t.pathAtCommit()));
            List<StampRecord> next = new ArrayList<>(lines.size() + 16);
            int cursor = 0;                                      // next unconsumed line of the old version (0-based)
            for (Edit edit : t.edits()) {
                for (int i = cursor; i < edit.getBeginA(); i++) {
                    next.add(lines.get(i));                      // unchanged lines keep their STAMP
                }
                int newCount = edit.getEndB() - edit.getBeginB();
                for (int j = 0; j < newCount; j++) {
                    if (!t.ignored()) {
                        next.add(stamp);
                    } else {
                        next.add(inherit(lines, edit, j, next));
                    }
                }
                cursor = edit.getEndA();
            }
            for (int i = cursor; i < lines.size(); i++) {
                next.add(lines.get(i));
            }
            lines = next;
        }
        return lines;
    }

    /**
     * Attribution for the j-th new line of an edit made by an ignored commit:
     * the j-th replaced line's STAMP, else the last replaced line's, else the
     * line just above the edit, else the line just below it.
     */
    private static StampRecord inherit(List<StampRecord> old, Edit edit, int j, List<StampRecord> next) {
        int replaced = edit.getEndA() - edit.getBeginA();
        if (replaced > 0) {
            return old.get(edit.getBeginA() + Math.min(j, replaced - 1));
        }
        if (!next.isEmpty()) {
            return next.get(next.size() - 1);
        }
        if (edit.getBeginA() < old.size()) {
            return old.get(edit.getBeginA());
        }
        throw new IllegalStateException("ignored commit introduced the first content of the file: " + edit);
    }

    private StampRecord record(RevCommit commit, String pathAtCommit) {
        PersonIdent author = commit.getAuthorIdent();
        return new StampRecord(
                new StampId(commit.name()),
                commit.name(),
                Status.ACTIVE,
                author.getWhenAsInstant(),
                new Person(author.getName(), author.getEmailAddress()),
                Path.of(pathAtCommit),
                commit.getShortMessage(),
                new RoaringBitmap());
    }

    // ── 3. fold the per-line list into STAMP-keyed records with line bitmaps ──

    private static Map<StampId, StampRecord> fold(List<StampRecord> lines, int headLines) {
        if (lines.size() != headLines) {
            throw new IllegalStateException("replayed " + lines.size() + " lines but HEAD has " + headLines);
        }
        Map<StampId, StampRecord> stamps = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            StampRecord r = lines.get(i);
            stamps.computeIfAbsent(r.id(), id -> r).introduced().add(i + 1);   // 1-based line numbers
        }
        return stamps;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private int lineCount(ObjectId head, String gitPath) throws IOException {
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tw = TreeWalk.forPath(repository, gitPath, walk.parseCommit(head).getTree())) {
            if (tw == null) {
                throw new IllegalStateException("not in HEAD: " + gitPath);
            }
            byte[] bytes = repository.open(tw.getObjectId(0)).getBytes();
            return new RawText(bytes).size();
        }
    }

    private Set<String> ignoredRevs() throws IOException {
        Path list = repoRoot.resolve(IGNORE_REVS_FILE);
        Set<String> shas = new HashSet<>();
        if (!Files.isRegularFile(list)) {
            return shas;
        }
        for (String raw : Files.readAllLines(list)) {
            String token = raw.strip();
            int hash = token.indexOf('#');
            if (hash >= 0) {
                token = token.substring(0, hash).strip();
            }
            if (token.isEmpty()) {
                continue;
            }
            ObjectId id = repository.resolve(token);
            if (id != null) {
                shas.add(id.name());
            }
        }
        return shas;
    }
}
