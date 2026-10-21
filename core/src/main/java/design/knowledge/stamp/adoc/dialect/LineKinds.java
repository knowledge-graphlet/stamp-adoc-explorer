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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each physical line of a parsed document is: blank, markup of one kind or another,
 * verbatim, the line a list item's marker opens, or prose (IKE-Network/ike-issues#1313). The
 * classification reads the parser's block structure, which owns every line but the blank ones,
 * and the markup the parser itself recognizes; it is a pure function of the text. The Komet
 * plugin names each kind with a line concept, and {@link Flows} reads the prose and item lines.
 */
public final class LineKinds {

    /** A line's kind. */
    public enum Kind {
        BLANK, DELIMITER, COMMENT, VERBATIM, HEADING, ANCHOR, BLOCK_TITLE, BLOCK_ATTRIBUTE, ATTRIBUTE_ENTRY, INCLUDE,
        CONDITIONAL, ITEM, PROSE
    }

    static final Pattern HEADING = Pattern.compile("^(={1,6})\\s+(\\S.*?)\\s*$");
    static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\[\\],]+)(?:,[^\\]]*)?\\]\\]$");
    static final Pattern BLOCK_TITLE = Pattern.compile("^\\.([^\\s.].*)$");
    static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    static final Pattern ATTRIBUTE_ENTRY = Pattern.compile("^:([A-Za-z0-9_][A-Za-z0-9_-]*!?):(?:\\s+(.*))?$");
    static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    static final Pattern UNORDERED = Pattern.compile("^\\s*(\\*+|-)\\s+(.*)$");
    static final Pattern ORDERED = Pattern.compile("^\\s*(\\.+|\\d+\\.)\\s+(.*)$");
    static final Pattern DESCRIPTION = Pattern.compile("^(?!\\s)(?![a-z]+::)(.+?)(::+)(?:\\s+(.*))?$");
    static final Pattern BLOCK_MACRO = Pattern.compile("^(image|include|ifdef|ifndef|ifeval|endif|toc|video|audio)::([^\\[]*)\\[(.*)\\]$");

    /** Blocks whose body is kept exactly as written. */
    static final Set<Block.Kind> VERBATIM = Set.of(Block.Kind.LISTING, Block.Kind.LITERAL, Block.Kind.DIAGRAM);

    private LineKinds() {
    }

    /**
     * Classifies every line of a parsed document.
     *
     * @param document the parse
     * @return each line's kind, line 1 first
     */
    public static List<Kind> of(Document document) {
        List<String> lines = document.lines();
        Map<Integer, Block> owner = owners(document);
        Map<Block, int[]> fences = new HashMap<>();
        List<Kind> kinds = new ArrayList<>(lines.size());
        for (int i = 1; i <= lines.size(); i++) {
            Block block = owner.get(i);
            int[] fence = block == null ? null : fences.computeIfAbsent(block, b -> fences(b, lines));
            kinds.add(classify(lines.get(i - 1), i, block, fence, lines));
        }
        return kinds;
    }

    /** @return the block that owns each non-blank line, keyed by 1-based line number */
    static Map<Integer, Block> owners(Document document) {
        Map<Integer, Block> owner = new HashMap<>();
        for (Block block : document.blocks()) {
            for (int line : block.lines()) {
                owner.put(line, block);
            }
        }
        return owner;
    }

    private static Kind classify(String s, int line, Block block, int[] fence, List<String> lines) {
        if (s.isBlank()) {
            return Kind.BLANK;
        }
        if (block != null && fence[0] > 0 && (VERBATIM.contains(block.kind()) || block.kind() == Block.Kind.OPAQUE)) {
            if (line == fence[0] || line == fence[1]) {
                return Kind.DELIMITER;
            }
            if (line > fence[0] && (fence[1] == 0 || line < fence[1])) {
                return lines.get(fence[0] - 1).startsWith("//") ? Kind.COMMENT : Kind.VERBATIM;
            }
        }
        if (DELIMITER.matcher(s).matches() || s.strip().equals("+")) {
            return Kind.DELIMITER;
        }
        if (s.startsWith("//")) {
            return Kind.COMMENT;
        }
        if (HEADING.matcher(s).matches() && block != null && block.headingLine() == line
                && (block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.DOCUMENT)) {
            return Kind.HEADING;
        }
        if (ANCHOR.matcher(s).matches()) {
            return Kind.ANCHOR;
        }
        if (BLOCK_TITLE.matcher(s).matches()) {
            return Kind.BLOCK_TITLE;
        }
        if (ATTRIBUTE_LINE.matcher(s).matches()) {
            return Kind.BLOCK_ATTRIBUTE;
        }
        if (ATTRIBUTE_ENTRY.matcher(s).matches()) {
            return Kind.ATTRIBUTE_ENTRY;
        }
        Matcher macro = BLOCK_MACRO.matcher(s);
        if (macro.matches()) {
            return switch (macro.group(1)) {
                case "include" -> Kind.INCLUDE;
                case "ifdef", "ifndef", "endif" -> Kind.CONDITIONAL;
                default -> Kind.VERBATIM;                        // image, toc and the media macros, kept as written
            };
        }
        if (block != null && block.kind() == Block.Kind.LIST_ITEM && opensItem(block, line, lines)) {
            return Kind.ITEM;
        }
        return Kind.PROSE;
    }

    /** Whether a line is the first line of a list item after its metadata: the line its marker opens. */
    private static boolean opensItem(Block item, int line, List<String> lines) {
        for (int owned : item.lines()) {
            String text = lines.get(owned - 1);
            if (ANCHOR.matcher(text).matches() || BLOCK_TITLE.matcher(text).matches() || ATTRIBUTE_LINE.matcher(text).matches()) {
                continue;
            }
            return owned == line;
        }
        return false;
    }

    /**
     * The opening and closing delimiter lines of a delimited block: the first line it owns that
     * is a delimiter, and the last it owns with the same text, 0 when it has none.
     */
    private static int[] fences(Block block, List<String> lines) {
        int open = 0;
        int close = 0;
        for (int owned : block.lines()) {
            String text = lines.get(owned - 1);
            if (open == 0) {
                if (DELIMITER.matcher(text).matches()) {
                    open = owned;
                }
            } else if (text.equals(lines.get(open - 1))) {
                close = owned;
            }
        }
        return new int[] {open, close};
    }
}
