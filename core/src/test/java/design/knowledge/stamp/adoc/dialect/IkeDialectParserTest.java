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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IkeDialectParserTest {

    /** A lab topic of 829 lines exercising sections, a listing, tables, lists with continuations and an admonition. */
    private static Document lease;

    @BeforeAll
    static void parseFixture() throws IOException {
        try (InputStream in = IkeDialectParserTest.class.getResourceAsStream("/dialect/dev-working-set-lease.adoc")) {
            lease = IkeDialectParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(829, lease.lines().size());
    }

    @Test
    void headerYieldsTitleAnchorAndJoinedAttributes() {
        assertEquals("Working-Set Leases — Single Writer Across Machines", lease.title());
        assertEquals("dev-working-set-lease", lease.root().anchor());
        assertEquals(20, lease.root().headingLine());
        assertEquals("dev-working-set-lease", lease.topicId().orElseThrow());
        assertEquals("proposed", lease.attribute("topic-status").orElseThrow());
        String note = lease.attribute("topic-scope-note").orElseThrow();
        assertTrue(note.startsWith("The coordination protocol that gives every openable project root in the synced"), note);
        assertTrue(note.endsWith("released as ike-lease 7."), note);
        assertFalse(note.contains("\\"), "continuation backslashes are joined away");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 19, 20), lease.root().lines(),
                "the document owns its header comments, attribute lines, anchor and title; blank line 18 belongs to no block");
    }

    @Test
    void everyNonBlankLineIsOwnedByExactlyOneBlock() {
        assertOwnedOnce(lease);
    }

    @Test
    void sectionsNestByLevel() {
        List<Block> top = lease.root().children().stream().filter(b -> b.kind() == Block.Kind.SECTION).toList();
        assertEquals(15, top.size(), "fifteen level-1 sections");
        assertEquals("The missing invariant", top.get(0).title());
        assertEquals(32, top.get(0).headingLine());
        assertEquals(1, top.get(0).level());
        assertEquals("Change Log", top.getLast().title());
        assertEquals(829, top.getLast().lastLine());
        Block materialization = top.get(6);
        assertEquals("Git-state materialization at the open gesture", materialization.title());
        Block firstUnderIt = materialization.children().getFirst();
        assertEquals("dev-working-set-lease-materialization", firstUnderIt.anchor(), "an anchor after a heading anchors the next block");
    }

    @Test
    void listingKeepsItsAttributeLineAndDelimiters() {
        Block listing = find(Block.Kind.LISTING, 137);
        assertEquals("[source,yaml]", listing.parameters());
        assertEquals(137, listing.firstLine());
        assertEquals(149, listing.lastLine());
        assertEquals(13, listing.lines().size(), "attribute line, opening ----, ten lines, closing ----");
        assertTrue(lease.referencesOn(146).isEmpty(), "no references are read inside a listing");
    }

    @Test
    void tableHasTitleParametersAndRows() {
        Block table = find(Block.Kind.TABLE, 177);
        assertEquals("TTL semantics", table.title());
        assertEquals("[cols=\"1,2,2\", options=\"header\"]", table.parameters());
        assertEquals(189, table.lastLine());
        List<Block> rows = table.children();
        assertEquals(3, rows.size());
        assertEquals(List.of(180), rows.get(0).lines());
        assertEquals(List.of(182, 183, 184), rows.get(1).lines());
        assertEquals(List.of(186, 187, 188), rows.get(2).lines());
        assertEquals(Block.Kind.TABLE_ROW, rows.get(0).kind());
    }

    @Test
    void admonitionParagraphTakesItsLabelAsParameters() {
        Block important = find(Block.Kind.ADMONITION, 209);
        assertEquals("[IMPORTANT]", important.parameters());
        assertEquals(209, important.firstLine());
    }

    @Test
    void descriptionListCarriesTermsAndContinuationBlocks() {
        Block list = find(Block.Kind.LIST, 215);
        assertEquals("[description]", list.parameters());
        List<Block> items = list.children();
        assertEquals(4, items.size(), "the four components");
        assertEquals("Lease daemon (per machine)", items.get(0).title());
        assertEquals("IntelliJ plugin (Java)", items.get(1).title());
        assertEquals("Claude Code hook", items.get(2).title());
        assertEquals("SSH", items.get(3).title());
        Block plugin = items.get(1);
        assertTrue(plugin.lines().contains(241), "the + line belongs to the item");
        assertEquals(2, plugin.children().size(), "two paragraphs attached by +");
        assertEquals(Block.Kind.PARAGRAPH, plugin.children().get(0).kind());
        assertEquals(242, plugin.children().get(0).firstLine());
        assertEquals(245, plugin.children().get(0).lastLine());
        assertEquals(254, plugin.lastLine(), "the item ends before the blank line that precedes the next term");
    }

    @Test
    void orderedListHasFourSteps() {
        Block steps = find(Block.Kind.LIST, 291);
        assertEquals("[ordered]", steps.parameters());
        assertEquals(List.of(291, 293, 300, 304), steps.children().stream().map(Block::firstLine).toList());
    }

    @Test
    void inlineReferencesAreFoundOnTheirLines() {
        Reference flow = lease.referencesOn(22).getFirst();
        assertEquals(Reference.Kind.INDEX_TERM, flow.kind());
        assertEquals("working-set lease", flow.target());
        assertEquals("flow", flow.text());
        Reference macro = lease.referencesOn(50).getFirst();
        assertEquals(Reference.Kind.INDEX_TERM, macro.kind());
        assertEquals("multi-machine development, single-writer", macro.target());
        Reference headerXref = lease.referencesOn(11).getFirst();
        assertEquals(Reference.Kind.CROSS_REFERENCE, headerXref.kind());
        assertEquals("dev-workspace-syncthing", headerXref.target());
        assertEquals("xref:dev-workspace-syncthing[]", headerXref.raw());
        List<Reference> shortXrefs = lease.references().stream()
                .filter(r -> r.target().equals("dev-working-set-lease-materialization") && r.raw().startsWith("<<")).toList();
        assertEquals(List.of(225, 245, 459), shortXrefs.stream().map(Reference::line).toList());
        assertEquals(Reference.Kind.CROSS_REFERENCE, shortXrefs.getFirst().kind());
        assertEquals(32, lease.references().size(), "21 index terms and 11 cross references, none from the listing");
        assertEquals(21, lease.references().stream().filter(r -> r.kind() == Reference.Kind.INDEX_TERM).count());
        assertEquals(150, lease.blocks().size(), "15 sections, 48 paragraphs, 14 lists with 51 items, 4 tables with 15 rows, a listing, an admonition, the document");
    }

    @Test
    void bibliographyEntriesTurnMatchingShortReferencesIntoCitations() {
        Document doc = IkeDialectParser.parse("""
                [[arch-refs]]
                = References

                See <<aho1977>> and <<other-topic,that one>>.

                [bibliography]
                - [[[aho1977]]] Aho, A.V. (1977). _Principles_.
                - [[[codd1970]]] Codd, E.F. (1970). A relational model.
                """);
        assertOwnedOnce(doc);
        Block list = doc.blocks().stream().filter(b -> b.kind() == Block.Kind.LIST).findFirst().orElseThrow();
        assertEquals("[bibliography]", list.parameters());
        assertEquals(List.of("aho1977", "codd1970"), list.children().stream().map(Block::anchor).toList());
        assertEquals(Block.Kind.BIBLIOGRAPHY_ENTRY, list.children().getFirst().kind());
        List<Reference> refs = doc.referencesOn(4);
        assertEquals(Reference.Kind.CITATION, refs.get(0).kind());
        assertEquals("aho1977", refs.get(0).target());
        assertEquals(Reference.Kind.CROSS_REFERENCE, refs.get(1).kind());
        assertEquals("other-topic", refs.get(1).target());
        assertEquals("that one", refs.get(1).text());
    }

    @Test
    void listsNestAndContinuationsAttachBlocks() {
        Document doc = IkeDialectParser.parse("""
                = Nesting

                * one
                ** one-a
                * two
                +
                [anf]
                --
                statement
                --

                //-
                . first
                * mixed nested
                . second

                image::pic.svg[Alt text]
                include::other.adoc[leveloffset=+1]
                ifndef::env-site[]
                hidden
                endif::[]
                """);
        assertOwnedOnce(doc);
        List<Block> top = doc.root().children();
        Block bullets = top.get(0);
        assertEquals(Block.Kind.LIST, bullets.kind());
        assertEquals(2, bullets.children().size());
        Block one = bullets.children().get(0);
        assertEquals(1, one.children().size());
        assertEquals(Block.Kind.LIST, one.children().getFirst().kind());
        assertEquals(2, one.children().getFirst().level());
        Block two = bullets.children().get(1);
        assertEquals(Block.Kind.OPEN, two.children().getFirst().kind());
        assertEquals("[anf]", two.children().getFirst().parameters());
        assertEquals("statement", doc.line(two.children().getFirst().children().getFirst().firstLine()));
        assertEquals(Block.Kind.OPAQUE, top.get(1).kind(), "the //- separator line");
        Block steps = top.get(2);
        assertEquals("[ordered]", steps.parameters());
        assertEquals(2, steps.children().size());
        assertEquals(Block.Kind.LIST, steps.children().get(0).children().getFirst().kind(), "a list of another kind nests");
        Block image = doc.blocks().stream().filter(b -> b.kind() == Block.Kind.IMAGE).findFirst().orElseThrow();
        assertEquals("pic.svg", image.title());
        assertEquals("[Alt text]", image.parameters());
        Reference include = doc.references().stream().filter(r -> r.kind() == Reference.Kind.INCLUDE).findFirst().orElseThrow();
        assertEquals("other.adoc", include.target());
        assertEquals("leveloffset=+1", include.text());
        Reference conditional = doc.references().stream().filter(r -> r.kind() == Reference.Kind.CONDITIONAL).findFirst().orElseThrow();
        assertEquals("env-site", conditional.target());
        assertTrue(conditional.negated());
    }

    @Test
    void verbatimAndCommentLinesYieldNoReferencesAndEstimateMarksAreRead() {
        Document doc = IkeDialectParser.parse("""
                = Marks

                // a comment with xref:ignored[] in it
                Around 1,200 patientspass:[<sup class="ours-mark" title="our estimate">†</sup><span class="ours-note">Our estimate, open to revision.</span>] a year.

                ----
                <<not-a-ref>> indexterm:[not, one]
                ----
                A footnote.footnote:[Says who.] See link:https://example.org/x[the site] and https://example.org/y.
                """);
        assertOwnedOnce(doc);
        assertTrue(doc.referencesOn(3).isEmpty());
        assertTrue(doc.referencesOn(7).isEmpty());
        Reference estimate = doc.referencesOn(4).getFirst();
        assertEquals(Reference.Kind.ESTIMATE_MARK, estimate.kind());
        assertEquals("Our estimate, open to revision.", estimate.target());
        List<Reference> last = doc.referencesOn(9);
        assertEquals(List.of(Reference.Kind.FOOTNOTE, Reference.Kind.LINK, Reference.Kind.LINK), last.stream().map(Reference::kind).toList());
        assertEquals("Says who.", last.get(0).target());
        assertEquals("https://example.org/x", last.get(1).target());
        assertEquals("the site", last.get(1).text());
        assertEquals("https://example.org/y", last.get(2).target());
    }

    @Test
    void textWithoutTrailingNewlineAndEmptyTextParse() {
        assertEquals(2, IkeDialectParser.parse("= T\nline").lines().size());
        assertEquals(2, IkeDialectParser.parse("= T\nline\n").lines().size());
        Document empty = IkeDialectParser.parse("");
        assertEquals("", empty.title());
        assertTrue(empty.blocks().size() == 1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Block find(Block.Kind kind, int firstLine) {
        return lease.blocks().stream().filter(b -> b.kind() == kind && b.firstLine() == firstLine).findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " starting at line " + firstLine + " among\n"
                        + String.join("\n", lease.blocks().stream().map(Block::toString).toList())));
    }

    private static void assertOwnedOnce(Document doc) {
        Map<Integer, Integer> owners = new HashMap<>();
        for (Block block : doc.blocks()) {
            for (int line : block.lines()) {
                owners.merge(line, 1, Integer::sum);
            }
        }
        for (int i = 1; i <= doc.lines().size(); i++) {
            int count = owners.getOrDefault(i, 0);
            if (doc.line(i).isBlank()) {
                assertTrue(count <= 1, "blank line " + i + " owned " + count + " times");
            } else {
                assertEquals(1, count, "line " + i + " '" + doc.line(i) + "' owned " + count + " times");
            }
        }
    }
}
