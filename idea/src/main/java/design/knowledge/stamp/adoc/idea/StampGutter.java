/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.idea;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.localVcs.UpToDateLineNumberProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vcs.actions.ActiveAnnotationGutter;
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gutter column: one STAMP per committed line, with a mark when the
 * line has been annotated — ✗ refuted, ✓ affirmed, … commented on — and
 * blank for lines the editor has changed since HEAD (their STAMP is made at
 * commit time). Clicking a line highlights every line its STAMP introduced;
 * the popup offers the id for copying, annotation of the STAMP's lines, and
 * the review queue — the lines no one has affirmed yet.
 */
final class StampGutter implements ActiveAnnotationGutter {

    static final Key<StampGutter> KEY = Key.create("stamp.adoc.gutter");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(ZoneId.systemDefault());
    private static final int AUTHOR_WIDTH = 18;

    /** Pastel backgrounds, light and dark, cycled in STAMP time order. */
    private static final JBColor[] PALETTE = {
            new JBColor(new Color(0xE3F2FD), new Color(0x1F3A5A)),
            new JBColor(new Color(0xE8F5E9), new Color(0x1F4A2B)),
            new JBColor(new Color(0xFFF8E1), new Color(0x5A4A1F)),
            new JBColor(new Color(0xF3E5F5), new Color(0x4A2B5A)),
            new JBColor(new Color(0xFFEBEE), new Color(0x5A2B2F)),
            new JBColor(new Color(0xE0F7FA), new Color(0x1F4A5A)),
            new JBColor(new Color(0xFBE9E7), new Color(0x5A3A2B)),
            new JBColor(new Color(0xF1F8E9), new Color(0x3A4A1F)),
    };

    private final Project project;
    private final Editor editor;
    private final VirtualFile file;
    private final Path relative;
    private final StampFile stamps;
    private final StampRecord[] byHeadLine;            // 1-based
    private final List<List<Note>> notesByHeadLine;     // 1-based, null = unannotated
    private final Map<StampId, Integer> order = new HashMap<>();
    private final UpToDateLineNumberProvider upToDate;
    private final List<RangeHighlighter> highlights = new ArrayList<>();
    private @Nullable StampId highlighted;
    private boolean queueShown;

    /** One annotation of one line: the annotating STAMP and the kind. */
    record Note(StampRecord stamp, AnnotationKind kind) { }

    private static final JBColor QUEUE = new JBColor(new Color(0xFFF3CD), new Color(0x5A4A1F));

    StampGutter(Project project, Editor editor, VirtualFile file, Path relative, StampFile stamps) {
        this.project = project;
        this.editor = editor;
        this.file = file;
        this.relative = relative;
        this.stamps = stamps;
        int last = stamps.stamps().values().stream()
                .mapToInt(r -> java.util.stream.Stream.of(r.introduced(), r.affirmed(), r.refuted(), r.commented())
                        .mapToInt(b -> b.isEmpty() ? 0 : b.last()).max().orElse(0))
                .max().orElse(0);
        this.byHeadLine = new StampRecord[last + 1];
        this.notesByHeadLine = new ArrayList<>(java.util.Collections.nCopies(last + 1, (List<Note>) null));
        List<StampRecord> byTime = new ArrayList<>(stamps.stamps().values());
        byTime.sort(Comparator.comparing(StampRecord::time));
        for (int i = 0; i < byTime.size(); i++) {
            StampRecord r = byTime.get(i);
            order.put(r.id(), i);
            r.introduced().forEach((int line) -> byHeadLine[line] = r);
            note(r, AnnotationKind.AFFIRMATION, r.affirmed());
            note(r, AnnotationKind.REFUTATION, r.refuted());
            note(r, AnnotationKind.COMMENT, r.commented());
        }
        this.upToDate = new UpToDateLineNumberProviderImpl(editor.getDocument(), project);
    }

    private void note(StampRecord r, AnnotationKind kind, org.roaringbitmap.RoaringBitmap lines) {
        lines.forEach((int line) -> {
            if (notesByHeadLine.get(line) == null) {
                notesByHeadLine.set(line, new ArrayList<>(2));
            }
            notesByHeadLine.get(line).add(new Note(r, kind));
        });
    }

