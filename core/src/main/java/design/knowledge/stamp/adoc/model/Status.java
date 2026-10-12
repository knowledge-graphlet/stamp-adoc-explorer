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

/** The status coordinate of a STAMP, as it bears on the current snapshot. */
public enum Status {
    /** The commit introduced lines that exist in the current snapshot. */
    ACTIVE,
    /**
     * The commit affirmed lines that exist in the current snapshot without
     * introducing any: a review recorded as {@code Affirms:} trailers.
     */
    AFFIRMED
}
