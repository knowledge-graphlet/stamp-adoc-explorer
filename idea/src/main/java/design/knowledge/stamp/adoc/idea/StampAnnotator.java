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

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.StampFile;
import git4idea.GitUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Opens, closes and refreshes the STAMP gutter of an editor: the history
 * walk runs in the background, the gutter is registered on the EDT.
 */
final class StampAnnotator {

    private StampAnnotator() { }

    static boolean isOpen(Editor editor) {
        return editor.getUserData(StampGutter.KEY) != null;
    }

    static void close(Editor editor) {
        StampGutter open = editor.getUserData(StampGutter.KEY);
        if (open != null) {
            editor.getGutter().closeTextAnnotations(List.of(open));   // gutterClosed() clears the key
        }
    }

    /** Re-extract and re-register, keeping the gutter open; a no-op when it is closed. */
    static void refresh(Project project, Editor editor, VirtualFile file) {
        if (isOpen(editor)) {
            close(editor);
            open(project, editor, file);
        }
    }

    static void open(Project project, Editor editor, VirtualFile file) {
        new Task.Backgroundable(project, "Extracting STAMPs for " + file.getName(), true) {
            private StampFile stamps;
            private Path relative;
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    VirtualFile root = GitUtil.getRootForFile(project, file);
                    Path rootPath = root.toNioPath();
                    relative = rootPath.relativize(file.toNioPath());
                    try (GitRepo repo = GitRepo.open(rootPath)) {
                        stamps = repo.stampHistoryOf(relative).atHead();
                    }
                } catch (VcsException | IOException | RuntimeException ex) {
                    failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    StampNotifications.error(project, "STAMPs unavailable for " + file.getName(), failure);
                    return;
                }
                if (editor.isDisposed() || isOpen(editor)) {
                    return;
                }
                if (stamps.stamps().isEmpty()) {
                    StampNotifications.info(project, "No STAMPs yet", relative + " has no committed lines at HEAD.");
                    return;
                }
                StampGutter gutter = new StampGutter(project, editor, file, relative, stamps);
                editor.getGutter().registerTextAnnotation(gutter, gutter);
                editor.putUserData(StampGutter.KEY, gutter);
                if (!stamps.problems().isEmpty()) {
                    StampNotifications.warn(project, stamps.problems().size() + " affirmation(s) not applied to " + file.getName(),
                            String.join("<br>", stamps.problems()));
                }
            }
        }.queue();
    }
}
