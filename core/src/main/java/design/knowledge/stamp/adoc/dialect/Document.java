/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.dialect;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed document: its lines, the attribute entries of its header, the block tree rooted at
 * the document block, and the inline references found on its lines.
 *
 * <p>The structure is an overlay on the lines, never a replacement: the lines in order are the
 * document, and every non-blank line is owned by exactly one block (a leaf's own line, or a
 * section's or the document's heading or metadata line), so a reader can give the text back
 * unchanged from the lines and still navigate it by structure.
 *
 * @param lines      the lines as given, index 0 being line 1
 * @param attributes the header's attribute entries ({@code :topic-id: …}) in order, continued
 *                   lines joined with single spaces
 * @param root       the document block: level 0, its heading the {@code = Title} line
 * @param references every inline reference and composition directive, in line order
 */
public record Document(List<String> lines, Map<String, String> attributes, Block root, List<Reference> references) {

    public Document {
        lines = List.copyOf(lines);
        attributes = Collections.unmodifiableMap(attributes);
        references = List.copyOf(references);
    }

    /** @return the document title ({@code = Title}), or empty when the document has none */
    public String title() {
        return root.title();
    }

    /** @return the {@code :topic-id:} attribute, if the header declares one */
    public Optional<String> topicId() {
        return attribute("topic-id");
    }

    /** @return an attribute entry's value, if the header declares it */
    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    /** @return every block in document order, the document block first */
    public List<Block> blocks() {
        return root.flatten();
    }

    /** @return the references on one line, in order */
    public List<Reference> referencesOn(int line) {
        return references.stream().filter(r -> r.line() == line).toList();
    }

    /** @return the text of a 1-based line */
    public String line(int number) {
        return lines.get(number - 1);
    }
}
