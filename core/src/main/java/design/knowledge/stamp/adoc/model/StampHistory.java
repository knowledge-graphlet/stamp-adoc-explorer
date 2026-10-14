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

/**
 * The time-traveling view of one file's STAMP attribution: a snapshot at
 * HEAD or at any commit on the first-parent chain, and the event stream a
 * graph materializer replays.
 */
public interface StampHistory {

    /**
     * @return the file this history covers, relative to its containing
     *         repository
     */
    Path file();

    /**
     * @return the STAMP attribution snapshot at the current HEAD of the
     *         containing repository
     */
    StampFile atHead();

    /**
     * The snapshot as the file stood at a revision, in that revision's line
     * numbers.
     *
     * @param revision anything {@code git rev-parse} accepts, on HEAD's first-parent chain
     * @return the snapshot, empty when the file did not yet exist there
     * @throws IllegalArgumentException if the revision is unknown or off the chain
     */
    StampFile asOf(String revision);

    /**
     * @return one event per first-parent commit that changed or annotated
     *         the file, oldest first
     */
    List<CommitEvent> events();
}
