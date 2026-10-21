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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The inline structure of AsciiDoc text (IKE-Network/ike-issues#1313): text runs interleaved with
 * the inline elements the dialect's documents use, as DocBook's mixed content holds them. It
 * parses text that may span several physical lines, a {@link Flow}'s, so every element knows the
 * characters it came from and through them its lines. The {@link Segmenter} reads it to find
 * sentences and the elements each holds; the Komet plugin's DocBook projection reads it too.
 *
 * <p>The rules follow Asciidoctor's where the corpus needs them: constrained marks
 * ({@code *strong*}, {@code _emphasis_}, {@code `monospace`}) open only after a non-word
 * character and close only before one, unconstrained ones ({@code **}, {@code __},
 * {@code ``}) anywhere, and {@code #highlight#} likewise; {@code ^superscript^} and
 * {@code ~subscript~} hold no space; {@code "`double`"} and {@code '`single`'} are curved quotations;
 * {@code {name}} is an attribute reference, kept as written; a backslash escapes a mark; a passthrough ({@code +text+},
 * {@code ++text++}, {@code pass:[text]}) is text, parsed no further; a {@code +} ending a line
 * is a line break. Formatting, link text,
 * cross-reference text, footnotes and visible index terms hold parsed content of their own.
 * What it does not know stays text, so nothing is lost: the source is the lines.
 */
public final class InlineMarkup {

    /** What a span is. */
    public enum Element {
        TEXT("text"),
        EMPHASIS("Emphasis"),
        STRONG("Strong"),
        MONOSPACE("Monospace"),
        PHRASE("Phrase"),
        CROSS_REFERENCE("Cross reference"),
        LINK("Link"),
        INDEX_TERM("Index term"),
        FOOTNOTE("Footnote"),
        ANCHOR("Anchor"),
        LINE_BREAK("Line break"),
        HIGHLIGHT("Highlight"),
        SUPERSCRIPT("Superscript"),
        SUBSCRIPT("Subscript"),
        QUOTATION("Quotation"),
        ATTRIBUTE_REFERENCE("Attribute reference");

        private final String word;

        Element(String word) {
            this.word = word;
        }

        /** @return the element in words, as the Graph view names it */
        public String word() {
            return word;
        }
    }

    /**
     * One span of the text.
     *
     * @param element    what it is
     * @param start      the first character it covers, markup included
     * @param end        one past the last character it covers
     * @param text       its content as plain text: a run's characters, an element's text with the markup removed
     * @param attributes an element's attributes in order: a cross reference's {@code linkend}, a link's {@code href}, an index term's {@code primary}…
     * @param children   an element's content, parsed; empty for a run and for an element that shows no text
     */
    public record Span(Element element, int start, int end, String text, Map<String, String> attributes, List<Span> children) {

        /** @return whether the span is a text run */
        public boolean isText() {
            return element == Element.TEXT;
        }
    }

    private static final Pattern XREF_SHORT = Pattern.compile("<<([^<>,\\s][^<>,]*?)(?:,\\s*([^>]*?))?>>");
    private static final Pattern XREF_MACRO = Pattern.compile("xref:([^\\[\\s]+)\\[");
    private static final Pattern LINK_MACRO = Pattern.compile("link:([^\\[\\s]+)\\[");
    private static final Pattern URL = Pattern.compile("(?:https?|ftp|irc)://[^\\s\\[\\]<>]+|mailto:[^\\s\\[\\]<>]+(?=\\[)");
    private static final Pattern FOOTNOTE = Pattern.compile("footnote:([\\w-]*)\\[");
    private static final Pattern INDEX_MACRO = Pattern.compile("indexterm(2?):\\[");
    private static final Pattern ANCHOR_MACRO = Pattern.compile("anchor:([\\w:.-]+)\\[");
    private static final Pattern ANCHOR_SHORT = Pattern.compile("\\[\\[\\[?([\\w:.-]+)(?:,\\s*([^\\]]*))?\\]?\\]\\]");
    private static final Pattern ROLE = Pattern.compile("\\[([.#%][^\\]\\s]*)\\]");
    private static final Pattern PASS_MACRO = Pattern.compile("pass:[a-z,]*\\[");
    private static final Pattern ATTRIBUTE_REFERENCE = Pattern.compile("\\{([A-Za-z0-9_][A-Za-z0-9_-]*)\\}");

    private final String s;

    private InlineMarkup(String s) {
        this.s = s;
    }

    /**
     * Parses text into spans: runs and elements in order, covering every character but the
     * markup of the elements.
     *
     * @param text the text, physical lines joined by {@code \n}
     * @return the spans, in order
     */
    public static List<Span> parse(String text) {
        return new InlineMarkup(text).spans(0, text.length());
    }

    /** The spans of {@code s[from, to)}. */
    private List<Span> spans(int from, int to) {
        List<Span> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int runStart = from;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && "*_`#+[<({^~\"'".indexOf(s.charAt(i + 1)) >= 0) {
                if (run.isEmpty()) {
                    runStart = i;
                }
                run.append(s.charAt(i + 1));                       // an escaped mark is text
                i += 2;
                continue;
            }
            Found found = at(i, to);
            if (found == null) {
                if (run.isEmpty()) {
                    runStart = i;
                }
                run.append(c);
                i++;
                continue;
            }
            if (found.span == null) {                             // a passthrough: its content is text
                if (run.isEmpty()) {
                    runStart = i;
                }
                run.append(found.text);
                i = found.end;
                continue;
            }
            flush(out, run, runStart, i);
            out.add(found.span);
            i = found.end;
        }
        flush(out, run, runStart, to);
        return out;
    }

    private static void flush(List<Span> out, StringBuilder run, int start, int end) {
        if (!run.isEmpty()) {
            out.add(new Span(Element.TEXT, start, end, run.toString(), Map.of(), List.of()));
            run.setLength(0);
        }
    }

    /** What starts at a position: an element ({@code span}), a passthrough ({@code text} only), or {@code null}. */
    private record Found(Span span, String text, int end) { }

    private Found at(int i, int to) {
        char c = s.charAt(i);
        return switch (c) {
            case '<' -> crossReferenceShort(i, to);
            case '(' -> indexTermShort(i, to);
            case '[' -> {
                Found anchor = anchorShort(i, to);
                yield anchor != null ? anchor : role(i, to);
            }
            case '*' -> formatted(i, to, '*', Element.STRONG, Map.of());
            case '#' -> formatted(i, to, '#', Element.HIGHLIGHT, Map.of());
            case '^' -> scripted(i, to, '^', Element.SUPERSCRIPT);
            case '~' -> scripted(i, to, '~', Element.SUBSCRIPT);
            case '"', '\'' -> quotation(i, to, c);
            case '{' -> attributeReference(i, to);
            case '_' -> formatted(i, to, '_', Element.EMPHASIS, Map.of());
            case '`' -> formatted(i, to, '`', Element.MONOSPACE, Map.of());
            case '+' -> {
                Found lineBreak = lineBreak(i, to);
                yield lineBreak != null ? lineBreak : passthrough(i, to);
            }
            case 'x' -> macro(i, to, XREF_MACRO, Element.CROSS_REFERENCE);
            case 'l' -> macro(i, to, LINK_MACRO, Element.LINK);
            case 'f' -> {
                Found footnote = footnote(i, to);
                yield footnote != null ? footnote : url(i, to);
            }
            case 'i' -> {
                Found term = indexTermMacro(i, to);
                yield term != null ? term : url(i, to);
            }
            case 'a' -> anchorMacro(i, to);
            case 'h', 'm' -> url(i, to);
            case 'p' -> passMacro(i, to);
            default -> null;
        };
    }

    // ── formatting ────────────────────────────────────────────────────────────

    /** Strong, emphasis or monospace, unconstrained (doubled mark) or constrained; with a role prefix, its roles. */
    private Found formatted(int i, int to, char mark, Element element, Map<String, String> attributes) {
        String twice = "" + mark + mark;
        if (s.startsWith(twice, i)) {
            int close = s.indexOf(twice, i + 2);
            if (close > i + 2 && close + 2 <= to) {
                return element(element, i, close + 2, i + 2, close, attributes);
            }
        }
        if (!opensConstrained(i)) {
            return null;
        }
        if (i + 1 >= to || Character.isWhitespace(s.charAt(i + 1)) || s.charAt(i + 1) == mark) {
            return null;
        }
        for (int j = i + 2; j < to; j++) {
            if (s.charAt(j) == mark && s.charAt(j - 1) != '\\' && !Character.isWhitespace(s.charAt(j - 1))
                    && (j + 1 >= to || !word(s.charAt(j + 1))) && (j + 1 >= to || s.charAt(j + 1) != mark)) {
                if (mark == '`' && s.charAt(i + 1) == '+' && s.charAt(j - 1) == '+' && j - 1 > i + 1) {
                    // `+literal+`: monospace whose content is text, parsed no further
                    String literal = s.substring(i + 2, j - 1);
                    return new Found(new Span(element, i, j + 1, literal, attributes, List.of(new Span(Element.TEXT, i + 2, j - 1, literal, Map.of(), List.of()))), null, j + 1);
                }
                return element(element, i, j + 1, i + 1, j, attributes);
            }
        }
        return null;
    }

    /** A constrained mark opens at the start, or after a character that is not a word character nor {@code ;:}}. */
    private boolean opensConstrained(int i) {
        if (i == 0) {
            return true;
        }
        char before = s.charAt(i - 1);
        return !word(before) && before != ';' && before != ':' && before != '}' && before != s.charAt(i);
    }

    /** {@code ^text^}, {@code ~text~}: the content holds no whitespace, and the mark is not doubled. */
    private Found scripted(int i, int to, char mark, Element element) {
        if (i + 1 >= to || s.charAt(i + 1) == mark || Character.isWhitespace(s.charAt(i + 1))) {
            return null;
        }
        for (int j = i + 1; j < to; j++) {
            char c = s.charAt(j);
            if (Character.isWhitespace(c)) {
                return null;
            }
            if (c == mark) {
                return s.charAt(j - 1) == '\\' ? null : element(element, i, j + 1, i + 1, j, Map.of());
            }
        }
        return null;
    }

    /** {@code "`text`"} and {@code '`text`'}: a quotation, its content parsed; the attribute says which marks. */
    private Found quotation(int i, int to, char quote) {
        if (i + 1 >= to || s.charAt(i + 1) != '`' || !opensConstrained(i)) {
            return null;
        }
        String close = "`" + quote;
        int end = s.indexOf(close, i + 2);
        if (end <= i + 2 || end + 2 > to) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("marks", quote == '"' ? "double" : "single");
        return element(Element.QUOTATION, i, end + 2, i + 2, end, attributes);
    }

    /** {@code {name}}: an attribute reference, kept as written; its text is the reference itself. */
    private Found attributeReference(int i, int to) {
        Matcher m = ATTRIBUTE_REFERENCE.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("name", m.group(1));
        return new Found(new Span(Element.ATTRIBUTE_REFERENCE, i, m.end(), m.group(), attributes, List.of()), null, m.end());
    }

    /** {@code [.role]#text#}, {@code [.role]*text*}: a phrase, or the formatting the mark makes, with its roles and id. */
    private Found role(int i, int to) {
        Matcher m = ROLE.matcher(s).region(i, to);
        if (!m.lookingAt() || m.end() >= to) {
            return null;
        }
        Map<String, String> attributes = shorthand(m.group(1));
        int at = m.end();
        char mark = s.charAt(at);
        Found inner = switch (mark) {
            case '#' -> formatted(at, to, '#', Element.PHRASE, attributes);
            case '*' -> formatted(at, to, '*', Element.STRONG, attributes);
            case '_' -> formatted(at, to, '_', Element.EMPHASIS, attributes);
            case '`' -> formatted(at, to, '`', Element.MONOSPACE, attributes);
            default -> null;
        };
        if (inner == null || inner.span == null) {
            return null;
        }
        Span span = inner.span;
        return new Found(new Span(span.element(), i, span.end(), span.text(), span.attributes(), span.children()), null, inner.end);
    }

    /** The shorthand of a block or role attribute: {@code #id}, {@code .role}, {@code %option}. */
    public static Map<String, String> shorthand(String text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher m = Pattern.compile("([#.%])([^#.%]+)").matcher(text);
        List<String> roles = new ArrayList<>();
        List<String> options = new ArrayList<>();
        while (m.find()) {
            switch (m.group(1)) {
                case "#" -> attributes.put("id", m.group(2));
                case "." -> roles.add(m.group(2));
                default -> options.add(m.group(2));
            }
        }
        if (!roles.isEmpty()) {
            attributes.put("role", String.join(" ", roles));
        }
        if (!options.isEmpty()) {
            attributes.put("options", String.join(",", options));
        }
        return attributes;
    }

    /** A hard line break: {@code +} after a space at the end of a line. */
    private Found lineBreak(int i, int to) {
        if (i > 0 && s.charAt(i - 1) == ' ' && (i + 1 == to || s.charAt(i + 1) == '\n')) {
            return new Found(new Span(Element.LINE_BREAK, i, i + 1, "", Map.of(), List.of()), null, i + 1);
        }
        return null;
    }

    // ── passthroughs ──────────────────────────────────────────────────────────

    /** {@code +text+}, {@code ++text++} and {@code +++text+++}: the content as text. */
    private Found passthrough(int i, int to) {
        for (String mark : List.of("+++", "++")) {
            if (s.startsWith(mark, i)) {
                int close = s.indexOf(mark, i + mark.length());
                if (close > i + mark.length() && close + mark.length() <= to) {
                    return new Found(null, s.substring(i + mark.length(), close), close + mark.length());
                }
            }
        }
        if (!opensConstrained(i) || i + 1 >= to || Character.isWhitespace(s.charAt(i + 1))) {
            return null;
        }
        for (int j = i + 2; j < to; j++) {
            if (s.charAt(j) == '+' && !Character.isWhitespace(s.charAt(j - 1)) && (j + 1 >= to || !word(s.charAt(j + 1)))) {
                return new Found(null, s.substring(i + 1, j), j + 1);
            }
        }
        return null;
    }

    private Found passMacro(int i, int to) {
        Matcher m = PASS_MACRO.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        int close = closingBracket(m.end(), to);
        return close < 0 ? null : new Found(null, s.substring(m.end(), close), close + 1);
    }

    // ── references ────────────────────────────────────────────────────────────

    private Found crossReferenceShort(int i, int to) {
        Matcher m = XREF_SHORT.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("linkend", m.group(1).strip());
        if (m.group(2) == null || m.group(2).isBlank()) {
            return new Found(new Span(Element.CROSS_REFERENCE, i, m.end(), "", attributes, List.of()), null, m.end());
        }
        return element(Element.CROSS_REFERENCE, i, m.end(), m.start(2), m.end(2), attributes);
    }

    /** {@code xref:id[text]}, {@code link:url[text]}: the target as an attribute, the text as content. */
    private Found macro(int i, int to, Pattern pattern, Element element) {
        Matcher m = pattern.matcher(s).region(i, to);
        if (!m.lookingAt() || (i > 0 && word(s.charAt(i - 1)))) {
            return null;
        }
        int close = closingBracket(m.end(), to);
        if (close < 0) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(element == Element.LINK ? "href" : "linkend", m.group(1));
        return linkText(element, i, close + 1, m.end(), close, attributes);
    }

    /** A URL, bare or with {@code [text]}; sentence punctuation after a bare one is not part of it. */
    private Found url(int i, int to) {
        if (i > 0 && (word(s.charAt(i - 1)) || s.charAt(i - 1) == '/' || s.charAt(i - 1) == ':')) {
            return null;
        }
        Matcher m = URL.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        int end = m.end();
        Map<String, String> attributes = new LinkedHashMap<>();
        if (end < to && s.charAt(end) == '[') {
            int close = closingBracket(end + 1, to);
            if (close >= 0) {
                attributes.put("href", s.substring(i, end));
                return linkText(Element.LINK, i, close + 1, end + 1, close, attributes);
            }
        }
        while (end > i && ".,;:!?)".indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        String href = s.substring(i, end);
        attributes.put("href", href);
        return new Found(new Span(Element.LINK, i, end, href, attributes, List.of(new Span(Element.TEXT, i, end, href, Map.of(), List.of()))), null, end);
    }

    /** A link's or cross reference's text, its content; empty text shows the target. Attributes after a comma ({@code ,window=_blank}) are dropped. */
    private Found linkText(Element element, int start, int end, int from, int to, Map<String, String> attributes) {
        int textEnd = to;
        int quote = s.indexOf('"', from);
        if (quote == from) {
            int closeQuote = s.indexOf('"', from + 1);
            if (closeQuote > 0 && closeQuote < to) {
                return element(element, start, end, from + 1, closeQuote, attributes);
            }
        }
        int comma = s.indexOf(",window=", from);
        if (comma >= 0 && comma < to) {
            textEnd = comma;
        }
        if (s.substring(from, textEnd).isBlank()) {
            return new Found(new Span(element, start, end, "", attributes, List.of()), null, end);
        }
        return element(element, start, end, from, textEnd, attributes);
    }

    private Found footnote(int i, int to) {
        Matcher m = FOOTNOTE.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        int close = closingBracket(m.end(), to);
        if (close < 0) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!m.group(1).isEmpty()) {
            attributes.put("id", m.group(1));
        }
        return element(Element.FOOTNOTE, i, close + 1, m.end(), close, attributes);
    }

    /** {@code indexterm:[a,b,c]} hidden, {@code indexterm2:[term]} shown. */
    private Found indexTermMacro(int i, int to) {
        Matcher m = INDEX_MACRO.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        int close = closingBracket(m.end(), to);
        if (close < 0) {
            return null;
        }
        return indexTerm(i, close + 1, m.end(), close, !m.group(1).isEmpty());
    }

    /** {@code (((a,b,c)))} hidden, {@code ((term))} shown. */
    private Found indexTermShort(int i, int to) {
        if (s.startsWith("(((", i)) {
            int close = s.indexOf(")))", i + 3);
            if (close > i + 3 && close + 3 <= to && s.substring(i + 3, close).indexOf('(') < 0) {
                return indexTerm(i, close + 3, i + 3, close, false);
            }
        }
        if (s.startsWith("((", i)) {
            int close = s.indexOf("))", i + 2);
            if (close > i + 2 && close + 2 <= to && s.substring(i + 2, close).indexOf('(') < 0) {
                return indexTerm(i, close + 2, i + 2, close, true);
            }
        }
        return null;
    }

    private Found indexTerm(int start, int end, int from, int to, boolean shown) {
        String[] parts = s.substring(from, to).split(",");
        Map<String, String> attributes = new LinkedHashMap<>();
        String[] keys = {"primary", "secondary", "tertiary"};
        for (int k = 0; k < parts.length && k < keys.length; k++) {
            attributes.put(keys[k], unquote(parts[k].strip().replace('\n', ' ')));
        }
        attributes.put("shown", shown ? "yes" : "no");
        if (shown) {
            return element(Element.INDEX_TERM, start, end, from, to, attributes);
        }
        return new Found(new Span(Element.INDEX_TERM, start, end, attributes.getOrDefault("primary", ""), attributes, List.of()), null, end);
    }

    private Found anchorShort(int i, int to) {
        Matcher m = ANCHOR_SHORT.matcher(s).region(i, to);
        if (!m.lookingAt()) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("id", m.group(1));
        if (m.group(2) != null && !m.group(2).isBlank()) {
            attributes.put("reftext", m.group(2).strip());
        }
        return new Found(new Span(Element.ANCHOR, i, m.end(), "", attributes, List.of()), null, m.end());
    }

    private Found anchorMacro(int i, int to) {
        Matcher m = ANCHOR_MACRO.matcher(s).region(i, to);
        if (!m.lookingAt() || (i > 0 && word(s.charAt(i - 1)))) {
            return url(i, to);
        }
        int close = closingBracket(m.end(), to);
        if (close < 0) {
            return null;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("id", m.group(1));
        if (close > m.end()) {
            attributes.put("reftext", s.substring(m.end(), close).strip());
        }
        return new Found(new Span(Element.ANCHOR, i, close + 1, "", attributes, List.of()), null, close + 1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** An element whose content is {@code s[from, to)}, parsed. */
    private Found element(Element element, int start, int end, int from, int to, Map<String, String> attributes) {
        List<Span> children = spans(from, to);
        return new Found(new Span(element, start, end, plain(children), attributes, children), null, end);
    }

    /** The plain text of spans: runs and the text of every element that shows any. */
    public static String plain(List<Span> spans) {
        StringBuilder text = new StringBuilder();
        for (Span span : spans) {
            if (span.isText()) {
                text.append(span.text());
            } else if (!span.children().isEmpty()) {
                text.append(plain(span.children()));
            } else if (span.element() == Element.CROSS_REFERENCE) {
                text.append(span.attributes().getOrDefault("linkend", ""));
            } else if (span.element() == Element.ATTRIBUTE_REFERENCE) {
                text.append(span.text());
            }
        }
        return text.toString();
    }

    /** The index of the {@code ]} closing a bracket opened before {@code from}, skipping escaped ones and nested pairs; -1 when none. */
    private int closingBracket(int from, int to) {
        int depth = 0;
        for (int j = from; j < to; j++) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < to) {
                j++;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                if (depth == 0) {
                    return j;
                }
                depth--;
            }
        }
        return -1;
    }

    private static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") ? text.substring(1, text.length() - 1) : text;
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