    /** The STAMP of an editor line, or null when the line is changed since HEAD or outside it. */
    @Nullable StampRecord at(int editorLine) {
        int head = upToDate.getLineNumber(editorLine);
        if (head < 0) {
            return null;
        }
        int number = head + 1;
        return number < byHeadLine.length ? byHeadLine[number] : null;
    }

    /** The annotations of an editor line, or null when there are none or the line is changed since HEAD. */
    @Nullable List<Note> notesAt(int editorLine) {
        int head = upToDate.getLineNumber(editorLine);
        if (head < 0) {
            return null;
        }
        int number = head + 1;
        return number < notesByHeadLine.size() ? notesByHeadLine.get(number) : null;
    }

    private boolean has(@Nullable List<Note> notes, AnnotationKind kind) {
        return notes != null && notes.stream().anyMatch(n -> n.kind() == kind);
    }

    /** The line's mark: ✗ when anyone refutes it, else ✓ when anyone affirms it, else … when it carries a comment. */
    private String markOf(int editorLine) {
        List<Note> notes = notesAt(editorLine);
        return has(notes, AnnotationKind.REFUTATION) ? "  ✗"
                : has(notes, AnnotationKind.AFFIRMATION) ? "  ✓"
                : has(notes, AnnotationKind.COMMENT) ? "  …" : "";
    }

