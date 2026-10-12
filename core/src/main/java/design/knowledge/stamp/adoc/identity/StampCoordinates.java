/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.identity;

import design.knowledge.stamp.adoc.model.Status;

import java.time.Instant;

/**
 * A git-derived STAMP expressed in the five coordinates a Tinkar version
 * carries, every concept-valued one as a deterministic {@link Identity}.
 * Status is the git-side status: both {@link Status#ACTIVE} and
 * {@link Status#AFFIRMED} are active versions in Tinkar terms, the
 * affirmation being a role on the lines rather than a state of the version.
 *
 * @param stamp  identity of the STAMP itself, derived from the commit
 * @param status the git-side status
 * @param time   the commit's author time
 * @param author the author identity, keyed by canonical email
 * @param module the module identity, keyed by the repository or component name
 * @param path   the path identity, keyed by the branch name
 */
public record StampCoordinates(Identity stamp, Status status, Instant time, Identity author, Identity module, Identity path) { }
