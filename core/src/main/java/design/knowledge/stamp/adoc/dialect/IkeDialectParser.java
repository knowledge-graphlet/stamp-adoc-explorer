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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A line-based parser for the IKE dialect of AsciiDoc — the subset the lab and Kompendium
 * topics are written in under the fragment standard — producing the elements of the AsciiDoc
 * document model: the document with its header attributes, sections, the block kinds, lists
 * with their items and continuations, tables as row groups, and the inline references.
 *
 * <p>It is deliberately not a full AsciiDoc parser. It reads what the 1,037 topics measured on
 * 2026-10-12 contain and treats everything else as opaque, so no document is refused and every
 * non-blank line is owned by exactly one block: the structure is an overlay on the lines, and
 * the lines in order remain the document. Table cells, inline formatting and attribute
 * substitution are left to a renderer.
 *
 * <p>Line numbers in the result are 1-based, matching STAMP line positions and
 * {@code Annotation:} line ranges.
 */
public final class IkeDialectParser {

    private static final Pattern HEADING = Pattern.compile("^(={1,6})\\s+(\\S.*?)\\s*$");
    private static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\[\\],]+)(?:,[^\\]]*)?\\]\\]$");
    private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.([^\\s.].*)$");
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ATTRIBUTE_ENTRY = Pattern.compile("^:([A-Za-z0-9_][A-Za-z0-9_-]*!?):(?:\\s+(.*))?$");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    private static final Pattern ADMONITION = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s+.*$");
    private static final Pattern ADMONITION_STYLE = Pattern.compile("^\\[(NOTE|TIP|WARNING|IMPORTANT|CAUTION)(?:[,\\]].*)?$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*(\\*+|-)\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\.+|\\d+\\.)\\s+(.*)$");
    private static final Pattern DESCRIPTION = Pattern.compile("^(?!\\s)(?![a-z]+::)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern BLOCK_MACRO = Pattern.compile("^(image|include|ifdef|ifndef|ifeval|endif|toc|video|audio)::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern BIBLIOGRAPHY_ENTRY = Pattern.compile("^\\[\\[\\[([^\\]]+)\\]\\]\\]\\s*(.*)$");

    private static final Pattern INDEX_TERM_MACRO = Pattern.compile("indexterm:\\[([^\\]]*)\\]");
    private static final Pattern CONCEALED_TERM = Pattern.compile("\\(\\(\\(([^()]+)\\)\\)\\)");
    private static final Pattern FLOW_TERM = Pattern.compile("\\(\\(([^()]+)\\)\\)");
    private static final Pattern XREF_MACRO = Pattern.compile("xref:([^\\[\\s]+)\\[([^\\]]*)\\]");
    private static final Pattern XREF_SHORT = Pattern.compile("<<([^>,]+?)(?:,\\s*([^>]*))?>>");
    private static final Pattern FOOTNOTE = Pattern.compile("footnote:\\[([^\\]]*)\\]");
    private static final Pattern LINK_MACRO = Pattern.compile("link:([^\\[\\s]+)\\[([^\\]]*)\\]");
    private static final Pattern BARE_URL = Pattern.compile("(?<![\\w:/])(https?://[^\\s\\[\\]<>)]+)");
    private static final Pattern ESTIMATE_MARK = Pattern.compile("<sup class=\"ours-mark\"(?: title=\"([^\"]*)\")?>[^<]*</sup>(?:<span class=\"ours-note\">([^<\\]]*))?");

    private final List<String> lines;
    private final List<Reference> references = new ArrayList<>();
    private final Set<String> bibliographyKeys = new HashSet<>();
    private final Set<Integer> verbatimLines = new HashSet<>();
    /** The lists being parsed, outermost first: a marker matching an enclosing list returns to it. */
    private final List<Marker> listStack = new ArrayList<>();
    private int pos;

    private IkeDialectParser(List<String> lines) {
        this.lines = lines;
    }

    /**
     * Parse a document given as lines.
     *
     * @param lines the lines, line 1 first
     * @return the parsed document
     */
    public static Document parse(List<String> lines) {
        return new IkeDialectParser(List.copyOf(lines)).run();
    }

    /**
     * Parse a document given as text; a trailing newline does not add an empty last line.
     *
     * @param text the whole document
     * @return the parsed document
     */
    public static Document parse(String text) {
        String[] split = text.replace("\r\n", "\n").split("\n", -1);
        List<String> lines = new ArrayList<>(Arrays.asList(split));
        if (!lines.isEmpty() && lines.getLast().isEmpty() && text.endsWith("\n")) {
            lines.removeLast();
        }
        return parse(lines);
    }

    private Document run() {
        Block document = new Block(Block.Kind.DOCUMENT, 0, 1);
        Map<String, String> attributes = new LinkedHashMap<>();
        parseHeader(document, attributes);
        parseBody(document, true, null);
        scanReferences(document);
        return new Document(lines, attributes, document, references);
    }

    // ── header ───────────────────────────────────────────────────────────────

    private void parseHeader(Block document, Map<String, String> attributes) {
        String anchor = "";
        while (pos < lines.size()) {
            String s = lines.get(pos);
            if (s.isBlank()) {
                pos++;
                continue;
            }
            Matcher heading = HEADING.matcher(s);
            if (heading.matches() && heading.group(1).length() == 1) {
                document.headingLine(pos + 1);
                document.title(heading.group(2));
                document.anchor(anchor);
                pos++;
                // attribute entries directly under the title still belong to the header
                while (pos < lines.size() && ATTRIBUTE_ENTRY.matcher(lines.get(pos)).matches()) {
                    attributeEntry(document, attributes);
                }
                return;
            }
            if (s.startsWith("//") && !DELIMITER.matcher(s).matches()) {
                document.own(pos + 1);
                verbatimLines.add(pos + 1);
                pos++;
                continue;
            }
            if (ATTRIBUTE_ENTRY.matcher(s).matches()) {
                attributeEntry(document, attributes);
                continue;
            }
            Matcher a = ANCHOR.matcher(s);
            if (a.matches()) {
                anchor = a.group(1);
                document.own(pos + 1);
                pos++;
                continue;
            }
            return;                                              // the body starts without a title
        }
    }

    private void attributeEntry(Block document, Map<String, String> attributes) {
        Matcher m = ATTRIBUTE_ENTRY.matcher(lines.get(pos));
        if (!m.matches()) {
            throw new IllegalStateException("not an attribute entry: " + lines.get(pos));
        }
        document.own(pos + 1);
        String name = m.group(1);
        StringBuilder value = new StringBuilder(m.group(2) == null ? "" : m.group(2).strip());
        pos++;
        while (value.length() > 0 && value.charAt(value.length() - 1) == '\\' && pos < lines.size()) {
            value.setLength(value.length() - 1);
            String next = lines.get(pos).strip();
            document.own(pos + 1);
            pos++;
            if (value.length() > 0 && !value.toString().endsWith(" ")) {
                value.append(' ');
            }
            value.append(next);
        }
        attributes.put(name, value.toString().strip());
    }

    // ── body ─────────────────────────────────────────────────────────────────

    /** Pending block metadata: the anchor, title and attribute lines above the next block. */
    private static final class Pending {
        String anchor = "";
        String title = "";
        String parameters = "";
        final List<Integer> lines = new ArrayList<>();

        boolean isEmpty() {
            return lines.isEmpty();
        }

        void applyTo(Block block) {
            block.anchor(anchor);
            if (!title.isEmpty()) {
                block.title(title);
            }
            if (!parameters.isEmpty()) {
                block.parameters(parameters);
            }
            for (int line : lines) {
                block.own(line);
            }
            anchor = "";
            title = "";
            parameters = "";
            lines.clear();
        }
    }

    /**
     * Parses blocks into {@code container} until the end, or until a line equal to
     * {@code stop} (a closing delimiter, consumed by the caller).
     *
     * @param container     the block receiving what is parsed; with sections allowed it is the
     *                      document, and headings open sections beneath it
     * @param allowSections whether headings open sections (false inside delimited blocks)
     * @param stop          the line that ends this body, or null for the end of the document
     */
    private void parseBody(Block container, boolean allowSections, String stop) {
        List<Block> sections = new ArrayList<>();
        sections.add(container);
        Pending pending = new Pending();
        while (pos < lines.size()) {
            String s = lines.get(pos);
            if (stop != null && s.equals(stop)) {
                flushPending(sections.getLast(), pending);
                return;
            }
            if (s.isBlank()) {
                pos++;
                continue;
            }
            Matcher heading = HEADING.matcher(s);
            if (heading.matches() && allowSections) {
                int level = heading.group(1).length() - 1;
                while (sections.size() > 1 && sections.getLast().level() >= level) {
                    sections.removeLast();
                }
                Block section = new Block(Block.Kind.SECTION, level, pos + 1);
                pending.applyTo(section);
                section.headingLine(pos + 1);
                section.title(heading.group(2));
                sections.getLast().add(section);
                sections.add(section);
                pos++;
                continue;
            }
            Matcher anchor = ANCHOR.matcher(s);
            if (anchor.matches()) {
                pending.anchor = anchor.group(1);
                pending.lines.add(pos + 1);
                pos++;
                continue;
            }
            Matcher title = BLOCK_TITLE.matcher(s);
            if (title.matches() && !DELIMITER.matcher(s).matches()) {
                pending.title = title.group(1).strip();
                pending.lines.add(pos + 1);
                pos++;
                continue;
            }
            Matcher attribute = ATTRIBUTE_LINE.matcher(s);
            if (attribute.matches()) {
                pending.parameters = pending.parameters.isEmpty() ? s : pending.parameters + "\n" + s;
                pending.lines.add(pos + 1);
                pos++;
                continue;
            }
            Block block = parseOne(pending, allowSections);
            sections.getLast().add(block);
        }
        flushPending(sections.getLast(), pending);
    }

    /** Metadata with no block after it is kept as an opaque block, so the lines stay owned. */
    private void flushPending(Block container, Pending pending) {
        if (!pending.isEmpty()) {
            Block opaque = new Block(Block.Kind.OPAQUE, 0, pending.lines.getFirst());
            pending.applyTo(opaque);
            container.add(opaque);
        }
    }

    /** Parses exactly one block starting at the current (non-blank, non-metadata) line. */
    private Block parseOne(Pending pending, boolean allowSections) {
        String s = lines.get(pos);
        Matcher delimiter = DELIMITER.matcher(s);
        if (delimiter.matches()) {
            return parseDelimited(pending, delimiter.group(1));
        }
        if (s.startsWith("//")) {
            Block comment = new Block(Block.Kind.OPAQUE, 0, pos + 1);
            pending.applyTo(comment);
            while (pos < lines.size() && lines.get(pos).startsWith("//") && !DELIMITER.matcher(lines.get(pos)).matches()) {
                comment.own(pos + 1);
                verbatimLines.add(pos + 1);
                pos++;
            }
            return comment;
        }
        Matcher macro = BLOCK_MACRO.matcher(s);
        if (macro.matches()) {
            return parseMacro(pending, macro);
        }
        if (ADMONITION.matcher(s).matches()) {
            Block admonition = new Block(Block.Kind.ADMONITION, 0, pos + 1);
            pending.applyTo(admonition);
            if (admonition.parameters().isEmpty()) {
                admonition.parameters("[" + s.substring(0, s.indexOf(':')) + "]");
            }
            ownParagraphLines(admonition);
            return admonition;
        }
        if (UNORDERED.matcher(s).matches() || ORDERED.matcher(s).matches() || isDescriptionTerm(s)) {
            return parseList(pending, allowSections);
        }
        if (s.strip().equals("+")) {
            Block stray = new Block(Block.Kind.OPAQUE, 0, pos + 1);
            pending.applyTo(stray);
            stray.own(pos + 1);
            pos++;
            return stray;
        }
        if (ATTRIBUTE_ENTRY.matcher(s).matches()) {
            Block entry = new Block(Block.Kind.OPAQUE, 0, pos + 1);
            pending.applyTo(entry);
            entry.own(pos + 1);
            pos++;
            return entry;
        }
        Block paragraph = new Block(Block.Kind.PARAGRAPH, 0, pos + 1);
        pending.applyTo(paragraph);
        ownParagraphLines(paragraph);
        return paragraph;
    }

    /** Owns consecutive lines up to a blank line, a delimiter or a heading. */
    private void ownParagraphLines(Block block) {
        while (pos < lines.size()) {
            String s = lines.get(pos);
            if (s.isBlank() || s.strip().equals("+") || DELIMITER.matcher(s).matches() || HEADING.matcher(s).matches()) {
                return;                                          // a lone + is a list continuation, never prose
            }
            block.own(pos + 1);
            pos++;
        }
    }

    private Block parseMacro(Pending pending, Matcher macro) {
        String name = macro.group(1);
        String target = macro.group(2).strip();
        String parameters = macro.group(3);
        int line = pos + 1;
        Block block;
        if (name.equals("image")) {
            block = new Block(Block.Kind.IMAGE, 0, line);
            pending.applyTo(block);
            if (block.parameters().isEmpty()) {
                block.parameters("[" + parameters + "]");
            }
            if (block.title().isEmpty()) {
                block.title(target);
            }
        } else {
            block = new Block(Block.Kind.OPAQUE, 0, line);
            pending.applyTo(block);
            switch (name) {
                case "include" -> references.add(new Reference(Reference.Kind.INCLUDE, line, 0, target, parameters, lines.get(pos)));
                case "ifdef", "ifndef" -> references.add(new Reference(Reference.Kind.CONDITIONAL, line, 0, target,
                        name.equals("ifndef") ? "negated" : "", lines.get(pos)));
                default -> { }
            }
        }
        block.own(line);
        verbatimLines.add(line);
        pos++;
        return block;
    }

    private Block parseDelimited(Pending pending, String opening) {
        int start = pos + 1;
        char c = opening.charAt(0);
        String parameters = pending.parameters;
        Block.Kind kind;
        boolean verbatim = false;
        boolean container = false;
        if (opening.equals("|===")) {
            kind = Block.Kind.TABLE;
        } else if (opening.equals("--")) {
            kind = Block.Kind.OPEN;
            container = true;
        } else {
            switch (c) {
                case '-' -> {
                    kind = parameters.startsWith("[plantuml") || parameters.startsWith("[graphviz") ? Block.Kind.DIAGRAM : Block.Kind.LISTING;
                    verbatim = true;
                }
                case '.' -> {
                    kind = Block.Kind.LITERAL;
                    verbatim = true;
                }
                case '=' -> {
                    kind = ADMONITION_STYLE.matcher(parameters).matches() ? Block.Kind.ADMONITION : Block.Kind.EXAMPLE;
                    container = true;
                }
                case '_' -> {
                    kind = Block.Kind.QUOTE;
                    container = true;
                }
                case '*' -> {
                    kind = Block.Kind.SIDEBAR;
                    container = true;
                }
                default -> {
                    kind = Block.Kind.OPAQUE;                    // comment or passthrough
                    verbatim = true;
                }
            }
        }
        Block block = new Block(kind, 0, start);
        pending.applyTo(block);
        block.own(start);
        if (verbatim) {
            verbatimLines.add(start);
        }
        pos++;
        if (container) {
            parseBody(block, false, opening);
        } else if (kind == Block.Kind.TABLE) {
            parseRows(block, opening);
        } else {
            while (pos < lines.size() && !lines.get(pos).equals(opening)) {
                block.own(pos + 1);
                if (verbatim) {
                    verbatimLines.add(pos + 1);
                }
                pos++;
            }
        }
        if (pos < lines.size() && lines.get(pos).equals(opening)) {
            block.own(pos + 1);
            if (verbatim) {
                verbatimLines.add(pos + 1);
            }
            pos++;
        }
        return block;
    }

    /** Rows are the blank-line separated line groups of a table; cells are not modeled yet. */
    private void parseRows(Block table, String closing) {
        Block row = null;
        while (pos < lines.size() && !lines.get(pos).equals(closing)) {
            String s = lines.get(pos);
            if (s.isBlank()) {
                row = null;
            } else {
                if (row == null) {
                    row = new Block(Block.Kind.TABLE_ROW, 0, pos + 1);
                    table.add(row);
                }
                row.own(pos + 1);
                table.extend(pos + 1);
            }
            pos++;
        }
    }

    // ── lists ────────────────────────────────────────────────────────────────

    private enum ListKind { UNORDERED, ORDERED, DESCRIPTION }

    private record Marker(ListKind kind, int depth, String text) { }

    private Marker marker(String s) {
        Matcher u = UNORDERED.matcher(s);
        if (u.matches()) {
            return new Marker(ListKind.UNORDERED, u.group(1).equals("-") ? 1 : u.group(1).length(), u.group(2));
        }
        Matcher o = ORDERED.matcher(s);
        if (o.matches()) {
            return new Marker(ListKind.ORDERED, o.group(1).endsWith(".") && Character.isDigit(o.group(1).charAt(0)) ? 1 : o.group(1).length(), o.group(2));
        }
        if (isDescriptionTerm(s)) {
            Matcher d = DESCRIPTION.matcher(s);
            d.matches();
            return new Marker(ListKind.DESCRIPTION, d.group(2).length() - 1, d.group(1).strip());
        }
        return null;
    }

    private boolean isDescriptionTerm(String s) {
        Matcher d = DESCRIPTION.matcher(s);
        return d.matches() && !BLOCK_MACRO.matcher(s).matches() && !s.contains("://");
    }

    private Block parseList(Pending pending, boolean allowSections) {
        Marker first = marker(lines.get(pos));
        listStack.add(first);
        try {
            return parseListItems(pending, allowSections, first);
        } finally {
            listStack.removeLast();
        }
    }

    private Block parseListItems(Pending pending, boolean allowSections, Marker first) {
        Block list = new Block(Block.Kind.LIST, first.depth(), pos + 1);
        pending.applyTo(list);
        boolean bibliography = list.parameters().contains("[bibliography]");
        if (list.parameters().isEmpty()) {
            list.parameters("[" + first.kind().name().toLowerCase() + "]");
        }
        Block item = null;
        while (pos < lines.size()) {
            String s = lines.get(pos);
            if (s.isBlank()) {
                int next = nextNonBlank(pos);
                if (next < 0) {
                    return list;
                }
                String n = lines.get(next);
                Marker m = marker(n);
                boolean continues = n.strip().equals("+")
                        || (m != null && (sameList(m, first) || (item != null && nests(m, first) && !enclosingListOwns(m))));
                if (!continues) {
                    return list;
                }
                pos = next;
                continue;
            }
            Marker m = marker(s);
            if (m != null && sameList(m, first)) {
                item = new Block(bibliography ? Block.Kind.BIBLIOGRAPHY_ENTRY : Block.Kind.LIST_ITEM, first.depth(), pos + 1);
                if (first.kind() == ListKind.DESCRIPTION) {
                    item.title(m.text());
                }
                Matcher entry = BIBLIOGRAPHY_ENTRY.matcher(m.text());
                if (bibliography && entry.matches()) {
                    item.anchor(entry.group(1));
                    bibliographyKeys.add(entry.group(1));
                }
                item.own(pos + 1);
                list.add(item);
                pos++;
                continue;
            }
            if (item == null) {
                return list;                                     // cannot happen: the first line is a marker
            }
            if (m != null && nests(m, first) && !enclosingListOwns(m)) {
                Pending none = new Pending();
                item.add(parseList(none, allowSections));
                list.extend(item.lastLine());
                continue;
            }
            if (m != null) {
                return list;                                     // a shallower marker of this kind ends this list
            }
            if (s.strip().equals("+")) {
                item.own(pos + 1);
                pos++;
                int next = nextNonBlank(pos);
                if (next < 0) {
                    return list;
                }
                pos = next;
                Pending inner = new Pending();
                item.add(parseAttached(inner, allowSections));
                list.extend(item.lastLine());
                continue;
            }
            if (DELIMITER.matcher(s).matches() || HEADING.matcher(s).matches()) {
                return list;
            }
            item.own(pos + 1);                                   // wrapped principal text
            list.extend(pos + 1);
            pos++;
        }
        return list;
    }

    /** One block attached to a list item by a {@code +} line, with its own metadata lines. */
    private Block parseAttached(Pending pending, boolean allowSections) {
        while (pos < lines.size()) {
            String s = lines.get(pos);
            Matcher anchor = ANCHOR.matcher(s);
            Matcher title = BLOCK_TITLE.matcher(s);
            Matcher attribute = ATTRIBUTE_LINE.matcher(s);
            if (anchor.matches()) {
                pending.anchor = anchor.group(1);
            } else if (title.matches() && !DELIMITER.matcher(s).matches()) {
                pending.title = title.group(1).strip();
            } else if (attribute.matches()) {
                pending.parameters = pending.parameters.isEmpty() ? s : pending.parameters + "\n" + s;
            } else {
                break;
            }
            pending.lines.add(pos + 1);
            pos++;
        }
        if (pos >= lines.size() || lines.get(pos).isBlank()) {
            Block opaque = new Block(Block.Kind.OPAQUE, 0, pending.lines.isEmpty() ? pos : pending.lines.getFirst());
            pending.applyTo(opaque);
            return opaque;
        }
        return parseOne(pending, allowSections);
    }

    private static boolean sameList(Marker m, Marker first) {
        return m.kind() == first.kind() && m.depth() == first.depth();
    }

    /** A deeper marker of the same kind, or any marker of another kind, nests under the current item. */
    private static boolean nests(Marker m, Marker first) {
        return m.kind() != first.kind() || m.depth() > first.depth();
    }

    /** Whether a marker belongs to a list enclosing the one being parsed, which then resumes. */
    private boolean enclosingListOwns(Marker m) {
        for (int i = 0; i < listStack.size() - 1; i++) {
            if (sameList(m, listStack.get(i))) {
                return true;
            }
        }
        return false;
    }

    private int nextNonBlank(int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    // ── inline references ────────────────────────────────────────────────────

    private record Found(int start, int end, Reference.Kind kind, String target, String text) { }

    private void scanReferences(Block document) {
        for (int i = 0; i < lines.size(); i++) {
            int line = i + 1;
            if (verbatimLines.contains(line)) {
                continue;
            }
            String s = lines.get(i);
            if (s.isBlank() || s.indexOf('[') < 0 && s.indexOf('(') < 0 && s.indexOf('<') < 0 && !s.contains("://")) {
                continue;
            }
            List<Found> found = new ArrayList<>();
            find(found, INDEX_TERM_MACRO, s, Reference.Kind.INDEX_TERM, 1, -1, "");
            find(found, CONCEALED_TERM, s, Reference.Kind.INDEX_TERM, 1, -1, "concealed");
            find(found, FLOW_TERM, s, Reference.Kind.INDEX_TERM, 1, -1, "flow");
            find(found, XREF_MACRO, s, Reference.Kind.CROSS_REFERENCE, 1, 2, "");
            find(found, XREF_SHORT, s, Reference.Kind.CROSS_REFERENCE, 1, 2, "");
            find(found, FOOTNOTE, s, Reference.Kind.FOOTNOTE, 1, -1, "");
            find(found, LINK_MACRO, s, Reference.Kind.LINK, 1, 2, "");
            find(found, BARE_URL, s, Reference.Kind.LINK, 1, -1, "");
            if (s.contains("ours-mark")) {
                Matcher m = ESTIMATE_MARK.matcher(s);
                while (m.find()) {
                    String note = m.group(2) != null && !m.group(2).isBlank() ? m.group(2).strip() : m.group(1) == null ? "" : m.group(1);
                    found.add(new Found(m.start(), m.end(), Reference.Kind.ESTIMATE_MARK, note, ""));
                }
            }
            found.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end - b.start, a.end - a.start));
            int index = 0;
            int covered = -1;
            for (Found f : found) {
                if (f.start < covered) {
                    continue;                                    // inside an earlier match (a URL inside a link macro)
                }
                covered = f.end;
                Reference.Kind kind = f.kind == Reference.Kind.CROSS_REFERENCE && bibliographyKeys.contains(f.target)
                        ? Reference.Kind.CITATION : f.kind;
                references.add(new Reference(kind, line, index++, f.target, f.text, s.substring(f.start, f.end)));
            }
        }
        references.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.index(), b.index()));
    }

    private static void find(List<Found> into, Pattern pattern, String s, Reference.Kind kind, int targetGroup, int textGroup, String fixedText) {
        Matcher m = pattern.matcher(s);
        while (m.find()) {
            String text = textGroup > 0 && m.group(textGroup) != null ? m.group(textGroup).strip() : fixedText;
            String target = m.group(targetGroup).strip();
            int end = m.end();
            if (pattern == BARE_URL) {
                while (!target.isEmpty() && ".,;:!?".indexOf(target.charAt(target.length() - 1)) >= 0) {
                    target = target.substring(0, target.length() - 1);   // sentence punctuation is not part of a URL
                    end--;
                }
            }
            into.add(new Found(m.start(), end, kind, target, text));
        }
    }
}
