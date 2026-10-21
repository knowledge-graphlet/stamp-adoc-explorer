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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inline parse the DocBook projection reads a paragraph with: Asciidoctor's boundaries where the corpus meets them. */
class InlineMarkupTest {

    private static List<Element> elements(String text) {
        return InlineMarkup.parse(text).stream().map(Span::element).toList();
    }

    @Test
    void constrainedMarksNeedWordBoundaries() {
        assertEquals(List.of(Element.TEXT), elements("a snake_case_name and 2*3*4"), "a mark inside a word is text");
        assertEquals(List.of(Element.TEXT), elements("issues #1005, #1006 and ike-issues#1057"), "an issue number is text");
        assertEquals(List.of(Element.TEXT, Element.EMPHASIS, Element.TEXT), elements("an _emphasis_, then text"));
        assertEquals(List.of(Element.TEXT, Element.STRONG, Element.TEXT), elements("un**constrain**ed"), "a doubled mark needs no boundary");
        assertEquals(List.of(Element.TEXT), elements("a `.sync-conflict-*` pattern").subList(0, 1));
        assertEquals(List.of(Element.TEXT), elements("not \\*strong*"), "a backslash escapes the mark");
        assertEquals("not *strong*", InlineMarkup.parse("not \\*strong*").getFirst().text());
    }

    @Test
    void elementsKnowTheirCharactersAndContent() {
        String text = "See *bold `code`* and <<id,its _title_>>.";
        List<Span> spans = InlineMarkup.parse(text);
        Span strong = spans.get(1);
        assertEquals(Element.STRONG, strong.element());
        assertEquals("*bold `code`*", text.substring(strong.start(), strong.end()));
        assertEquals("bold code", strong.text());
        assertEquals(List.of(Element.TEXT, Element.MONOSPACE), strong.children().stream().map(Span::element).toList(), "formatting nests");
        Span xref = spans.get(3);
        assertEquals(Element.CROSS_REFERENCE, xref.element());
        assertEquals(Map.of("linkend", "id"), xref.attributes());
        assertEquals("its title", xref.text());
    }

    @Test
    void passthroughsAreText() {
        assertEquals(List.of(Element.TEXT), elements("keep +*as written*+ here"));
        assertEquals("keep *as written* here", InlineMarkup.parse("keep +*as written*+ here").getFirst().text());
        Span literal = InlineMarkup.parse("`+{attribute}+`").getFirst();
        assertEquals(Element.MONOSPACE, literal.element());
        assertEquals("{attribute}", literal.text());
    }

    @Test
    void linksIndexTermsFootnotesAndAnchors() {
        List<Span> spans = InlineMarkup.parse("Read https://example.org/a. Then link:b.html[B] and mailto:x@y.z[mail].");
        List<Span> links = spans.stream().filter(s -> s.element() == Element.LINK).toList();
        assertEquals(List.of("https://example.org/a", "b.html", "mailto:x@y.z"), links.stream().map(l -> l.attributes().get("href")).toList(),
                "a bare URL stops before the sentence's period");
        assertEquals("B", links.get(1).text());

        Span concealed = InlineMarkup.parse("(((ANF, statement, topic)))").getFirst();
        assertEquals(Element.INDEX_TERM, concealed.element());
        assertEquals(Map.of("primary", "ANF", "secondary", "statement", "tertiary", "topic", "shown", "no"), concealed.attributes());
        assertTrue(concealed.children().isEmpty());

        Span footnote = InlineMarkup.parse("footnote:[See xref:a[the a].]").getFirst();
        assertEquals(Element.FOOTNOTE, footnote.element());
        assertEquals("See the a.", footnote.text(), "a footnote's bracket closes after its nested macro");

        Span anchor = InlineMarkup.parse("[[key,Key text]]").getFirst();
        assertEquals(Map.of("id", "key", "reftext", "Key text"), anchor.attributes());
        Span bibliography = InlineMarkup.parse("[[[ref1]]] A book").getFirst();
        assertEquals(Map.of("id", "ref1"), bibliography.attributes());

        Span phrase = InlineMarkup.parse("[.term]#lease#").getFirst();
        assertEquals(Element.PHRASE, phrase.element());
        assertEquals(Map.of("role", "term"), phrase.attributes());
    }

    @Test
    void aPlusEndingALineIsALineBreak() {
        assertEquals(List.of(Element.STRONG, Element.TEXT, Element.LINE_BREAK, Element.TEXT), elements("*Name* +\nJane Doe"));
        assertEquals(List.of(Element.TEXT, Element.MONOSPACE, Element.TEXT, Element.MONOSPACE), elements("then `a` + `b`"), "a plus inside a line is text");
    }

    @Test
    void textMaySpanLines() {
        List<Span> spans = InlineMarkup.parse("one *two\nthree* four");
        Span strong = spans.get(1);
        assertEquals(Element.STRONG, strong.element());
        assertEquals("two\nthree", strong.text());
    }

    @Test
    void superscriptSubscriptHighlightQuotationAndAttributeReferences() {
        assertEquals(List.of(Element.TEXT, Element.SUPERSCRIPT, Element.TEXT), elements("E=mc^2^ holds"));
        assertEquals(List.of(Element.TEXT, Element.SUBSCRIPT), elements("H~2~"));
        assertEquals(List.of(Element.TEXT), elements("about ~1–2 s on LAN, ~/ike-dev"), "a tilde before a space or a path is text");
        assertEquals(List.of(Element.TEXT, Element.HIGHLIGHT, Element.TEXT), elements("a #marked# word"));
        Span quote = InlineMarkup.parse("\"`quoted *text*`\"").getFirst();
        assertEquals(Element.QUOTATION, quote.element());
        assertEquals("double", quote.attributes().get("marks"));
        assertEquals("quoted text", quote.text());
        Span reference = InlineMarkup.parse("see {topics}/a").getLast().element() == Element.TEXT
                ? InlineMarkup.parse("see {topics}/a").get(1) : null;
        assertEquals(Element.ATTRIBUTE_REFERENCE, reference.element());
        assertEquals("topics", reference.attributes().get("name"));
        assertEquals("see {topics}/a", InlineMarkup.plain(InlineMarkup.parse("see {topics}/a")), "a reference reads as written");
        assertEquals(List.of(Element.TEXT), elements("not \\{topics} nor \\^x^"));
    }
}
