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

import design.knowledge.stamp.adoc.dialect.InlineMarkup.Element;
import design.knowledge.stamp.adoc.dialect.InlineMarkup.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits a document's prose into sentences, the stored text segments of
 * {@code design-2026-10-21-stored-text-segments} (IKE-Network/ike-issues#1313), and lists the
 * inline elements each sentence holds.
 *
 * <p>Sentences are found within one {@link Flow} and never cross one. A boundary follows a
 * {@code .}, {@code ?} or {@code !}, with any closing quote or bracket, then whitespace, then a
 * character that is not a lowercase letter; a period after a known abbreviation or a single
 * capital initial is no boundary. These are the sentence rules of Unicode text segmentation (UAX
 * #29) as the corpus needs them. A boundary is found only in a text run: an inline element is
 * atomic, and a footnote's content is split into sentences of its own, listed after the sentence
 * that holds the footnote.
 *
 * <p>Segmentation reads only the text, never where its lines break, so a document and the same
 * document reflowed segment alike: <em>reflow invariance</em>. A sentence's {@link
 * Sentence#text() text} is its source, markup included, with every run of whitespace collapsed to
 * one space, except that a hard line break ({@code " +"} at a line's end) keeps its newline.
 */
public final class Segmenter {

    private static final Set<String> ABBREVIATIONS = Set.of("e.g", "i.e", "etc", "vs", "cf", "dr", "mr", "ms", "mrs", "no",
            "fig", "al", "approx", "ca", "st", "jr", "sr", "inc", "ltd", "co", "p", "pp", "vol", "ed", "eds", "ch", "sec");
    private static final String CLOSERS = ")\"'”’]";

    /**
     * One sentence.
     *
     * @param flow    the flow it is in
     * @param start   its first character in the flow's text
     * @param end     one past its last
     * @param text    its source, whitespace normalized
     * @param note    the index in {@link Segmentation#inlines()} of the footnote it is the content of, or -1
     * @param inlines the indexes in {@link Segmentation#inlines()} of the elements it holds, in preorder
     */
    public record Sentence(Flow flow, int start, int end, String text, int note, List<Integer> inlines) {

        /** @return the 1-based lines it spans */
        public List<Integer> lines() {
            return flow.lines(start, end);
        }
    }

    /**
     * One inline element.
     *
     * @param span     the parsed element, in its flow's offsets
     * @param sentence the index of the sentence it is in
     * @param parent   the index of the element it is nested in, or -1 at the top of its sentence
     * @param text     its source, whitespace normalized
     */
    public record Inline(Span span, int sentence, int parent, String text) {

        /** @return what kind of element it is */
        public Element element() {
            return span.element();
        }

        /** @return what pairs it across versions: its kind and its source */
        public String signature() {
            return span.element().name() + '\u0001' + text;
        }
    }

    /**
     * A document's sentences and inline elements, each list in the order identities are numbered
     * in: flow by flow, a sentence and then the sentences of its footnotes; an element after its
     * parent.
     *
     * @param sentences the sentences
     * @param inlines   the inline elements
     */
    public record Segmentation(List<Sentence> sentences, List<Inline> inlines) {

        /** @return the sentences' texts, in order */
        public List<String> texts() {
            return sentences.stream().map(Sentence::text).toList();
        }

        /** @return the inline elements' signatures, in order */
        public List<String> signatures() {
            return inlines.stream().map(Inline::signature).toList();
        }
    }

    private final List<Sentence> sentences = new ArrayList<>();
    private final List<Inline> inlines = new ArrayList<>();

    private Segmenter() {
    }

    /**
     * Segments a document.
     *
     * @param document the parse
     * @return its sentences and inline elements
     */
    public static Segmentation of(Document document) {
        return of(Flows.of(document));
    }

    /**
     * Segments flows.
     *
     * @param flows the flows, in document order
     * @return their sentences and inline elements
     */
    public static Segmentation of(List<Flow> flows) {
        Segmenter segmenter = new Segmenter();
        for (Flow flow : flows) {
            List<Span> spans = InlineMarkup.parse(flow.text());
            segmenter.sentences(flow, spans, 0, flow.text().length(), -1);
        }
        return new Segmentation(List.copyOf(segmenter.sentences), List.copyOf(segmenter.inlines));
    }

    /**
     * The sentence boundaries of a text: where each sentence starts and ends.
     *
     * @param text the text
     * @return {@code [start, end)} pairs, in order
     */
    public static List<int[]> sentences(String text) {
        List<Span> spans = InlineMarkup.parse(text);
        List<int[]> out = new ArrayList<>();
        for (int[] range : ranges(text, spans, 0, text.length())) {
            out.add(range);
        }
        return out;
    }

    /**
     * Collapses each run of whitespace to one space, keeping the newline of a hard line break.
     *
     * @param source the source text
     * @return the normalized text
     */
    public static String normalize(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            boolean newline = false;
            while (j < source.length() && Character.isWhitespace(source.charAt(j))) {
                newline |= source.charAt(j) == '\n';
                j++;
            }
            boolean hardBreak = newline && i >= 2 && source.charAt(i - 1) == '+' && source.charAt(i - 2) == ' ';
            if (i > 0 && j < source.length()) {
                out.append(hardBreak ? '\n' : ' ');
            }
            i = j;
        }
        return out.toString();
    }

    private void sentences(Flow flow, List<Span> spans, int from, int to, int note) {
        String text = flow.text();
        for (int[] range : ranges(text, spans, from, to)) {
            int index = sentences.size();
            List<Integer> own = new ArrayList<>();
            List<Integer> notes = new ArrayList<>();
            for (Span span : spans) {
                if (span.start() >= range[0] && span.end() <= range[1]) {
                    collect(flow, span, index, -1, own, notes);
                }
            }
            sentences.add(new Sentence(flow, range[0], range[1], normalize(text.substring(range[0], range[1])), note, List.copyOf(own)));
            for (int footnote : notes) {
                Span span = inlines.get(footnote).span();
                if (!span.children().isEmpty()) {
                    sentences(flow, span.children(), span.children().getFirst().start(), span.children().getLast().end(), footnote);
                }
            }
        }
    }

    /** Lists an element and, except inside a footnote, its nested elements; a footnote is remembered for its own sentences. */
    private void collect(Flow flow, Span span, int sentence, int parent, List<Integer> own, List<Integer> notes) {
        if (span.isText()) {
            return;
        }
        int index = inlines.size();
        inlines.add(new Inline(span, sentence, parent, normalize(flow.text().substring(span.start(), span.end()))));
        own.add(index);
        if (span.element() == Element.FOOTNOTE) {
            notes.add(index);
            return;
        }
        for (Span child : span.children()) {
            collect(flow, child, sentence, index, own, notes);
        }
    }

    /** The sentence ranges of {@code text[from, to)}, whose spans are given: cut only in text runs, trimmed of whitespace. */
    private static List<int[]> ranges(String text, List<Span> spans, int from, int to) {
        List<Integer> cuts = new ArrayList<>();
        for (Span span : spans) {
            if (!span.isText() || span.end() <= from || span.start() >= to) {
                continue;
            }
            for (int i = Math.max(from, span.start()); i < Math.min(to, span.end()); i++) {
                char c = text.charAt(i);
                if (c != '.' && c != '?' && c != '!') {
                    continue;
                }
                int j = i + 1;
                while (j < to && CLOSERS.indexOf(text.charAt(j)) >= 0) {
                    j++;
                }
                if (j >= to || !Character.isWhitespace(text.charAt(j))) {
                    continue;
                }
                int k = j;
                while (k < to && Character.isWhitespace(text.charAt(k))) {
                    k++;
                }
                if (k >= to || Character.isLowerCase(text.charAt(k)) || (c == '.' && abbreviation(text, i))) {
                    continue;
                }
                cuts.add(k);
            }
        }
        List<int[]> ranges = new ArrayList<>();
        int start = from;
        for (int cut : cuts) {
            add(ranges, text, start, cut);
            start = cut;
        }
        add(ranges, text, start, to);
        return ranges;
    }

    private static void add(List<int[]> ranges, String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        if (to > from) {
            ranges.add(new int[] {from, to});
        }
    }

    /** Whether the period at {@code dot} ends a known abbreviation or a capital initial. */
    private static boolean abbreviation(String text, int dot) {
        int b = dot;
        while (b > 0 && (Character.isLetter(text.charAt(b - 1)) || text.charAt(b - 1) == '.')) {
            b--;
        }
        String word = text.substring(b, dot).toLowerCase(Locale.ROOT);
        return ABBREVIATIONS.contains(word) || (word.length() == 1 && Character.isUpperCase(text.charAt(b)));
    }
}
