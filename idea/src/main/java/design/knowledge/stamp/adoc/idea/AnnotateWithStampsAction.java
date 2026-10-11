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
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
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
 * Toggles the STAMP gutter on the current editor: the history walk runs in
 * the background, then the gutter is registered on the EDT. A second
 * invocation closes it.
 */
public final class AnnotateWithStampsAction extends AnAction implements DumbAware {

    /** The platform instantiates actions reflectively. */
    public AnnotateWithStampsAction() { }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean usable = e.getProject() != null && editor != null && file != null && file.isInLocalFileSystem();
        e.getPresentation().setEnabledAndVisible(usable);
        if (usable) {
            e.getPresentation().setText(editor.getUserData(StampGutter.KEY) != null
                    ? "Close STAMP Annotations" : "Annotate with STAMPs");
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null || file == null) {
            return;
        }
        StampGutter open = editor.getUserData(StampGutter.KEY);
        if (open != null) {
            editor.getGutter().closeTextAnnotations(List.of(open));   // gutterClosed() clears the key
            return;
        }
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
                if (editor.isDisposed() || editor.getUserData(StampGutter.KEY) != null) {
                    return;
                }
                if (stamps.stamps().isEmpty()) {
                    StampNotifications.info(project, "No STAMPs yet", relative + " has no committed lines at HEAD.");
                    return;
                }
                StampGutter gutter = new StampGutter(project, editor, file, relative, stamps);
                editor.getGutter().registerTextAnnotation(gutter, gutter);
                editor.putUserData(StampGutter.KEY, gutter);
            }
        }.queue();
    }
}
