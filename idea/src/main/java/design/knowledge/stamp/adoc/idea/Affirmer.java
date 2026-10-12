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
import com.intellij.openapi.localVcs.UpToDateLineNumberProvider;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.impl.UpToDateLineNumberProviderImpl;
import com.intellij.openapi.vfs.VirtualFile;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Affirmation;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns editor lines into {@code Affirms} trailers and writes the empty
 * signed commit that carries them.
 *
 * <p>Editor lines are mapped to HEAD lines through the platform's
 * up-to-date line mapping; a line changed since HEAD cannot be affirmed,
 * because an affirmation is anchored to a revision and the lines must be
 * committed before they are reviewed. Contiguous HEAD lines fold into one
 * trailer each. The hash is computed over the committed content, never the
 * editor's.
 */
final class Affirmer {

    private Affirmer() { }

    /**
     * Affirm the given editor lines of {@code file}, after confirmation.
     *
     * @param project     the project
     * @param editor      the editor showing the file
     * @param file        the file
     * @param relative    the file's path relative to its repository root
     * @param editorLines 0-based editor lines to affirm
     */
    static void affirmEditorLines(Project project, Editor editor, VirtualFile file, Path relative, List<Integer> editorLines) {
        UpToDateLineNumberProvider upToDate = new UpToDateLineNumberProviderImpl(editor.getDocument(), project);
        List<Integer> headLines = new ArrayList<>();
        for (int line : editorLines) {
            int head = upToDate.getLineNumber(line);
            if (head < 0) {
                StampNotifications.warn(project, "Commit before affirming",
                        "Line " + (line + 1) + " of " + file.getName() + " differs from HEAD. An affirmation is anchored to a revision, so commit the lines first.");
                return;
            }
            headLines.add(head + 1);                          // 1-based, as trailers are written
        }
        if (headLines.isEmpty()) {
            return;
        }
        List<int[]> ranges = fold(headLines);
        new Task.Backgroundable(project, "Hashing lines of " + file.getName(), false) {
            private VirtualFile root;
            private final List<Affirmation> affirmations = new ArrayList<>();
            private String failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    root = GitUtil.getRootForFile(project, file);
                    try (GitRepo repo = GitRepo.open(root.toNioPath())) {
                        String revision = repo.headRevision();
                        List<String> content = repo.headLines(relative);
                        for (int[] range : ranges) {
                            affirmations.add(Affirmation.of(relative, range[0], range[1], revision, content));
                        }
                    }
                } catch (VcsException | IOException | RuntimeException ex) {
                    failure = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    StampNotifications.error(project, "Cannot affirm " + file.getName(), failure);
                    return;
                }
                confirmAndCommit(project, editor, file, root, relative, affirmations);
            }
        }.queue();
    }

    /** Fold sorted, possibly repeated line numbers into inclusive [from, to] ranges. */
    static List<int[]> fold(List<Integer> lines) {
        List<Integer> sorted = lines.stream().distinct().sorted().collect(Collectors.toList());
        List<int[]> ranges = new ArrayList<>();
        int[] current = null;
        for (int line : sorted) {
            if (current != null && line == current[1] + 1) {
                current[1] = line;
            } else {
                current = new int[] {line, line};
                ranges.add(current);
            }
        }
        return ranges;
    }

    private static void confirmAndCommit(Project project, Editor editor, VirtualFile file, VirtualFile root, Path relative,
                                         List<Affirmation> affirmations) {
        String trailers = affirmations.stream().map(Affirmation::trailer).collect(Collectors.joining("\n"));
        int lines = affirmations.stream().mapToInt(Affirmation::lineCount).sum();
        String subject = "Affirm " + relative + " " + affirmations.stream()
                .map(a -> a.from() + "-" + a.to()).collect(Collectors.joining(", "));
        int choice = Messages.showYesNoCancelDialog(project,
                "An empty signed commit will record your review of " + lines + " line" + (lines == 1 ? "" : "s") + ":\n\n"
                        + subject + "\n\n" + trailers + "\n\nSign the commit with -S (recommended; non-repudiation needs it)?",
                "Affirm Lines", "Signed Commit", "Unsigned Commit", "Cancel", Messages.getQuestionIcon());
        if (choice == Messages.CANCEL) {
            return;
        }
        boolean sign = choice == Messages.YES;
        new Task.Backgroundable(project, "Writing affirmation commit", false) {
            private GitCommandResult result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // The affirmation must stay empty: anything staged would ride along with it.
                GitLineHandler staged = new GitLineHandler(project, root, GitCommand.DIFF);
                staged.addParameters("--cached", "--quiet");
                staged.setSilent(true);
                if (!Git.getInstance().runCommand(staged).success()) {
                    result = GitCommandResult.error("The index has staged changes. Commit or unstage them first, so the affirmation commit records nothing but the trailers.");
                    return;
                }
                GitLineHandler handler = new GitLineHandler(project, root, GitCommand.COMMIT);
                handler.addParameters("--allow-empty");
                if (sign) {
                    handler.addParameters("-S");
                }
                handler.addParameters("-m", subject);
                for (Affirmation a : affirmations) {
                    handler.addParameters("--trailer", a.trailer());
                }
                result = Git.getInstance().runCommand(handler);
            }

            @Override
            public void onSuccess() {
                if (result.success()) {
                    StampNotifications.info(project, "Affirmed " + lines + " line" + (lines == 1 ? "" : "s") + " of " + relative.getFileName(),
                            trailers.replace("\n", "<br>"));
                    StampAnnotator.refresh(project, editor, file);        // the gutter shows the new check marks
                } else {
                    StampNotifications.error(project, "Affirmation commit failed", result.getErrorOutputAsJoinedString());
                }
            }
        }.queue();
    }
}
