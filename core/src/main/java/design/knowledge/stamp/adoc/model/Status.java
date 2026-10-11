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

/**
 * Lifecycle status of a STAMP.
 *
 * <p>v1 ships {@link #ACTIVE} only. {@code WITHDRAWN} and {@code CANCELLED}
 * are reserved for v1.1+ once history-walk-derived transition events are
 * exposed via {@code StampHistory#events()}.
 */
public enum Status {
    /** Lines covered by this STAMP exist in the current snapshot. */
    ACTIVE
}
