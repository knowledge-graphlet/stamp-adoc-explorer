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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * STAMP attribution of one file at one revision.
 *
 * @param file        the file, relative to its repository root
 * @param revisionSha the revision the snapshot describes (empty on an unborn branch)
 * @param ignoredRevs commits skipped for authorship through {@code .git-blame-ignore-revs}
 * @param stamps      every STAMP holding at least one current line under some role
 * @param problems    affirmations that could not be applied, one human-readable line each
 */
public record StampFile(
        Path file,
        String revisionSha,
        Set<String> ignoredRevs,
        Map<StampId, StampRecord> stamps,
        List<String> problems
) { }
