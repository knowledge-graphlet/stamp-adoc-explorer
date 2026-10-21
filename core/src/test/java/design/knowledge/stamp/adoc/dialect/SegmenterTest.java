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
import design.knowledge.stamp.adoc.dialect.Segmenter.Segmentation;
import design.knowledge.stamp.adoc.dialect.Segmenter.Sentence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sentences found within flows, inline elements per sentence, and the reflow invariance the stored segments rely on. */
class SegmenterTest {

    private static String fixture() throws IOException {
        try (InputStream in = SegmenterTest.class.getResourceAsStream("/segments/wrapped.adoc")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sentencesCrossLinesButNeverFlows() throws IOException {
        Segmentation segments = Segmenter.of(IkeDialectParser.parse(fixture()));
        List<String> texts = segments.texts();
        assertEquals("Leases for a Fleet", texts.getFirst(), "a heading is a flow of its own");
        assertEquals("A ((working-set lease)) grants one machine at a time the right to write a working set — any project root under "
                + "`~/ike-dev` that IntelliJ would open — while Syncthing carries the working tree to every machine.", texts.get(1));
        assertEquals("The lease record lives in the synced folder itself, so taking a lease and announcing it are the same act: every "
                + "peer observes the change within seconds.", texts.get(2), "a colon is no boundary");
        Sentence second = segments.sentences().get(2);
        assertEquals(List.of(5, 6, 7), second.lines(), "the sentence starts mid-line and ends a paragraph");
        assertTrue(texts.contains("indexterm:[multi-machine development, single-writer] The lease generalizes that rule to working-set "
                + "granularity and makes it machine-enforced."), "a concealed index term on its own line belongs to the next sentence");
    }

    @Test
    void abbreviationsInitialsAndLowercaseAreNoBoundary() throws IOException {
        List<String> texts = Segmenter.of(IkeDialectParser.parse(fixture())).texts();
        assertTrue(texts.contains("A recall never waits, e.g. for the TTL to lapse."), String.join("\n", texts));
        assertTrue(texts.contains("Dr. Smith asked why."));
        assertTrue(texts.contains("J. Doe answered."));
    }

    @Test
    void markersLabelsAndCellSeparatorsAreNotText() throws IOException {
        List<String> texts = Segmenter.of(IkeDialectParser.parse(fixture())).texts();
        assertTrue(texts.contains("The TTL is a staleness horizon, not a queue."), "a list item's text, marker dropped");
        assertTrue(texts.contains("A returned record stays where it is."), "an admonition's text, label dropped");
        assertTrue(texts.contains("Why recall is immediate"), "a block title");
        assertTrue(texts.contains("Gain the working set."), "a cell's text");
        assertTrue(texts.contains("Renew it while working."));
        assertTrue(texts.contains("Term"), "a description term");
        assertTrue(texts.contains("A definition that runs on."));
        assertTrue(texts.stream().noneMatch(t -> t.contains("Segmented")), "a listing is verbatim");
    }

    @Test
    void elementsBelongToTheirSentenceAndFootnotesHoldSentences() throws IOException {
        Segmentation segments = Segmenter.of(IkeDialectParser.parse(fixture()));
        Sentence concurrency = segments.sentences().stream().filter(s -> s.text().startsWith("Single-writer")).findFirst().orElseThrow();
        List<Element> kinds = concurrency.inlines().stream().map(i -> segments.inlines().get(i).element()).toList();
        assertEquals(List.of(Element.STRONG, Element.FOOTNOTE), kinds);
        int footnote = concurrency.inlines().get(1);
        List<String> notes = segments.sentences().stream().filter(s -> s.note() == footnote).map(Sentence::text).toList();
        assertEquals(List.of("Siblings exist for this.", "See <<siblings>>."), notes);
        int index = segments.sentences().indexOf(concurrency);
        assertEquals("Siblings exist for this.", segments.sentences().get(index + 1).text(), "a footnote's sentences follow the sentence");
        Sentence see = segments.sentences().get(index + 2);
        assertEquals(Element.CROSS_REFERENCE, segments.inlines().get(see.inlines().getFirst()).element(),
                "an element inside a footnote belongs to the footnote's sentence");
    }

    @Test
    void normalizingKeepsAHardLineBreak() {
        assertEquals("one two three", Segmenter.normalize("  one\n  two\tthree \n"));
        assertEquals("Name: +\nJane Doe", Segmenter.normalize("Name: +\n  Jane Doe"));
    }

    @Test
    void segmentationIsReflowInvariant() throws IOException {
        String text = fixture();
        assertReflowInvariant(text, "fixture");
    }

    /**
     * The five reference documents and the rest of a corpus, when {@code -Dstamp.corpus} names
     * directories to walk ({@code :}-separated); skipped otherwise.
     */
    @Test
    void corpusIsReflowInvariant() throws IOException {
        String corpus = System.getProperty("stamp.corpus", "");
        Assumptions.assumeFalse(corpus.isBlank(), "no -Dstamp.corpus");
        int files = 0;
        List<String> failures = new ArrayList<>();
        for (String dir : corpus.split(":")) {
            try (Stream<Path> paths = Files.walk(Path.of(dir))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".adoc")).toList()) {
                    String failure = reflowDifference(Files.readString(path));
                    if (failure != null) {
                        failures.add(path + ": " + failure);
                    }
                    files++;
                }
            }
        }
        assertTrue(files > 0);
        assertEquals(List.of(), failures, failures.size() + " of " + files + " files segment differently once reflowed");
    }

    /** @return the first sentence that segments differently once the document is reflowed, or null */
    private static String reflowDifference(String text) {
        Document parsed = IkeDialectParser.parse(text);
        List<String> before = Segmenter.of(parsed).texts();
        List<String> after = Segmenter.of(IkeDialectParser.parse(reflow(parsed))).texts();
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            if (!before.get(i).equals(after.get(i))) {
                return "\n  before: " + before.get(i) + "\n  after:  " + after.get(i);
            }
        }
        return before.size() == after.size() ? null : before.size() + " sentences before, " + after.size() + " after";
    }

    private static void assertReflowInvariant(String text, String name) {
        Document parsed = IkeDialectParser.parse(text);
        List<String> before = Segmenter.of(parsed).texts();
        String reflowed = reflow(parsed);
        List<String> after = Segmenter.of(IkeDialectParser.parse(reflowed)).texts();
        assertEquals(before, after, name + " segments differently once reflowed");
    }

    /** The document with every multi-line text flow rewritten as one sentence per line, its other lines untouched. */
    static String reflow(Document document) {
        List<String> lines = document.lines();
        List<Flow> flows = Flows.of(document);
        List<List<String>> replacement = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            replacement.add(null);
        }
        boolean[] drop = new boolean[lines.size()];
        for (Flow flow : flows) {
            if (flow.kind() != Flow.Kind.TEXT || flow.parts().size() < 2 || !wholeLines(flow, lines)) {
                continue;
            }
            Flow.Part first = flow.parts().getFirst();
            String prefix = lines.get(first.line() - 1).substring(0, first.from());
            List<String> out = new ArrayList<>();
            boolean guarded = false;
            for (int[] range : Segmenter.sentences(flow.text())) {
                // a hard line break keeps its newline; every other line break inside the sentence goes
                String sentence = flow.text().substring(range[0], range[1]).replaceAll("(?<! \\+)[ \\t]*\\n[ \\t]*", " ");
                for (String line : sentence.split("\n")) {
                    String written = out.isEmpty() ? prefix + line : line;
                    guarded |= (!out.isEmpty() && startsStructure(line)) || LineKinds.DESCRIPTION.matcher(written).matches();
                    out.add(written);
                }
            }
            if (guarded) {
                continue;
            }
            replacement.set(first.line() - 1, out);
            for (int k = 1; k < flow.parts().size(); k++) {
                drop[flow.parts().get(k).line() - 1] = true;
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (replacement.get(i) != null) {
                result.addAll(replacement.get(i));
            } else if (!drop[i]) {
                result.add(lines.get(i));
            }
        }
        return String.join("\n", result) + "\n";
    }

    private static boolean wholeLines(Flow flow, List<String> lines) {
        for (int k = 0; k < flow.parts().size(); k++) {
            Flow.Part part = flow.parts().get(k);
            if (part.to() != lines.get(part.line() - 1).length() || (k > 0 && part.from() != 0)) {
                return false;
            }
            if (k > 0 && part.line() != flow.parts().get(k - 1).line() + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsStructure(String sentence) {
        return sentence.matches("^(=+ |[*-]+ |\\.+ |\\d+\\. |\\||//|\\[|\\+$|(NOTE|TIP|WARNING|IMPORTANT|CAUTION): ).*");
    }
}
