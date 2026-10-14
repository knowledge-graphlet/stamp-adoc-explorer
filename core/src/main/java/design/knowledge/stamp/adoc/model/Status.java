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
 * The status coordinate of a git-derived STAMP. Every commit's STAMP is
 * active; an annotation is a statement beside content, never a status of it
 * (ike-lab-documents topic {@code dev-stamp-annotation}). Withdrawal and
 * cancellation join here when the walk records them.
 */
public enum Status {
    /** The commit's versions are in force. */
    ACTIVE
}