    /** Editor lines (0-based) that have a STAMP but no affirmation: the review queue. Refuted lines stay in it. */
    private List<Integer> unaffirmedEditorLines() {
        List<Integer> lines = new ArrayList<>();
        int count = editor.getDocument().getLineCount();
        for (int line = 0; line < count; line++) {
            if (at(line) != null && !has(notesAt(line), AnnotationKind.AFFIRMATION)) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** Editor lines (0-based) currently showing {@code stamp}, through the up-to-date mapping. */
    private List<Integer> editorLinesOf(StampId stamp) {
        List<Integer> lines = new ArrayList<>();
        int count = editor.getDocument().getLineCount();
        for (int line = 0; line < count; line++) {
            StampRecord r = at(line);
            if (r != null && r.id().equals(stamp)) {
                lines.add(line);
            }
        }
        return lines;
    }

    private Color backgroundOf(StampRecord r) {
        return PALETTE[order.get(r.id()) % PALETTE.length];
    }

    static String shortSha(StampRecord r) {
        return r.commitSha().substring(0, 7);
    }

    // ── TextAnnotationGutterProvider ──────────────────────────────────────────

    @Override
    public @Nullable String getLineText(int line, Editor editor) {
        StampRecord r = at(line);
        if (r == null) {
            return "";
        }
        String author = r.author().name();
        if (author.length() > AUTHOR_WIDTH) {
            author = author.substring(0, AUTHOR_WIDTH - 1) + "…";
        }
        return shortSha(r) + "  " + DAY.format(r.time()) + "  " + author + markOf(line);
    }

    @Override
    public @Nullable String getToolTip(int line, Editor editor) {
        StampRecord r = at(line);
        if (r == null) {
            return upToDate.isLineChanged(line)
                    ? "<html><b>Uncommitted</b><br>This line differs from HEAD; its STAMP is made when it is committed.</html>"
                    : null;
        }
        return "<html><b>STAMP " + shortSha(r) + "</b> &nbsp;" + escape(r.summary()) + "<br>"
                + "<b>S</b>tatus: " + r.status().name().toLowerCase() + "<br>"
                + "<b>T</b>ime: " + MOMENT.format(r.time()) + "<br>"
                + "<b>A</b>uthor: " + escape(r.author().name()) + " &lt;" + escape(r.author().email()) + "&gt;<br>"
                + "<b>M</b>odule: " + escape(moduleOf(r)) + "<br>"
                + "<b>P</b>ath: " + escape(r.module().toString()) + "<br>"
                + "Introduced " + r.introduced().getCardinality() + " of the " + (byHeadLine.length - 1) + " lines at HEAD"
                + annotationHtml(line)
                + "<br><i>Click to highlight the STAMP's lines.</i></html>";
    }

    private String annotationHtml(int line) {
        List<Note> notes = notesAt(line);
        StringBuilder html = new StringBuilder();
        if (!has(notes, AnnotationKind.AFFIRMATION)) {
            html.append("<br><b>Not yet affirmed.</b>");
        }
        if (notes != null) {
            for (Note n : notes) {
                String label = switch (n.kind()) {
                    case AFFIRMATION -> "✓ Affirmed";
                    case REFUTATION -> "✗ Refuted";
                    case COMMENT -> "… Commented on";
                    case RETRACTION -> "↶ Retracted";          // never a note of its own: a retraction leaves no mark
                };
                StampRecord a = n.stamp();
                html.append("<br><b>").append(label).append("</b> by ").append(escape(a.author().name()))
                        .append(" on ").append(MOMENT.format(a.time()))
                        .append(a.signed() ? ", signed commit " : ", unsigned commit ").append(shortSha(a))
                        .append(": ").append(escape(a.summary()));
            }
        }
        return html.toString();
    }

    private String moduleOf(StampRecord r) {
        Path module = r.module().getParent();
        return module == null ? "." : module.toString();
    }

    @Override
    public EditorFontType getStyle(int line, Editor editor) {
        return EditorFontType.PLAIN;
    }

    /**
     * The scheme's annotation text color, the key Git Annotate paints with.
     * The platform has no fallback here: a null key leaves the Graphics
     * color at the background just filled, and the text paints invisibly.
     */
    private static final ColorKey TEXT = ColorKey.createColorKey("ANNOTATIONS_COLOR");

    @Override
    public ColorKey getColor(int line, Editor editor) {
        return TEXT;
    }

    @Override
    public @Nullable Color getBgColor(int line, Editor editor) {
        StampRecord r = at(line);
        return r == null ? null : backgroundOf(r);
    }

    @Override
    public @Nullable List<AnAction> getPopupActions(int line, Editor editor) {
        StampRecord r = at(line);
        List<AnAction> actions = new ArrayList<>();
        if (r != null) {
            actions.add(new AnAction("Copy STAMP Id " + shortSha(r)) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    CopyPasteManager.getInstance().setContents(new StringSelection(r.commitSha()));
                }
            });
            for (AnnotationKind kind : AnnotationKind.values()) {
                if (kind == AnnotationKind.RETRACTION) {
                    continue;                                    // a retraction names a commit, not lines
                }
                actions.add(new AnAction(kind.verb() + " the " + r.introduced().getCardinality() + " Lines of STAMP " + shortSha(r) + "…") {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        Annotator.annotateEditorLines(project, StampGutter.this.editor, file, relative, kind, editorLinesOf(r.id()));
                    }
                });
            }
        }
        int queue = unaffirmedEditorLines().size();
        actions.add(new AnAction(queueShown ? "Hide Review Queue" : "Highlight Review Queue (" + queue + " unaffirmed line" + (queue == 1 ? "" : "s") + ")") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                toggleQueue();
            }
        });
        actions.add(new AnAction("Close STAMP Annotations") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                StampGutter.this.editor.getGutter().closeTextAnnotations(List.of(StampGutter.this));
            }
        });
        return actions;
    }

    @Override
    public void gutterClosed() {
        clearHighlights();
        editor.putUserData(KEY, null);
    }

    // ── EditorGutterAction ────────────────────────────────────────────────────

    private void toggleQueue() {
        clearHighlights();
        highlighted = null;
        queueShown = !queueShown;
        if (!queueShown) {
            return;
        }
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(QUEUE);
        for (int editorLine : unaffirmedEditorLines()) {
            highlights.add(editor.getMarkupModel().addLineHighlighter(editorLine, HighlighterLayer.SELECTION - 1, attributes));
        }
    }

    @Override
    public void doAction(int line) {
        StampRecord r = at(line);
        clearHighlights();
        queueShown = false;
        if (r == null || r.id().equals(highlighted)) {
            highlighted = null;
            return;
        }
        highlighted = r.id();
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(backgroundOf(r));
        for (int editorLine : editorLinesOf(r.id())) {
            highlights.add(editor.getMarkupModel().addLineHighlighter(editorLine, HighlighterLayer.SELECTION - 1, attributes));
        }
    }

    @Override
    public Cursor getCursor(int line) {
        return at(line) == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    }

    private void clearHighlights() {
        for (RangeHighlighter h : highlights) {
            h.dispose();
        }
        highlights.clear();
    }

    /** @return the file this gutter annotates */
    StampFile stamps() {
        return stamps;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
