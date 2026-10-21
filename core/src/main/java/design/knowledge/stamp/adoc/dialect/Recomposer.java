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
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recomposes a document's physical lines after one sentence is edited (IKE-Network/ike-issues#1313;
 * decision 6 of {@code design-2026-10-21-stored-text-segments}, "a sentence on its own line").
 * Sentences never author a byte: the result is lines, and a commit carries exactly their breaks.
 *
 * <ul>
 * <li>Only the edited sentence's lines change. The part of its first line before it stays on that
 * line; the part of its last line after it begins a line of its own.</li>
 * <li>A <em>fragment</em>, the part of the sentence on one physical line, whose text survives the
 * edit keeps its line exactly, a neighbouring sentence on the same line included; so an author's
 * own line breaks inside the sentence are kept (KEC, 2026-10-21).</li>
 * <li>Only changed text is broken anew: after a clause or a comma, and only where a line would
 * otherwise run past the measure, the longest line the document already has or 72 columns.</li>
 * <li>A hard line break ({@code " +"}) ends a line, as it did.</li>
 * <li>No new line begins with a token the dialect reads as structure (a heading's {@code =}, a list
 * marker, {@code |}, {@code //}, a wholly bracketed line, an admonition label, a lone {@code +}, a
 * delimiter) or forms a {@code term::} line: the break moves back a word.</li>
 * </ul>
 *
 * <p>The caller parses the result again and refuses it when the block structure changed.
 */
public final class Recomposer {

    private static final int MIN_MEASURE = 72;
    private static final Pattern STRUCTURE = Pattern.compile(
            "^(=+\\s|[*-]+\\s|\\.+\\s|\\d+\\.\\s|\\||//|\\[.*\\]$|\\+$|(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s|-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--$).*");

    private Recomposer() {
    }

    /**
     * The document's lines with one sentence's text replaced.
     *
     * @param lines    the document's lines, line 1 first
     * @param flow     the flow holding the sentence, as {@link Flows} reads it from these lines
     * @param start    the sentence's first character in the flow's text
     * @param end      one past its last
     * @param replaced the sentence's new text; whitespace in it is collapsed, a hard line break kept
     * @return the new lines
     */
    public static List<String> replace(List<String> lines, Flow flow, int start, int end, String replaced) {
        List<String> words = tokens(Segmenter.normalize(replaced));
        int firstPart = flow.partAt(start);
        int lastPart = flow.partAt(Math.max(start, end - 1));
        Flow.Part first = flow.parts().get(firstPart);
        int firstLine = first.line() - 1;
        int lastLine = flow.parts().get(lastPart).line() - 1;
        String head = lines.get(firstLine).substring(0, flow.columnAt(start));
        String tail = lines.get(lastLine).substring(flow.columnAt(end - 1) + 1);
        boolean headHasText = head.length() > first.from() && !head.substring(first.from()).isBlank();

        // the sentence's fragments, one per physical line, and where each survives in the new words
        int n = lastPart - firstPart + 1;
        List<List<String>> fragments = new ArrayList<>();
        for (int k = firstPart; k <= lastPart; k++) {
            Flow.Part part = flow.parts().get(k);
            int from = k == firstPart ? start : part.offset();
            int to = k == lastPart ? end : part.offset() + (part.to() - part.from());
            fragments.add(tokens(Segmenter.normalize(flow.text().substring(from, to))));
        }
        // the new text as pieces in order: a surviving fragment, or a run of changed words
        List<Object> pieces = new ArrayList<>();                 // Integer: a fragment's index; List<String>: changed words
        int cursor = 0;
        for (int k = 0; k < n; k++) {
            int at = find(words, fragments.get(k), cursor);
            if (at < 0) {
                continue;
            }
            if (at > cursor) {
                pieces.add(new ArrayList<>(words.subList(cursor, at)));
            }
            pieces.add(k);
            cursor = at + fragments.get(k).size();
        }
        if (cursor < words.size()) {
            pieces.add(new ArrayList<>(words.subList(cursor, words.size())));
        }

        int measure = MIN_MEASURE;
        for (String line : lines) {
            measure = Math.max(measure, line.length());
        }
        List<String> out = new ArrayList<>(lines.subList(0, firstLine));
        String open = headHasText ? null : head;                 // a marker or label the first piece continues
        if (headHasText && !(pieces.size() > 0 && Integer.valueOf(0).equals(pieces.getFirst()))) {
            out.add(head.stripTrailing());                       // the sentence before it ends the line it shares
        }
        boolean tailPlaced = false;
        for (int p = 0; p < pieces.size(); p++) {
            boolean firstPiece = p == 0;
            boolean lastPiece = p == pieces.size() - 1;
            if (pieces.get(p) instanceof Integer k) {
                String line = lines.get(flow.parts().get(firstPart + k).line() - 1);
                // before a fragment's text on its line: nothing for the first, whose head is placed already; a continuation's indent
                String leading = k == 0 ? "" : line.substring(0, Math.min(flow.parts().get(firstPart + k).from(), line.length()));
                String fragment = String.join(" ", fragments.get(k)).replace(" \n ", "\n");
                boolean opens = k == 0 && firstPiece;
                boolean closes = k == n - 1 && lastPiece;
                if (opens && closes) {
                    out.add(head + fragment + tail);              // the sentence is as it was
                    tailPlaced = true;
                } else if (opens) {
                    out.add(n == 1 ? (head + fragment).stripTrailing() : line);
                } else if (closes) {
                    out.add(n == 1 ? (open == null ? "" : open) + fragment + tail : line);
                    tailPlaced = true;
                } else if (k > 0 && k < n - 1) {
                    out.add(line);                               // a whole line of the sentence, kept exactly
                } else {
                    out.add((firstPiece && open != null ? open : leading) + fragment);
                }
                open = null;
            } else {
                @SuppressWarnings("unchecked")
                List<String> changed = (List<String>) pieces.get(p);
                layOut(changed, open, out, measure);
                open = null;
            }
        }
        if (!tailPlaced && !tail.isBlank()) {
            out.add(tail.stripLeading());                        // the sentence after it begins a line of its own
        }
        int edited = out.size();
        out.addAll(lines.subList(lastLine + 1, lines.size()));
        return guard(out, firstLine, edited);
    }

    /** Lays out changed words on lines of their own, the first after {@code open} when given, broken after a clause or comma past the measure. */
    private static void layOut(List<String> words, String open, List<String> out, int measure) {
        StringBuilder line = new StringBuilder(open == null ? "" : open);
        int base = line.length();
        int lastBreak = -1;                                      // where the line may break: just after a clause or a comma
        for (String w : words) {
            if (w.equals("\n")) {
                out.add(line.toString().stripTrailing());
                line.setLength(0);
                base = 0;
                lastBreak = -1;
                continue;
            }
            if (line.length() > base) {
                line.append(' ');
            }
            line.append(w);
            if (line.length() > measure && lastBreak > 0) {
                out.add(line.substring(0, lastBreak).stripTrailing());
                String rest = line.substring(lastBreak).stripLeading();
                line.setLength(0);
                line.append(rest);
                base = 0;
                lastBreak = -1;
            }
            if (w.endsWith(",") || w.endsWith(";") || w.endsWith(":") || w.equals("—")) {
                lastBreak = line.length();
            }
        }
        if (line.length() > base || (open != null && !open.isBlank() && words.isEmpty())) {
            out.add(line.toString());
        }
    }

    /** Joins a new line that would begin as structure onto the line before it, within lines {@code [from, to)}. */
    private static List<String> guard(List<String> out, int from, int to) {
        for (int i = Math.max(from + 1, 1); i < to && i < out.size(); i++) {
            String line = out.get(i);
            if (STRUCTURE.matcher(line).matches() || LineKinds.DESCRIPTION.matcher(line).matches()) {
                out.set(i - 1, out.get(i - 1) + " " + line);
                out.remove(i);
                to--;
                i--;
            }
        }
        return out;
    }

    /** The words of normalized text, a hard line break as its own token {@code "\n"}. */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (String w : lines[i].trim().split(" +")) {
                if (!w.isEmpty()) {
                    tokens.add(w);
                }
            }
            if (i < lines.length - 1) {
                tokens.add("\n");
            }
        }
        return tokens;
    }

    /** Where a run of words occurs in a list at or after a position, -1 when it does not. */
    private static int find(List<String> words, List<String> run, int from) {
        if (run.isEmpty()) {
            return -1;
        }
        outer:
        for (int i = from; i + run.size() <= words.size(); i++) {
            for (int j = 0; j < run.size(); j++) {
                if (!words.get(i + j).equals(run.get(j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
