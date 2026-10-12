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
import design.knowledge.stamp.adoc.model.Person;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampHistory;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import design.knowledge.stamp.adoc.model.Status;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Level-2 STAMP extractor: walks the first-parent history of one file from
 * HEAD, following renames, then replays each commit's edits oldest-first
 * over a per-line attribution so that every line of the HEAD snapshot names
 * the STAMP that introduced it and the STAMPs that affirmed it.
 *
 * <p>Unlike blame, the walk sees every transition, which is what later lets
 * {@code asOf(revision)} and withdrawal tracking land as lookups over the
 * same accumulated state. Commits listed in {@code .git-blame-ignore-revs}
 * (a formatting-only reflow, say) never take authorship: the lines they
 * touch inherit the attribution of the lines they replaced, positionally,
 * and a pure insertion inherits from the line above it.
 *
 * <p>Affirmations are {@code Affirms: path:from-to@rev#hash} trailers on
 * any first-parent commit, typically an empty signed one. Each is checked
 * against the file as committed at {@code rev}; one whose hash no longer
 * matches is reported, never applied. The affirmed lines are mapped from
 * {@code rev} to the file as it stood when the affirmation was made, and
 * from there they ride the replay like any other line: a later edit of an
 * affirmed line drops its affirmation, an edit elsewhere leaves it alone.
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

    /** One commit that changed the file, with the file's path and content as of that commit. */
    private record Touch(RevCommit commit, String pathAtCommit, List<Edit> edits, RawText text, boolean ignored) { }

    /** One trailer on one commit. */
    private record Affirm(RevCommit commit, Affirmation affirmation) { }

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
                return history(relativeFile, new StampFile(relativeFile, "", Set.of(), Map.of(), List.of()));
            }
            Set<String> ignored = ignoredRevs();
            List<String> problems = new ArrayList<>();
            Map<String, Integer> chain = new HashMap<>();                 // commit → distance from HEAD along first parents
            Map<Integer, List<Affirm>> affirms = new HashMap<>();
            walkChain(head, chain, affirms, problems);
            Map<Integer, Touch> touches = new TreeMap<>((a, b) -> Integer.compare(b, a));   // oldest (farthest) first
            for (Touch t : collectTouches(head, gitPath, ignored)) {
                touches.put(chain.get(t.commit().name()), t);
            }
            Replay replay = new Replay();
            List<Integer> steps = new ArrayList<>(touches.keySet());
            for (Integer index : affirms.keySet()) {
                if (!touches.containsKey(index)) {
                    steps.add(index);
                }
            }
            steps.sort((a, b) -> Integer.compare(b, a));
            for (int index : steps) {
                Touch t = touches.get(index);
                if (t != null) {
                    replay.apply(t);
                }
                for (Affirm a : affirms.getOrDefault(index, List.of())) {
                    applyAffirmation(replay, a, problems);
                }
            }
            int headLines = lineCount(head, gitPath);
            Map<StampId, StampRecord> stamps = replay.fold(headLines);
            return history(relativeFile, new StampFile(relativeFile, head.name(), ignored, stamps, List.copyOf(problems)));
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

    // ── 1a. the chain: every first-parent commit, numbered from HEAD, with its trailers ──

    private void walkChain(ObjectId head, Map<String, Integer> chain, Map<Integer, List<Affirm>> affirms, List<String> problems)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(head));
            int index = 0;
            for (RevCommit commit : walk) {
                chain.put(commit.name(), index);
                for (String value : commit.getFooterLines(Affirmation.TRAILER)) {
                    try {
                        affirms.computeIfAbsent(index, k -> new ArrayList<>()).add(new Affirm(commit, Affirmation.parse(value)));
                    } catch (IllegalArgumentException e) {
                        problems.add(commit.abbreviate(7).name() + ": " + e.getMessage());
                    }
                }
                index++;
            }
        }
    }

    // ── 1b. the touches: which commits changed the file, under which name ──────

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
                    continue;
                }
                List<Edit> edits = new ArrayList<>(df.toFileHeader(entry).toEditList());
                RawText text = new RawText(repository.open(entry.getNewId().toObjectId()).getBytes());
                touches.add(new Touch(commit, path, edits, text, ignored.contains(commit.name())));
                if (entry.getChangeType() == DiffEntry.ChangeType.RENAME
                        || entry.getChangeType() == DiffEntry.ChangeType.COPY) {
                    path = entry.getOldPath();                   // older commits know the file by its earlier name
                }
            }
        }
        return touches;
    }

    // ── 2. the replay: oldest first, every line ends up naming its STAMPs ──────

    /** Per-line state: who introduced the line, who has affirmed it since, and the text itself. */
    private final class Replay {
        private List<StampRecord> intro = new ArrayList<>();
        private List<List<StampRecord>> affirmers = new ArrayList<>();
        private RawText text = new RawText(new byte[0]);
        private final Map<String, StampRecord> bySha = new LinkedHashMap<>();

        /** Where a line written by an ignored commit takes its attribution from. */
        private record Source(boolean fromOld, int index) { }

        void apply(Touch t) {
            StampRecord stamp = bySha.computeIfAbsent(t.commit().name(),
                    sha -> record(t.commit(), t.pathAtCommit(), Status.ACTIVE));
            List<StampRecord> nextIntro = new ArrayList<>(intro.size() + 16);
            List<List<StampRecord>> nextAffirmers = new ArrayList<>(intro.size() + 16);
            int cursor = 0;                                      // next unconsumed line of the old version (0-based)
            for (Edit edit : t.edits()) {
                for (int i = cursor; i < edit.getBeginA(); i++) {
                    nextIntro.add(intro.get(i));                 // unchanged lines keep their STAMPs
                    nextAffirmers.add(affirmers.get(i));
                }
                int newCount = edit.getEndB() - edit.getBeginB();
                for (int j = 0; j < newCount; j++) {
                    if (!t.ignored()) {
                        nextIntro.add(stamp);
                        nextAffirmers.add(null);                 // an edited line is a new claim: unaffirmed
                    } else {
                        Source from = inherit(intro.size(), edit, j, nextIntro.size());
                        nextIntro.add(from.fromOld() ? intro.get(from.index()) : nextIntro.get(from.index()));
                        nextAffirmers.add(from.fromOld() ? affirmers.get(from.index()) : nextAffirmers.get(from.index()));
                    }
                }
                cursor = edit.getEndA();
            }
            for (int i = cursor; i < intro.size(); i++) {
                nextIntro.add(intro.get(i));
                nextAffirmers.add(affirmers.get(i));
            }
            intro = nextIntro;
            affirmers = nextAffirmers;
            text = t.text();
        }

        /**
         * Attach {@code record} to the current lines that {@code revLines}
         * [from, to] of {@code revText} map to.
         *
         * @return how many lines received the affirmation
         */
        int affirm(StampRecord record, RawText revText, int from, int to) {
            if (text.size() == 0) {
                return 0;                                        // the file is not in the chain yet
            }
            int[] map = lineMap(revText, text);
            int applied = 0;
            for (int line = from - 1; line <= to - 1 && line < map.length; line++) {
                int current = map[line];
                if (current >= 0) {
                    if (affirmers.get(current) == null) {
                        affirmers.set(current, new ArrayList<>(2));
                    }
                    if (!affirmers.get(current).contains(record)) {
                        affirmers.get(current).add(record);
                    }
                    applied++;
                }
            }
            return applied;
        }

        StampRecord recordFor(RevCommit commit, String path) {
            return bySha.computeIfAbsent(commit.name(), sha -> record(commit, path, Status.AFFIRMED));
        }

        /** Fold the per-line lists into STAMP-keyed records with line bitmaps. */
        Map<StampId, StampRecord> fold(int headLines) {
            if (intro.size() != headLines) {
                throw new IllegalStateException("replayed " + intro.size() + " lines but HEAD has " + headLines);
            }
            Map<StampId, StampRecord> stamps = new LinkedHashMap<>();
            for (int i = 0; i < intro.size(); i++) {
                StampRecord r = intro.get(i);
                stamps.computeIfAbsent(r.id(), id -> r).introduced().add(i + 1);      // 1-based line numbers
                List<StampRecord> who = affirmers.get(i);
                if (who != null) {
                    for (StampRecord a : who) {
                        stamps.computeIfAbsent(a.id(), id -> a).affirmed().add(i + 1);
                    }
                }
            }
            return stamps;
        }

        /**
         * Attribution source for the j-th new line of an edit made by an
         * ignored commit: the j-th replaced line, else the last replaced
         * line, else the line just above the edit, else the line just below.
         */
        private Source inherit(int oldSize, Edit edit, int j, int nextSize) {
            int replaced = edit.getEndA() - edit.getBeginA();
            if (replaced > 0) {
                return new Source(true, edit.getBeginA() + Math.min(j, replaced - 1));
            }
            if (nextSize > 0) {
                return new Source(false, nextSize - 1);
            }
            if (edit.getBeginA() < oldSize) {
                return new Source(true, edit.getBeginA());
            }
            throw new IllegalStateException("ignored commit introduced the first content of the file: " + edit);
        }
    }

    private void applyAffirmation(Replay replay, Affirm a, List<String> problems) throws IOException {
        Affirmation affirmation = a.affirmation();
        String where = a.commit().abbreviate(7).name() + " " + affirmation.value();
        ObjectId rev;
        try {
            rev = repository.resolve(affirmation.revision());
        } catch (IOException e) {
            problems.add(where + ": revision " + affirmation.revision() + " " + e.getMessage());
            return;
        }
        if (rev == null) {
            problems.add(where + ": unknown revision " + affirmation.revision());
            return;
        }
        RawText revText = blobAt(rev, affirmation.file().toString().replace('\\', '/'));
        if (revText == null) {
            problems.add(where + ": " + affirmation.file() + " is not committed at " + affirmation.revision());
            return;
        }
        if (!affirmation.matches(lines(revText))) {
            problems.add(where + ": hash does not match the lines at " + affirmation.revision());
            return;
        }
        replay.affirm(replay.recordFor(a.commit(), affirmation.file().toString()), revText, affirmation.from(), affirmation.to());
    }

    /**
     * For each 0-based line of {@code a}, the 0-based line of {@code b}
     * holding the same unchanged line, or -1 where {@code b} changed it.
     */
    static int[] lineMap(RawText a, RawText b) {
        int[] map = new int[a.size()];
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);
        int ia = 0;
        int ib = 0;
        for (Edit e : edits) {
            while (ia < e.getBeginA()) {
                map[ia++] = ib++;
            }
            while (ia < e.getEndA()) {
                map[ia++] = -1;
            }
            ib = e.getEndB();
        }
        while (ia < a.size()) {
            map[ia++] = ib++;
        }
        return map;
    }

    private StampRecord record(RevCommit commit, String pathAtCommit, Status status) {
        PersonIdent author = commit.getAuthorIdent();
        return new StampRecord(
                new StampId(commit.name()),
                commit.name(),
                status,
                author.getWhenAsInstant(),
                new Person(author.getName(), author.getEmailAddress()),
                Path.of(pathAtCommit),
                commit.getShortMessage(),
                new RoaringBitmap(),
                new RoaringBitmap(),
                commit.getRawGpgSignature() != null);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RawText blobAt(ObjectId commitId, String gitPath) throws IOException {
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tw = TreeWalk.forPath(repository, gitPath, walk.parseCommit(commitId).getTree())) {
            return tw == null ? null : new RawText(repository.open(tw.getObjectId(0)).getBytes());
        }
    }

    private static List<String> lines(RawText text) {
        List<String> lines = new ArrayList<>(text.size());
        for (int i = 0; i < text.size(); i++) {
            lines.add(text.getString(i));
        }
        return lines;
    }

    private int lineCount(ObjectId head, String gitPath) throws IOException {
        RawText text = blobAt(head, gitPath);
        if (text == null) {
            throw new IllegalStateException("not in HEAD: " + gitPath);
        }
        return text.size();
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
