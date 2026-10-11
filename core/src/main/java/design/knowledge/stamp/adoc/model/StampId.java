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
 * Stable identifier for a STAMP, opaque to consumers of this module.
 *
 * <p>v1 derives the id deterministically from {@code (commitSha + author +
 * authorTime + module)} via the identity adapter — the same adapter that
 * future {@code ws:stamp-java} and {@code ws:stamp-graph} goals will reuse,
 * so STAMP ids agree across project types.
 *
 * @param value opaque string form of the identifier
 */
public record StampId(String value) { }
