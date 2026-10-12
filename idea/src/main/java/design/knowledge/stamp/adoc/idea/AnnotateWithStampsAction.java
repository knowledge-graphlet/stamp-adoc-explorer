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
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/** Toggles the STAMP gutter on the current editor. */
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
            e.getPresentation().setText(StampAnnotator.isOpen(editor) ? "Close STAMP Annotations" : "Annotate with STAMPs");
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
        if (StampAnnotator.isOpen(editor)) {
            StampAnnotator.close(editor);
        } else {
            StampAnnotator.open(project, editor, file);
        }
    }
}
