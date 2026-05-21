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

/**
 * Time-traveling view of STAMP attribution for a single file.
 *
 * <p>A {@code StampHistory} owns the accumulated per-commit state from a
 * Level-2 history walk; individual {@link StampFile} snapshots are produced
 * from that state on demand. v1 exposes only {@link #atHead()}; the
 * {@code asOf(revision)} and {@code events()} methods land additively in
 * v1.1+ once withdrawal / cancellation modeling is in scope.
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

    // v1.1+:
    // StampFile asOf(org.eclipse.jgit.lib.ObjectId revision);
    // java.util.stream.Stream<StampEvent> events();
}
