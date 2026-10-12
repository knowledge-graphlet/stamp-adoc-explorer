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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * RFC 4122 name-based UUIDs, version 5: SHA-1 over the namespace's sixteen
 * big-endian bytes followed by the name in UTF-8, with the version and
 * variant bits set. Byte for byte the computation Tinkar's
 * {@code UuidT5Generator.get(namespace, name)} performs, so an identity
 * minted here without Tinkar on the classpath is the identity Komet
 * resolves.
 */
public final class Uuid5 {

    private Uuid5() { }

    /**
     * @param namespace the namespace UUID
     * @param name      the name within the namespace
     * @return the version-5 UUID of {@code name} in {@code namespace}
     */
    public static UUID of(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();
            hash[6] &= 0x0f;
            hash[6] |= 0x50;                                      // version 5
            hash[8] &= 0x3f;
            hash[8] |= (byte) 0x80;                               // IETF variant
            ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
