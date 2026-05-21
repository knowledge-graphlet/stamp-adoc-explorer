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
import java.util.Map;
import java.util.Set;

/**
 * A snapshot of STAMP attribution for a single file at a single git revision.
 *
 * <p>The model is STAMP-keyed, not line-keyed: each entry in {@link #stamps()}
 * is one unique attribution with a {@link org.roaringbitmap.RoaringBitmap}
 * of the lines (1-based, in the snapshot's line-number space) it covers.
 *
 * @param file         path of the file relative to its containing repository
 * @param revisionSha  git SHA of the revision this snapshot reflects (HEAD in v1)
 * @param ignoredRevs  SHAs honored from {@code .git-blame-ignore-revs} during
 *                     extraction
 * @param stamps       map from {@link StampId} to {@link StampRecord} for every
 *                     unique attribution touching this snapshot
 */
public record StampFile(
        Path file,
        String revisionSha,
        Set<String> ignoredRevs,
        Map<StampId, StampRecord> stamps
) { }
