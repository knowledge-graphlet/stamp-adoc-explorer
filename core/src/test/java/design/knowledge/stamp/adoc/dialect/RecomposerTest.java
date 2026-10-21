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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Decision 6: a sentence on its own line, surviving fragments keeping their lines, only changed text broken anew. */
class RecomposerTest {

    /** Edits the sentence that starts with {@code opening} and returns the document's new lines. */
    private static List<String> edit(String document, String opening, String replaced) {
        Document parsed = IkeDialectParser.parse(document);
        Segmenter.Segmentation segments = Segmenter.of(parsed);
        Segmenter.Sentence sentence = segments.sentences().stream().filter(s -> s.text().startsWith(opening)).findFirst().orElseThrow();
        List<String> lines = Recomposer.replace(parsed.lines(), sentence.flow(), sentence.start(), sentence.end(), replaced);
        // the result parses to the same blocks, and the edited sentence reads as written
        assertEquals(parsed.blocks().stream().map(Block::kind).toList(),
                IkeDialectParser.parse(lines).blocks().stream().map(Block::kind).toList(), "block structure unchanged");
        assertEquals(true, Segmenter.of(IkeDialectParser.parse(lines)).texts().contains(Segmenter.normalize(replaced)), String.join("\n", lines));
        return lines;
    }

    @Test
    void underSemanticLineBreaksOnlyTheChangedFragmentMoves() {
        String document = """
                = T

                The lease is a loan.
                A machine takes it,
                renews it while working,
                and returns it when done.
                Then it is free.
                """;
        List<String> lines = edit(document, "A machine", "A machine takes it, renews it while working, and returns it when the work is done.");
        assertEquals(List.of("= T", "", "The lease is a loan.", "A machine takes it,", "renews it while working,",
                "and returns it when the work is done.", "Then it is free."), lines,
                "the author's own breaks inside the sentence are kept; only the changed clause is rewritten");
    }

    @Test
    void anInsertionMidSentenceKeepsTheFragmentsAroundIt() {
        String document = """
                The lease is a loan.
                A machine takes it,
                renews it while working,
                and returns it when done.
                """;
        List<String> lines = edit(document, "A machine",
                "A machine takes it, checks its record, renews it while working, and returns it when done.");
        assertEquals(List.of("The lease is a loan.", "A machine takes it,", "checks its record,", "renews it while working,",
                "and returns it when done."), lines);
    }

    @Test
    void inWrappedProseTheSentenceTakesItsOwnLine() {
        String document = """
                A lease grants one machine the right to write. The record lives in the
                synced folder, so taking a lease and announcing it are the same act. Every
                peer observes the change.
                """;
        List<String> lines = edit(document, "The record", "The record now lives in a shared folder, so taking is announcing.");
        assertEquals(List.of("A lease grants one machine the right to write.",
                "The record now lives in a shared folder, so taking is announcing.", "Every", "peer observes the change."),
                lines, "the sentence before ends its line, the one after begins a line, and nothing else moves");
        List<String> kept = edit(document, "The record", "The record lives in the shared folder, so taking and announcing are one act.");
        assertEquals(List.of("A lease grants one machine the right to write. The record lives in the",
                "shared folder, so taking and announcing are one act.", "Every", "peer observes the change."),
                kept, "a surviving first fragment keeps its line, the sentence before it included");
    }

    @Test
    void aSurvivingFirstFragmentKeepsItsLineNeighbourIncluded() {
        String document = """
                A lease grants one machine the right to write. The record lives in the
                synced folder, so taking a lease and announcing it are the same act.
                """;
        List<String> lines = edit(document, "The record", "The record lives in the synced folder, and that is all.");
        assertEquals("A lease grants one machine the right to write. The record lives in the", lines.getFirst(), "kept exactly");
        assertEquals("synced folder, and that is all.", lines.get(1));
    }

    @Test
    void aListItemKeepsItsMarker() {
        String document = """
                * The TTL is a staleness horizon.
                * A recall never waits.
                """;
        List<String> lines = edit(document, "A recall", "A recall never waits for anyone.");
        assertEquals(List.of("* The TTL is a staleness horizon.", "* A recall never waits for anyone."), lines);
    }

    @Test
    void changedTextIsBrokenAfterAClauseOnlyPastTheMeasure() {
        String document = "Short.\nThe old sentence here.\n";
        String longer = "The new sentence runs on, and on past the measure of seventy-two columns, so it breaks after a comma.";
        List<String> lines = edit(document, "The old", longer);
        assertEquals(List.of("Short.", "The new sentence runs on,", "and on past the measure of seventy-two columns,",
                "so it breaks after a comma."), lines, "greedy: each break the last clause or comma before the measure");
    }

    @Test
    void noNewLineBeginsAsStructure() {
        String document = "Intro text here. Old words.\n";
        List<String> lines = edit(document, "Old", "The value is shown as, == Heading, in the source.");
        assertEquals(List.of("Intro text here.", "The value is shown as, == Heading, in the source."), lines);
    }
}
