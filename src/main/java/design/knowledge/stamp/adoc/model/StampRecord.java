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

import org.roaringbitmap.RoaringBitmap;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One unique STAMP attribution and the set of lines it covers within a
 * {@link StampFile} snapshot.
 *
 * <p>v1 carries a single role-tagged bitmap, {@link #introduced()}, recording
 * the lines this STAMP introduced. Future role-tagged bitmaps
 * ({@code modified}, {@code cancelled}) join in v1.1+ when withdrawal /
 * cancellation tracking lands; their absence in v1 reflects the active-only
 * scope.
 *
 * @param id          stable identifier for this STAMP (derived from commit
 *                    + author + time + module via the identity adapter)
 * @param commitSha   raw git commit SHA backing this STAMP
 * @param status      lifecycle status of this STAMP (v1: always
 *                    {@link Status#ACTIVE})
 * @param time        author time of the commit
 * @param author      author identity (name + email; will be normalized via
 *                    the mailmap-equivalent registry in a later milestone)
 * @param module      path of the file at the time of the commit
 *                    (rename-aware via JGit's diff machinery)
 * @param summary     commit subject line, useful as hover content
 * @param introduced  lines (1-based) in the {@link StampFile} snapshot that
 *                    were introduced by this STAMP
 */
public record StampRecord(
        StampId id,
        String commitSha,
        Status status,
        Instant time,
        Person author,
        Path module,
        String summary,
        RoaringBitmap introduced
) { }
