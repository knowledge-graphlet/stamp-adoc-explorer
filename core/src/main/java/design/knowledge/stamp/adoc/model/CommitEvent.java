/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.model;

import java.time.Instant;
import java.util.List;

/**
 * What one commit on the first-parent chain did to the file, oldest first in
 * {@link StampHistory#events()}: the lines it introduced (with their text),
 * the lines it re-texted, which keep their identity (a revision, or an
 * ignored formatting commit that takes no authorship), the lines it retired, the file's full line order afterwards,
 * and the annotations it recorded. A graph materializer writes one
 * transaction per event under the commit's STAMP; replaying the manifests
 * in order is the file's history.
 *
 * @param commit       the commit's coordinates
 * @param pathAtCommit the path the file had at this commit
 * @param ignored      whether the commit is listed in {@code .git-blame-ignore-revs}
 * @param introduced   new lines, in file order
 * @param retexted     existing lines whose text the commit changed: lines paired in order within a
 *                     diff hunk, which keep their identity (IKE-Network/ike-issues#1313)
 * @param retired      lines no longer present after this commit
 * @param manifest     every line of the file after this commit, in order
 * @param annotations  annotations this commit recorded on this file, with the lines they landed on
 * @param segments     what the commit did to the file's sentences and inline elements, which have
 *                     identities of their own below the line (IKE-Network/ike-issues#1313)
 */
public record CommitEvent(
        Commit commit,
        String pathAtCommit,
        boolean ignored,
        List<Line> introduced,
        List<Line> retexted,
        List<LineId> retired,
        List<LineId> manifest,
        List<AnnotationEvent> annotations,
        Segments segments
) {

    /**
     * A commit's own coordinates, independent of any snapshot.
     *
     * @param sha     full SHA
     * @param time    author time
     * @param author  author identity as committed
     * @param summary subject line
     * @param signed  whether the commit carries a signature
     */
    public record Commit(String sha, Instant time, Person author, String summary, boolean signed) { }

    /**
     * A line with its text at this commit.
     *
     * @param id   the line identity
     * @param text the line's text, without terminator
     */
    public record Line(LineId id, String text) { }

    /**
     * An annotation as applied to this file.
     *
     * @param annotation  the parsed trailer
     * @param index       the trailer's position among the commit's {@code Annotation:} trailers, 0-based;
     *                    with the commit SHA it names the annotation when the trailer carries no {@code id}
     * @param kind        the annotation's kind
     * @param lines       the identities of the annotated lines, in order
     * @param reviewedSha the full SHA of the last commit that changed the file at or before the
     *                    range's anchoring revision: the version the annotator saw
     * @param text        the commit body, without subject and trailers; empty when there is none
     * @param sentences   the sentences the range covers whole, as the annotator read them, by their current
     *                    identities (IKE-Network/ike-issues#1313); a sentence revised since is not among them
     */
    public record AnnotationEvent(Annotation annotation, int index, AnnotationKind kind, List<LineId> lines,
                                  String reviewedSha, String text, List<SegmentId> sentences) {

        /** An annotation on lines alone. */
        public AnnotationEvent(Annotation annotation, int index, AnnotationKind kind, List<LineId> lines, String reviewedSha, String text) {
            this(annotation, index, kind, lines, reviewedSha, text, List.of());
        }
    }

    /**
     * A sentence or an inline element with its text at this commit.
     *
     * @param id   its identity
     * @param text its source, whitespace normalized
     */
    public record Segment(SegmentId id, String text) { }

    /**
     * What one commit did below the line: the sentences and inline elements it introduced,
     * re-texted (paired with a predecessor, so keeping its identity) and retired, and every one of
     * each after it, in the order {@code Segmenter} lists them for the file's text at this commit.
     *
     * @param introduced       new sentences, in order
     * @param retexted         sentences whose text the commit changed
     * @param retired          sentences no longer present
     * @param manifest         every sentence after the commit, in order
     * @param inlineIntroduced new inline elements, in order
     * @param inlineRetexted   inline elements whose text the commit changed
     * @param inlineRetired    inline elements no longer present
     * @param inlineManifest   every inline element after the commit, in order
     */
    public record Segments(List<Segment> introduced, List<Segment> retexted, List<SegmentId> retired, List<SegmentId> manifest,
                           List<Segment> inlineIntroduced, List<Segment> inlineRetexted, List<SegmentId> inlineRetired,
                           List<SegmentId> inlineManifest) {

        /**
         * Nothing changed below the line; the manifests stand.
         *
         * @param manifest       every sentence
         * @param inlineManifest every inline element
         * @return the empty change
         */
        public static Segments unchanged(List<SegmentId> manifest, List<SegmentId> inlineManifest) {
            return new Segments(List.of(), List.of(), List.of(), manifest, List.of(), List.of(), List.of(), inlineManifest);
        }

        /** @return whether any sentence or inline element was introduced, re-texted or retired */
        public boolean changed() {
            return !introduced.isEmpty() || !retexted.isEmpty() || !retired.isEmpty()
                    || !inlineIntroduced.isEmpty() || !inlineRetexted.isEmpty() || !inlineRetired.isEmpty();
        }
    }

    /** @return whether the commit changed the file's text or order */
    public boolean touchesFile() {
        return !introduced.isEmpty() || !retexted.isEmpty() || !retired.isEmpty();
    }
}
