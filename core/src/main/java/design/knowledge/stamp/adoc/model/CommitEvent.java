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
 */
public record CommitEvent(
        Commit commit,
        String pathAtCommit,
        boolean ignored,
        List<Line> introduced,
        List<Line> retexted,
        List<LineId> retired,
        List<LineId> manifest,
        List<AnnotationEvent> annotations
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
     */
    public record AnnotationEvent(Annotation annotation, int index, AnnotationKind kind, List<LineId> lines,
                                  String reviewedSha, String text) { }

    /** @return whether the commit changed the file's text or order */
    public boolean touchesFile() {
        return !introduced.isEmpty() || !retexted.isEmpty() || !retired.isEmpty();
    }
}
