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
 * One STAMP of a file: a commit, read as the Status, Time, Author, Module
 * and Path coordinates, with the current-snapshot lines it holds under each
 * line role.
 *
 * @param id         the STAMP identity (the commit SHA for now)
 * @param commitSha  the full commit SHA
 * @param status     {@link Status#ACTIVE} when the commit introduced lines, {@link Status#AFFIRMED} when it only affirmed them
 * @param time       the author time of the commit
 * @param author     the commit author
 * @param module     the path the file had when the commit was made
 * @param summary    the commit subject, for hover text
 * @param introduced 1-based current lines this commit introduced
 * @param affirmed   1-based current lines this commit affirmed through {@code Affirms:} trailers
 * @param signed     whether the commit carries a signature (presence, not yet verification)
 */
public record StampRecord(
        StampId id,
        String commitSha,
        Status status,
        Instant time,
        Person author,
        Path module,
        String summary,
        RoaringBitmap introduced,
        RoaringBitmap affirmed,
        boolean signed
) { }
