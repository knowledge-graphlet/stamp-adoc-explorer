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
 * An author identity at the granularity git provides — name + email pair.
 *
 * <p>v1 uses these values verbatim. A {@code .mailmap}-equivalent identity
 * registry will land later so renames and email changes don't fragment
 * STAMPs.
 *
 * @param name  display name as recorded on the commit
 * @param email email address as recorded on the commit
 */
public record Person(String name, String email) { }
