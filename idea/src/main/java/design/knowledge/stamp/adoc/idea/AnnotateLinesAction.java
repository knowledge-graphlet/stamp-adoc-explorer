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

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitUtil;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Annotates the selected lines (or the caret line) with one kind — affirm,
 * refute, or comment: the range is anchored to HEAD, hashed, and written as
 * an empty commit with one {@code Annotation:} trailer per contiguous range.
 * The platform instantiates one subclass per kind.
 */
public abstract class AnnotateLinesAction extends AnAction implements DumbAware {

    private final AnnotationKind kind;

    AnnotateLinesAction(AnnotationKind kind) {
        this.kind = kind;
    }

    /** Affirm Selected Lines. */
    public static final class Affirm extends AnnotateLinesAction {
        /** The platform instantiates actions reflectively. */
        public Affirm() {
            super(AnnotationKind.AFFIRMATION);
        }
    }

    /** Refute Selected Lines. */
    public static final class Refute extends AnnotateLinesAction {
        /** The platform instantiates actions reflectively. */
        public Refute() {
            super(AnnotationKind.REFUTATION);
        }
    }

    /** Comment on Selected Lines. */
    public static final class Comment extends AnnotateLinesAction {
        /** The platform instantiates actions reflectively. */
        public Comment() {
            super(AnnotationKind.COMMENT);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && editor != null && file != null && file.isInLocalFileSystem());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null || file == null) {
            return;
        }
        List<Integer> lines = selectedLines(editor);
        new Task.Backgroundable(project, "Locating repository for " + file.getName(), false) {
            private Path relative;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    relative = GitUtil.getRootForFile(project, file).toNioPath().relativize(file.toNioPath());
                } catch (VcsException ex) {
                    failure = ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    StampNotifications.error(project, "Cannot annotate " + file.getName(), failure);
                    return;
                }
                Annotator.annotateEditorLines(project, editor, file, relative, kind, lines);
            }
        }.queue();
    }

    /** Editor lines (0-based) covered by the selection, or the caret line. */
    static List<Integer> selectedLines(Editor editor) {
        Document document = editor.getDocument();
        SelectionModel selection = editor.getSelectionModel();
        int startLine;
        int endLine;
        if (selection.hasSelection()) {
            startLine = document.getLineNumber(selection.getSelectionStart());
            int end = selection.getSelectionEnd();
            endLine = document.getLineNumber(end);
            if (endLine > startLine && document.getLineStartOffset(endLine) == end) {
                endLine--;                                   // a selection ending at a line start does not include that line
            }
        } else {
            startLine = endLine = document.getLineNumber(editor.getCaretModel().getOffset());
        }
        List<Integer> lines = new ArrayList<>();
        for (int line = startLine; line <= endLine; line++) {
            lines.add(line);
        }
        return lines;
    }
}
