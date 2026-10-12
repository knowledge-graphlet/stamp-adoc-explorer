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

import java.util.UUID;

/**
 * One STAMP coordinate as a deterministic identity: the UUID every machine
 * derives for it and the display name it was derived for. The UUID is what
 * a knowledge graph keys on; the name is what a person reads.
 *
 * @param uuid the identity, a version-5 UUID under {@link GitIdentityAdapter#NAMESPACE}
 * @param key  the frozen name key the UUID was derived from
 * @param name the human-readable name
 */
public record Identity(UUID uuid, String key, String name) {

    /** @return the same identity under another display name */
    public Identity withName(String displayName) {
        return new Identity(uuid, key, displayName);
    }
}
