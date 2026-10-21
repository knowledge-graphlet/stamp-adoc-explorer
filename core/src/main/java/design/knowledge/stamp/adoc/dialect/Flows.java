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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every {@link Flow} of a parsed document, in document order (IKE-Network/ike-issues#1313): the
 * runs of text a DocBook reading holds as mixed content. A block's content lines join into one
 * flow until a blank line, a markup line or a child block ends it; a heading or block title is a
 * flow of its own, as is a description item's term; a table's cells are read from its rows' lines,
 * a {@code |} opening each, and a cell may run over several lines. A list marker, an admonition
 * label, a bibliography marker and a cell's separator are not part of the text. The rules are the
 * Komet DocBook projection's, so the sentences found here are the ones it shows.
 */
public final class Flows {

    private static final Pattern ADMONITION = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s+");
    private static final Pattern CELL = Pattern.compile("(?:^|(?<=\\s))((?:\\d+(?:\\.\\d+)?[+*])?[<^>]?(?:\\.[<^>])?[aehlmdsv]?)\\|");

    private Flows() {
    }

    /**
     * The flows of a document.
     *
     * @param document the parse
     * @return its flows, ordered by the line and column each starts at
     */
    public static List<Flow> of(Document document) {
        return of(document, LineKinds.of(document));
    }

    /**
     * The flows of a document whose lines are already classified.
     *
     * @param document the parse
     * @param kinds    each line's kind, line 1 first
     * @return its flows, ordered by the line and column each starts at
     */
    public static List<Flow> of(Document document, List<LineKinds.Kind> kinds) {
        List<String> lines = document.lines();
        List<Flow> flows = new ArrayList<>();
        for (Block block : document.blocks()) {
            if (block.kind() == Block.Kind.TABLE_ROW) {
                continue;                                        // its table reads its lines as cells
            }
            blockFlows(block, lines, kinds, flows);
            if (block.kind() == Block.Kind.TABLE) {
                cells(block, lines, flows);
            }
        }
        flows.sort(Comparator.<Flow>comparingInt(Flow::firstLine).thenComparingInt(f -> f.parts().getFirst().from()));
        return List.copyOf(flows);
    }

    private static void blockFlows(Block block, List<String> lines, List<LineKinds.Kind> kinds, List<Flow> out) {
        Flow.Builder flow = new Flow.Builder(block, Flow.Kind.TEXT);
        boolean continuing = false;                              // an attribute entry's value goes on to this line
        boolean admonitionLabel = block.kind() == Block.Kind.ADMONITION;
        int previous = -1;
        for (int line : block.lines()) {
            String text = lines.get(line - 1);
            LineKinds.Kind kind = kinds.get(line - 1);
            if (previous >= 0 && line != previous + 1) {
                flow = flush(flow, out);                         // a blank line or a child block between
            }
            previous = line;
            if (continuing) {
                continuing = text.endsWith("\\");
                continue;
            }
            switch (kind) {
                case ANCHOR, BLOCK_ATTRIBUTE -> { }              // metadata: says something of the block, holds no text
                case HEADING -> {
                    flow = flush(flow, out);
                    Matcher heading = LineKinds.HEADING.matcher(text);
                    if (heading.matches()) {
                        out.add(new Flow.Builder(block, Flow.Kind.TITLE).add(line, text, heading.start(2), heading.end(2)).build());
                    }
                }
                case BLOCK_TITLE -> {
                    flow = flush(flow, out);
                    Matcher title = LineKinds.BLOCK_TITLE.matcher(text);
                    if (title.matches()) {
                        String value = title.group(1).strip();
                        int at = text.indexOf(value);
                        out.add(new Flow.Builder(block, Flow.Kind.TITLE).add(line, text, at, at + value.length()).build());
                    }
                }
                case ATTRIBUTE_ENTRY -> {
                    flow = flush(flow, out);
                    continuing = text.endsWith("\\");
                }
                case ITEM -> {
                    flow = flush(flow, out);
                    item(block, line, text, flow, out);
                }
                case PROSE -> {
                    int offset = 0;
                    if (block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY && flow.isEmpty()) {
                        Matcher marker = LineKinds.UNORDERED.matcher(text);
                        if (marker.matches()) {
                            offset = marker.start(2);
                        }
                    }
                    if (admonitionLabel && flow.isEmpty()) {
                        Matcher label = ADMONITION.matcher(text);
                        if (label.lookingAt()) {
                            offset = label.end();
                        }
                        admonitionLabel = false;
                    }
                    flow.add(line, text, offset, text.length());
                }
                default -> flow = flush(flow, out);             // blank, delimiter, comment, verbatim, include, conditional
            }
        }
        flush(flow, out);
    }

    /** A list item's first line: its marker dropped, a description item's term a flow of its own, the rest the item's text. */
    private static void item(Block block, int line, String text, Flow.Builder flow, List<Flow> out) {
        Matcher unordered = LineKinds.UNORDERED.matcher(text);
        Matcher ordered = LineKinds.ORDERED.matcher(text);
        if (unordered.matches()) {
            flow.add(line, text, unordered.start(2), text.length());
            return;
        }
        if (ordered.matches()) {
            flow.add(line, text, ordered.start(2), text.length());
            return;
        }
        Matcher description = LineKinds.DESCRIPTION.matcher(text);
        if (description.matches()) {
            String term = description.group(1).strip();
            int at = text.indexOf(term);
            out.add(new Flow.Builder(block, Flow.Kind.TERM).add(line, text, at, at + term.length()).build());
            if (description.group(3) != null) {
                flow.add(line, text, description.start(3), text.length());
            }
            return;
        }
        flow.add(line, text, 0, text.length());
    }

    private static Flow.Builder flush(Flow.Builder flow, List<Flow> out) {
        if (flow.isEmpty()) {
            return flow;
        }
        Flow built = flow.build();
        out.add(built);
        return new Flow.Builder(built.block(), Flow.Kind.TEXT);
    }

    /** A table's cells, read from its rows' lines in order: a {@code |} with its spec opens each, and a cell runs on until the next. */
    private static void cells(Block table, List<String> lines, List<Flow> out) {
        Flow.Builder open = null;
        for (Block row : table.children()) {
            if (row.kind() != Block.Kind.TABLE_ROW) {
                continue;
            }
            for (int line : row.lines()) {
                String text = lines.get(line - 1);
                if (text.isBlank()) {
                    continue;
                }
                Matcher m = CELL.matcher(text);
                int at = 0;
                while (m.find()) {
                    if (open != null && m.start() > at) {
                        open.add(line, text, at, m.start());
                    }
                    if (open != null && !open.isEmpty()) {
                        out.add(open.build());
                    }
                    open = new Flow.Builder(row, Flow.Kind.CELL);
                    at = m.end();
                }
                if (open != null && at < text.length()) {
                    open.add(line, text, at, text.length());
                }
            }
        }
        if (open != null && !open.isEmpty()) {
            out.add(open.build());
        }
    }
}
