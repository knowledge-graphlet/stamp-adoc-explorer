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
 * The stable identity of one line: the commit that introduced it and the
 * line's position in the file at that commit. The identity rides along as
 * the line moves through later edits and is replaced when the line itself
 * is edited, so a line entity in a graph has one introducer and, through
 * ignored formatting commits, possibly several text versions.
 *
 * @param introducingSha full SHA of the commit that introduced the line
 * @param position       1-based line number at that commit
 */
public record LineId(String introducingSha, int position) {

    /** @return the frozen key form, {@code line:<sha>:<position>}, for identity derivation */
    public String key() {
        return "line:" + introducingSha + ":" + position;
    }
}
