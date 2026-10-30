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

import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import design.knowledge.stamp.adoc.dialect.Segmenter;
import design.knowledge.stamp.adoc.dialect.TextPairing;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.LineRange;
import design.knowledge.stamp.adoc.model.CommitEvent;
import design.knowledge.stamp.adoc.model.LineId;
import design.knowledge.stamp.adoc.model.SegmentId;
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
 * the STAMP that introduced it and the STAMPs that annotated it.
 *
 * <p>Unlike blame, the walk sees every transition, which is what later lets
 * {@code asOf(revision)} and withdrawal tracking land as lookups over the
 * same accumulated state. Commits listed in {@code .git-blame-ignore-revs}
 * (a formatting-only reflow, say) never take authorship: the lines they
 * touch inherit the attribution of the lines they replaced, positionally,
 * and a pure insertion inherits from the line above it.
 *
 * <p><b>A line keeps its identity across a revision</b>
 * (IKE-Network/ike-issues#1313). Within each diff hunk, the old and new lines
 * pair off in order, as Komet's own block editor pairs them: a paired line is
 * the same line with new text, re-texted under the commit that revised it,
 * so its history is its versions. What is left over is introduced or
 * retired. Text removed in one place and added unchanged in another by the
 * same commit is the same line moved; a line with no letter or digit, a
 * blank line or a delimiter, is too common to be followed that way. A
 * revised line still drops its annotations here, since a judgment is about
 * the text the annotator read; a moved line keeps them.
 *
 * <p><b>Sentences and inline elements have identities too</b> (IKE-Network/ike-issues#1313;
 * {@code design-2026-10-21-stored-text-segments}). After each commit the file is segmented
 * ({@link Segmenter}) and its sentences paired with the previous commit's by {@link TextPairing}:
 * equal, moved, revised by word overlap, or the positional residue; the inline elements likewise,
 * a revision only within its kind and its sentence. Sentences cross diff hunks, so they are paired
 * over the whole file, never per hunk, and a reflow, which changes lines but no sentence's text,
 * leaves every sentence's identity alone.
 *
 * <p>Annotations are {@code Annotation: <kind> path:from-to@rev#hash}
 * trailers on any first-parent commit, typically an empty signed one: an
 * affirmation, a refutation, or a comment ({@link Annotation}). Each is
 * checked against the file as committed at {@code rev}; one whose hash no
 * longer matches, or whose kind is unknown, is reported, never applied. The
 * annotated lines are mapped from {@code rev} to the file as it stood when
 * the annotation was made, and from there they ride the replay like any
 * other line: a later edit of an annotated line drops its annotations, an
 * edit elsewhere leaves them alone. A later judgment by the same annotator
 * on the same line supersedes an earlier one, so affirming and then
 * refuting reads as refuted. A retraction ({@code Annotation: <kind> @<sha>})
 * withdraws every annotation an earlier commit recorded on this file: their
 * marks leave the lines, and nothing the retracted annotation had superseded
 * comes back, since a retraction takes back only its target. Trailers about
 * other files, or about graph components, are not this walk's concern and are
 * skipped; so is a retraction of a commit that annotated only other files.
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

    /**
     * One annotation trailer on one commit, with its position among the commit's trailers and the
     * commit body: a line range of this file, or, for a retraction, the annotations of this file the
     * retracted commit recorded ({@code range} is then null).
     */
    private record Annot(RevCommit commit, int index, Annotation annotation, AnnotationKind kind, LineRange range, String text,
                         List<CommitEvent.AnnotationRef> retracts) { }

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
                StampFile empty = new StampFile(relativeFile, "", Set.of(), Map.of(), List.of());
                return new Walked(relativeFile, empty, List.of(), Map.of(), List.of(), Set.of(), List.of());
            }
            Set<String> ignored = ignoredRevs();
            List<String> problems = new ArrayList<>();
            Map<String, Integer> chain = new HashMap<>();                 // commit → distance from HEAD along first parents
            Map<Integer, List<Annot>> annots = new HashMap<>();
            Map<Integer, Touch> touches = new TreeMap<>((a, b) -> Integer.compare(b, a));   // oldest (farthest) first
            List<Touch> touched = collectTouches(head, gitPath, ignored);
            Set<String> names = new HashSet<>();                 // every path the file has had
            for (Touch t : touched) {
                names.add(t.pathAtCommit());
            }
            walkChain(head, chain, annots, names, problems);
            for (Touch t : touched) {
                touches.put(chain.get(t.commit().name()), t);
            }
            Replay replay = new Replay();
            List<Integer> steps = new ArrayList<>(touches.keySet());
            for (Integer index : annots.keySet()) {
                if (!touches.containsKey(index)) {
                    steps.add(index);
                }
            }
            steps.sort((a, b) -> Integer.compare(b, a));
            List<Step> snapshots = new ArrayList<>();
            List<CommitEvent> events = new ArrayList<>();
            for (int index : steps) {
                Touch t = touches.get(index);
                RevCommit commit = t != null ? t.commit() : annots.get(index).get(0).commit();
                String pathAtCommit = t != null ? t.pathAtCommit() : replay.lastPath;
                Replay.Delta delta = t != null ? replay.apply(t) : Replay.Delta.none();
                CommitEvent.Segments segments = t != null ? replay.segment(t) : replay.segmentsUnchanged();
                List<CommitEvent.AnnotationEvent> applied = new ArrayList<>();
                for (Annot a : annots.getOrDefault(index, List.of())) {
                    List<SegmentId> sentences = new ArrayList<>();
                    List<LineId> lines = applyAnnotation(replay, a, chain, touches, problems, sentences);
                    if (lines != null) {
                        applied.add(new CommitEvent.AnnotationEvent(a.annotation(), a.index(), a.kind(), lines,
                                a.range() == null ? a.retracts().getFirst().commitSha() : reviewedSha(a.range(), chain, touches),
                                a.text(), sentences, a.retracts()));
                    }
                }
                events.add(new CommitEvent(commitOf(commit), pathAtCommit, t != null && t.ignored(),
                        delta.introduced(), delta.retexted(), delta.retired(), List.copyOf(replay.ids), applied, segments));
                snapshots.add(replay.snapshot(index, commit.name()));
            }
            int headLines = lineCount(head, gitPath);
            if (replay.intro.size() != headLines) {
                throw new IllegalStateException("replayed " + replay.intro.size() + " lines but HEAD has " + headLines);
            }
            StampFile atHead = Replay.fold(relativeFile, head.name(), ignored, snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1), problems);
            return new Walked(relativeFile, atHead, snapshots, chain, problems, ignored, events);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CommitEvent.Commit commitOf(RevCommit commit) {
        PersonIdent author = commit.getAuthorIdent();
        return new CommitEvent.Commit(commit.name(), author.getWhenAsInstant(),
                new Person(author.getName(), author.getEmailAddress()), commit.getShortMessage(), commit.getRawGpgSignature() != null);
    }

    /** One replay state as it stood after a chain step, newest last. */
    private record Step(int chainIndex, String sha, List<StampRecord> intro, List<List<Mark>> marks, List<LineId> ids) { }

    /**
     * One annotation on one line: the annotating commit's record, the kind, and
     * who annotated — the {@code by} author's PublicId when the trailer names
     * one, else the commit author's email — so a later judgment by the same
     * annotator can supersede it.
     */
    private record Mark(StampRecord record, AnnotationKind kind, String annotator) { }

    /** The walked history: snapshots per step, the chain for asOf, and the events. */
    private final class Walked implements StampHistory {
        private final Path relativeFile;
        private final StampFile atHead;
        private final List<Step> snapshots;
        private final Map<String, Integer> chain;
        private final List<String> problems;
        private final Set<String> ignored;
        private final List<CommitEvent> events;

        Walked(Path relativeFile, StampFile atHead, List<Step> snapshots, Map<String, Integer> chain, List<String> problems,
               Set<String> ignored, List<CommitEvent> events) {
            this.relativeFile = relativeFile;
            this.atHead = atHead;
            this.snapshots = snapshots;
            this.chain = chain;
            this.problems = problems;
            this.ignored = ignored;
            this.events = List.copyOf(events);
        }

        @Override public Path file() { return relativeFile; }
        @Override public StampFile atHead() { return atHead; }
        @Override public List<CommitEvent> events() { return events; }

        @Override
        public StampFile asOf(String revision) {
            ObjectId id;
            try {
                id = repository.resolve(revision);
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot resolve " + revision + ": " + e.getMessage(), e);
            }
            if (id == null) {
                throw new IllegalArgumentException("unknown revision " + revision);
            }
            Integer index = chain.get(id.name());
            if (index == null) {
                throw new IllegalArgumentException(revision + " is not on HEAD's first-parent chain");
            }
            Step at = null;
            for (Step step : snapshots) {                        // oldest first; the last step at or before the revision wins
                if (step.chainIndex() >= index) {
                    at = step;
                }
            }
            return Replay.fold(relativeFile, id.name(), ignored, at, problems);
        }
    }

    // ── 1a. the chain: every first-parent commit, numbered from HEAD, with its trailers ──

    private void walkChain(ObjectId head, Map<String, Integer> chain, Map<Integer, List<Annot>> annots, Set<String> names,
                           List<String> problems) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(head));
            int index = 0;
            for (RevCommit commit : walk) {
                chain.put(commit.name(), index);
                List<String> values = commit.getFooterLines(Annotation.TRAILER);
                for (int position = 0; position < values.size(); position++) {
                    Annotation parsed;
                    try {
                        parsed = Annotation.parse(values.get(position));   // parse before touching the map: a malformed trailer must leave no empty step
                    } catch (IllegalArgumentException e) {
                        problems.add(commit.abbreviate(7).name() + ": " + e.getMessage());
                        continue;
                    }
                    AnnotationKind kind = parsed.knownKind().orElse(null);
                    if (parsed.target() instanceof AnnotationTarget.Commit retracted) {
                        String where = commit.abbreviate(7).name() + " " + parsed.value();
                        if (kind != AnnotationKind.RETRACTION) {
                            if (kind == null) {
                                problems.add(where + ": unknown annotation kind " + parsed.kind());
                            } else {
                                problems.add(where + ": only a retraction names a commit");
                            }
                            continue;
                        }
                        List<CommitEvent.AnnotationRef> retracts = annotationsOf(retracted, names, problems, where);
                        if (retracts != null && !retracts.isEmpty()) {
                            annots.computeIfAbsent(index, k -> new ArrayList<>())
                                    .add(new Annot(commit, position, parsed, kind, null, bodyOf(commit), retracts));
                        }
                        continue;                                // about another file's annotations, or reported
                    }
                    if (!(parsed.target() instanceof LineRange range) || !names.contains(range.gitPath())) {
                        continue;                                // about another file, or about the graph
                    }
                    if (kind == null) {
                        problems.add(commit.abbreviate(7).name() + " " + parsed.value() + ": unknown annotation kind " + parsed.kind());
                        continue;
                    }
                    if (kind == AnnotationKind.RETRACTION) {
                        problems.add(commit.abbreviate(7).name() + " " + parsed.value() + ": a retraction names a commit, not lines");
                        continue;
                    }
                    annots.computeIfAbsent(index, k -> new ArrayList<>())
                            .add(new Annot(commit, position, parsed, kind, range, bodyOf(commit), List.of()));
                }
                index++;
            }
        }
    }

    /**
     * The annotations of this file an earlier commit recorded, as a retraction's targets: one
     * reference per {@code Annotation:} trailer whose line range names one of the file's paths.
     *
     * @return the references; empty when the commit annotated only other files; null, with a
     *         problem reported, when the revision is unknown or the commit recorded no annotation
     */
    private List<CommitEvent.AnnotationRef> annotationsOf(AnnotationTarget.Commit retracted, Set<String> names, List<String> problems,
                                                          String where) {
        ObjectId id;
        try {
            id = repository.resolve(retracted.revision());
        } catch (IOException | RuntimeException e) {
            problems.add(where + ": revision " + retracted.revision() + " " + e.getMessage());
            return null;
        }
        if (id == null) {
            problems.add(where + ": unknown revision " + retracted.revision());
            return null;
        }
        RevCommit target;
        try (RevWalk walk = new RevWalk(repository)) {
            target = walk.parseCommit(id);
        } catch (IOException e) {
            problems.add(where + ": " + retracted.revision() + " is not a commit");
            return null;
        }
        List<String> values = target.getFooterLines(Annotation.TRAILER);
        if (values.isEmpty()) {
            problems.add(where + ": " + retracted.shortRevision() + " recorded no annotation");
            return null;
        }
        List<CommitEvent.AnnotationRef> refs = new ArrayList<>();
        for (int position = 0; position < values.size(); position++) {
            Annotation parsed;
            try {
                parsed = Annotation.parse(values.get(position));
            } catch (IllegalArgumentException e) {
                continue;                                        // reported where that commit is walked
            }
            if (parsed.target() instanceof LineRange range && names.contains(range.gitPath())) {
                refs.add(new CommitEvent.AnnotationRef(target.name(), position, parsed.id()));
            }
        }
        return refs;
    }

    /**
     * The commit message without its subject and its trailer block: the text an
     * annotation commit says about what it annotates.
     */
    static String bodyOf(RevCommit commit) {
        String[] paragraphs = commit.getFullMessage().strip().split("\\n\\s*\\n");
        int end = paragraphs.length;
        if (end > 1 && paragraphs[end - 1].lines().allMatch(l -> l.matches("[A-Za-z][A-Za-z0-9-]*:\\s.*"))) {
            end--;                                               // the trailer block
        }
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < end; i++) {
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append(paragraphs[i].strip());
        }
        return body.toString();
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

    /** Per-line state: who introduced the line, who has annotated it since, its identity, and the text itself. */
    private final class Replay {
        private List<StampRecord> intro = new ArrayList<>();
        private List<List<Mark>> marks = new ArrayList<>();
        private List<LineId> ids = new ArrayList<>();
        private RawText text = new RawText(new byte[0]);
        private String lastPath = "";
        private final Map<String, StampRecord> bySha = new LinkedHashMap<>();
        private List<SegmentId> sentenceIds = List.of();
        private List<String> sentenceTexts = List.of();
        private List<SegmentId> inlineIds = List.of();
        private List<String> inlineSignatures = List.of();
        private List<String> inlineKinds = List.of();
        private List<SegmentId> inlineSentences = List.of();     // the sentence each inline element is in

        /** What a touch did, for the event stream. */
        record Delta(List<CommitEvent.Line> introduced, List<CommitEvent.Line> retexted, List<LineId> retired) {
            static Delta none() { return new Delta(List.of(), List.of(), List.of()); }
        }

        /** Whether a line's text is distinctive enough to follow as a move: it has a letter or a digit. */
        private static boolean significant(String line) {
            for (int i = 0; i < line.length(); i++) {
                if (Character.isLetterOrDigit(line.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        /** Where a line written by an ignored commit takes its attribution from. */
        private record Source(boolean fromOld, int index) { }

        Delta apply(Touch t) {
            StampRecord stamp = bySha.computeIfAbsent(t.commit().name(),
                    sha -> record(t.commit(), t.pathAtCommit(), Status.ACTIVE));
            List<StampRecord> nextIntro = new ArrayList<>(intro.size() + 16);
            List<List<Mark>> nextMarks = new ArrayList<>(intro.size() + 16);
            List<LineId> nextIds = new ArrayList<>(intro.size() + 16);
            List<CommitEvent.Line> introduced = new ArrayList<>();
            List<CommitEvent.Line> retexted = new ArrayList<>();
            Set<LineId> survivors = new HashSet<>();
            int cursor = 0;                                      // next unconsumed line of the old version (0-based)
            for (Edit edit : t.edits()) {
                for (int i = cursor; i < edit.getBeginA(); i++) {
                    nextIntro.add(intro.get(i));                 // unchanged lines keep their STAMPs and identity
                    nextMarks.add(marks.get(i));
                    nextIds.add(ids.get(i));
                    survivors.add(ids.get(i));
                }
                int newCount = edit.getEndB() - edit.getBeginB();
                int replaced = edit.getEndA() - edit.getBeginA();
                for (int j = 0; j < newCount; j++) {
                    String newText = t.text().getString(edit.getBeginB() + j);
                    if (j >= replaced) {
                        LineId id = new LineId(t.commit().name(), t.pathAtCommit(), nextIds.size() + 1);
                        if (!t.ignored()) {
                            nextIntro.add(stamp);
                            nextMarks.add(null);                 // an edited line is a new claim: unannotated
                        } else {
                            Source from = inherit(intro.size(), edit, j, nextIntro.size());
                            nextIntro.add(from.fromOld() ? intro.get(from.index()) : nextIntro.get(from.index()));
                            nextMarks.add(from.fromOld() ? marks.get(from.index()) : nextMarks.get(from.index()));
                        }
                        nextIds.add(id);
                        introduced.add(new CommitEvent.Line(id, newText));
                    } else {
                        // A replaced line paired in order is the same line with new text: it keeps its
                        // identity. An ignored commit leaves its authorship and annotations alone; any
                        // other takes authorship of the text, and the text's annotations go with it.
                        int source = edit.getBeginA() + j;
                        boolean changed = !newText.equals(text.getString(source));
                        nextIntro.add(t.ignored() || !changed ? intro.get(source) : stamp);
                        nextMarks.add(t.ignored() || !changed ? marks.get(source) : null);
                        nextIds.add(ids.get(source));
                        survivors.add(ids.get(source));
                        if (changed) {
                            retexted.add(new CommitEvent.Line(ids.get(source), newText));
                        }
                    }
                }
                cursor = edit.getEndA();
            }
            for (int i = cursor; i < intro.size(); i++) {
                nextIntro.add(intro.get(i));
                nextMarks.add(marks.get(i));
                nextIds.add(ids.get(i));
                survivors.add(ids.get(i));
            }
            // A line removed in one place and added unchanged in another is the same line moved.
            Map<String, java.util.ArrayDeque<Integer>> removedByText = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                String old = text.getString(i);
                if (!survivors.contains(ids.get(i)) && significant(old)) {
                    removedByText.computeIfAbsent(old, k -> new java.util.ArrayDeque<>()).add(i);
                }
            }
            if (!removedByText.isEmpty()) {
                for (java.util.Iterator<CommitEvent.Line> it = introduced.iterator(); it.hasNext(); ) {
                    CommitEvent.Line line = it.next();
                    java.util.ArrayDeque<Integer> candidates = removedByText.get(line.text());
                    if (candidates == null || candidates.isEmpty()) {
                        continue;
                    }
                    int from = candidates.poll();
                    int at = nextIds.indexOf(line.id());
                    nextIds.set(at, ids.get(from));
                    nextIntro.set(at, intro.get(from));
                    nextMarks.set(at, marks.get(from));
                    survivors.add(ids.get(from));
                    it.remove();
                }
            }
            List<LineId> retired = new ArrayList<>();
            for (LineId old : ids) {
                if (!survivors.contains(old)) {
                    retired.add(old);
                }
            }
            intro = nextIntro;
            marks = nextMarks;
            ids = nextIds;
            text = t.text();
            lastPath = t.pathAtCommit();
            return new Delta(introduced, retexted, retired);
        }

        /** The sentences and inline elements as they stand, when a step changes no text. */
        CommitEvent.Segments segmentsUnchanged() {
            return CommitEvent.Segments.unchanged(sentenceIds, inlineIds);
        }

        /**
         * Segments the file as the touch left it and pairs its sentences and inline elements with
         * the previous ones: a paired unit keeps its identity, an unpaired one is introduced under
         * this commit at its position, and what is left over is retired.
         */
        CommitEvent.Segments segment(Touch t) {
            Segmenter.Segmentation now = Segmenter.of(IkeDialectParser.parse(lines(t.text())));
            String sha = t.commit().name();
            List<String> texts = now.texts();
            int[] map = TextPairing.pair(sentenceTexts, texts);
            List<SegmentId> ids = new ArrayList<>(texts.size());
            List<CommitEvent.Segment> introduced = new ArrayList<>();
            List<CommitEvent.Segment> retexted = new ArrayList<>();
            boolean[] kept = new boolean[sentenceIds.size()];
            for (int j = 0; j < texts.size(); j++) {
                if (map[j] >= 0) {
                    SegmentId id = sentenceIds.get(map[j]);
                    kept[map[j]] = true;
                    ids.add(id);
                    if (!texts.get(j).equals(sentenceTexts.get(map[j]))) {
                        retexted.add(new CommitEvent.Segment(id, texts.get(j)));
                    }
                } else {
                    SegmentId id = new SegmentId(SegmentId.Kind.SENTENCE, sha, t.pathAtCommit(), j + 1);
                    ids.add(id);
                    introduced.add(new CommitEvent.Segment(id, texts.get(j)));
                }
            }
            List<SegmentId> retired = new ArrayList<>();
            for (int i = 0; i < kept.length; i++) {
                if (!kept[i]) {
                    retired.add(sentenceIds.get(i));
                }
            }

            List<String> signatures = now.signatures();
            List<String> kinds = now.inlines().stream().map(inline -> inline.element().name()).toList();
            List<SegmentId> owners = now.inlines().stream().map(inline -> ids.get(inline.sentence())).toList();
            List<String> oldKinds = inlineKinds;
            List<SegmentId> oldOwners = inlineSentences;
            int[] inlineMap = TextPairing.pair(inlineSignatures, signatures,
                    (i, j) -> oldKinds.get(i).equals(kinds.get(j)) && oldOwners.get(i).equals(owners.get(j)));
            List<SegmentId> inline = new ArrayList<>(signatures.size());
            List<CommitEvent.Segment> inlineIntroduced = new ArrayList<>();
            List<CommitEvent.Segment> inlineRetexted = new ArrayList<>();
            boolean[] inlineKept = new boolean[inlineIds.size()];
            for (int j = 0; j < signatures.size(); j++) {
                String text = now.inlines().get(j).text();
                if (inlineMap[j] >= 0) {
                    SegmentId id = inlineIds.get(inlineMap[j]);
                    inlineKept[inlineMap[j]] = true;
                    inline.add(id);
                    if (!signatures.get(j).equals(inlineSignatures.get(inlineMap[j]))) {
                        inlineRetexted.add(new CommitEvent.Segment(id, text));
                    }
                } else {
                    SegmentId id = new SegmentId(SegmentId.Kind.INLINE, sha, t.pathAtCommit(), j + 1);
                    inline.add(id);
                    inlineIntroduced.add(new CommitEvent.Segment(id, text));
                }
            }
            List<SegmentId> inlineRetired = new ArrayList<>();
            for (int i = 0; i < inlineKept.length; i++) {
                if (!inlineKept[i]) {
                    inlineRetired.add(inlineIds.get(i));
                }
            }
            sentenceIds = List.copyOf(ids);
            sentenceTexts = texts;
            inlineIds = List.copyOf(inline);
            inlineSignatures = signatures;
            inlineKinds = kinds;
            inlineSentences = owners;
            return new CommitEvent.Segments(introduced, retexted, retired, sentenceIds, inlineIntroduced, inlineRetexted, inlineRetired,
                    inlineIds);
        }

        /**
         * The current sentences reading the given texts, matched in order: the identities an
         * annotation on those sentences, as the annotator read them, lands on. A sentence revised
         * since is not among them, since a judgment is about the text the annotator read.
         */
        List<SegmentId> sentencesReading(List<String> texts) {
            List<SegmentId> found = new ArrayList<>();
            int cursor = 0;
            for (String text : texts) {
                for (int i = cursor; i < sentenceTexts.size(); i++) {
                    if (sentenceTexts.get(i).equals(text)) {
                        found.add(sentenceIds.get(i));
                        cursor = i + 1;
                        break;
                    }
                }
            }
            return found;
        }

        /**
         * Withdraws every mark an earlier commit made: a retraction. Nothing the retracted
         * judgments had superseded comes back.
         *
         * @return the identities of the lines that lost a mark, in order
         */
        List<LineId> retract(String commitSha) {
            List<LineId> affected = new ArrayList<>();
            for (int line = 0; line < marks.size(); line++) {
                List<Mark> on = marks.get(line);
                if (on == null || on.stream().noneMatch(m -> m.record().commitSha().equals(commitSha))) {
                    continue;
                }
                List<Mark> kept = new ArrayList<>(on);
                kept.removeIf(m -> m.record().commitSha().equals(commitSha));
                marks.set(line, kept.isEmpty() ? null : kept);
                affected.add(ids.get(line));
            }
            return affected;
        }

        /**
         * Attach an annotation by {@code record} to the current lines that lines
         * [from, to] of {@code revText} map to.
         *
         * @return the identities of the lines that received the annotation, in order
         */
        List<LineId> annotate(StampRecord record, AnnotationKind kind, String annotator, RawText revText, int from, int to) {
            List<LineId> applied = new ArrayList<>();
            if (text.size() == 0) {
                return applied;                                  // the file is not in the chain yet
            }
            int[] map = lineMap(revText, text);
            Mark mark = new Mark(record, kind, annotator);
            for (int line = from - 1; line <= to - 1 && line < map.length; line++) {
                int current = map[line];
                if (current >= 0) {
                    List<Mark> on = marks.get(current) == null ? new ArrayList<>(2) : new ArrayList<>(marks.get(current));
                    if (kind.judgment()) {                       // a later stance by the same annotator replaces the earlier one
                        on.removeIf(m -> m.kind().judgment() && m.annotator().equals(annotator));
                    }
                    if (!on.contains(mark)) {
                        on.add(mark);
                    }
                    marks.set(current, on);
                    applied.add(ids.get(current));
                }
            }
            return applied;
        }

        StampRecord recordFor(RevCommit commit, String path) {
            return bySha.computeIfAbsent(commit.name(), sha -> record(commit, path, Status.ACTIVE));
        }

        Step snapshot(int chainIndex, String sha) {
            List<List<Mark>> marksCopy = new ArrayList<>(marks.size());
            for (List<Mark> on : marks) {
                marksCopy.add(on == null ? null : List.copyOf(on));
            }
            return new Step(chainIndex, sha, List.copyOf(intro), marksCopy, List.copyOf(ids));
        }

        /** Fold one step into STAMP-keyed records with fresh line bitmaps. */
        static StampFile fold(Path file, String revision, Set<String> ignored, Step step, List<String> problems) {
            Map<StampId, StampRecord> stamps = new LinkedHashMap<>();
            if (step != null) {
                for (int i = 0; i < step.intro().size(); i++) {
                    StampRecord r = step.intro().get(i);
                    stamps.computeIfAbsent(r.id(), id -> fresh(r)).introduced().add(i + 1);      // 1-based line numbers
                    List<Mark> on = step.marks().get(i);
                    if (on != null) {
                        for (Mark m : on) {
                            StampRecord a = stamps.computeIfAbsent(m.record().id(), id -> fresh(m.record()));
                            switch (m.kind()) {
                                case AFFIRMATION -> a.affirmed().add(i + 1);
                                case REFUTATION -> a.refuted().add(i + 1);
                                case COMMENT -> a.commented().add(i + 1);
                                case RETRACTION -> { }           // a retraction leaves no mark of its own
                            }
                        }
                    }
                }
            }
            return new StampFile(file, revision, ignored, stamps, List.copyOf(problems));
        }

        private static StampRecord fresh(StampRecord r) {
            return new StampRecord(r.id(), r.commitSha(), r.status(), r.time(), r.author(), r.module(), r.summary(),
                    new RoaringBitmap(), new RoaringBitmap(), new RoaringBitmap(), new RoaringBitmap(), r.signed());
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

    /** @return the annotated line identities, or null when the annotation was not applied */
    private List<LineId> applyAnnotation(Replay replay, Annot a, Map<String, Integer> chain, Map<Integer, Touch> touches,
                                         List<String> problems, List<SegmentId> sentences) throws IOException {
        if (a.kind() == AnnotationKind.RETRACTION) {
            String retracted = a.retracts().getFirst().commitSha();
            Integer at = chain.get(retracted);
            Integer own = chain.get(a.commit().name());
            if (at == null || own == null || at <= own) {
                problems.add(a.commit().abbreviate(7).name() + " " + a.annotation().value() + ": "
                        + retracted.substring(0, 7) + " is not an earlier commit on HEAD's first-parent chain");
                return null;
            }
            return replay.retract(retracted);
        }
        LineRange range = a.range();
        String where = a.commit().abbreviate(7).name() + " " + range.value();
        ObjectId rev;
        try {
            rev = repository.resolve(range.revision());
        } catch (IOException e) {
            problems.add(where + ": revision " + range.revision() + " " + e.getMessage());
            return null;
        }
        if (rev == null) {
            problems.add(where + ": unknown revision " + range.revision());
            return null;
        }
        RawText revText = blobAt(rev, range.gitPath());
        if (revText == null) {
            problems.add(where + ": " + range.file() + " is not committed at " + range.revision());
            return null;
        }
        if (!range.matches(lines(revText))) {
            problems.add(where + ": hash does not match the lines at " + range.revision());
            return null;
        }
        // the sentences the range covers whole, at the revision the annotator read
        List<String> revLines = lines(revText);
        List<String> covered = new ArrayList<>();
        for (Segmenter.Sentence sentence : Segmenter.of(IkeDialectParser.parse(revLines)).sentences()) {
            int[] first = {sentence.lines().getFirst(), sentence.flow().columnAt(sentence.start()) + 1};
            int[] last = {sentence.lines().getLast(), sentence.flow().columnAt(sentence.end() - 1) + 1};
            boolean starts = within(range, first);
            boolean ends = within(range, last);
            if (starts && ends) {
                covered.add(sentence.text());
            } else if (range.hasColumns() && (starts || ends)) {
                problems.add(where + ": the range covers part of a sentence; an annotation names whole sentences");
                return null;
            }
        }
        Annotation annotation = a.annotation();
        String annotator = annotation.by() != null
                ? "author:" + annotation.by().uuid()
                : "email:" + a.commit().getAuthorIdent().getEmailAddress().toLowerCase(java.util.Locale.ROOT);
        sentences.addAll(replay.sentencesReading(covered));
        // a column range lands on the lines it holds whole; the lines it shares with other sentences keep their own judgments
        int from = range.from();
        int to = range.to();
        if (range.hasColumns()) {
            if (!revLines.get(from - 1).substring(0, range.fromColumn() - 1).isBlank()) {
                from++;
            }
            if (!revLines.get(to - 1).substring(range.toColumn()).isBlank()) {
                to--;
            }
        }
        if (from > to) {
            return List.of();
        }
        return replay.annotate(replay.recordFor(a.commit(), range.file().toString()), a.kind(), annotator,
                revText, from, to);
    }

    /** Whether a 1-based line and column lies within a range: its lines, and its columns at either end. */
    private static boolean within(LineRange range, int[] at) {
        if (at[0] < range.from() || at[0] > range.to()) {
            return false;
        }
        if (!range.hasColumns()) {
            return true;
        }
        return !(at[0] == range.from() && at[1] < range.fromColumn()) && !(at[0] == range.to() && at[1] > range.toColumn());
    }

    /**
     * The last commit that changed the file at or before the range's anchoring
     * revision: the version the annotator saw. Falls back to the anchoring
     * revision itself when it is off the chain or precedes the file.
     */
    private String reviewedSha(LineRange range, Map<String, Integer> chain, Map<Integer, Touch> touches) {
        ObjectId rev;
        try {
            rev = repository.resolve(range.revision());
        } catch (IOException e) {
            return range.revision();
        }
        Integer at = rev == null ? null : chain.get(rev.name());
        if (at == null) {
            return rev == null ? range.revision() : rev.name();
        }
        Integer best = null;
        for (Integer index : touches.keySet()) {                 // a larger index is older
            if (index >= at && (best == null || index < best)) {
                best = index;
            }
        }
        return best == null ? rev.name() : touches.get(best).commit().name();
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
