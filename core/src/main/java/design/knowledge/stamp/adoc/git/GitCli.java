/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the user's own {@code git} command line, for the operations whose
 * behavior must be exactly what the user has configured: signing a commit
 * (GPG, SSH, a password manager's agent) and verifying one against their
 * allowed signers. JGit's signers and verifiers are not in the Komet boot
 * image, and would not see that configuration if they were.
 */
final class GitCli {

    private static final List<String> GIT_CANDIDATES = List.of("git", "/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git");

    private GitCli() { }

    /**
     * The outcome of one git invocation.
     *
     * @param exit   the exit status, or -1 when git did not finish in time or was interrupted
     * @param output stdout and stderr combined, stripped
     */
    record Run(int exit, String output) {
        boolean ok() {
            return exit == 0;
        }
    }

    /**
     * @param repoRoot       working directory
     * @param timeoutSeconds how long to wait
     * @param args           arguments, without the executable
     * @return the outcome
     * @throws IOException if git cannot be started
     */
    static Run run(Path repoRoot, int timeoutSeconds, List<String> args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.addAll(args);
        Process process = new ProcessBuilder(command).directory(repoRoot.toFile()).redirectErrorStream(true).start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "git did not finish within " + timeoutSeconds + " seconds (a signing prompt waiting?)"
                        + (output.isEmpty() ? "" : "\n" + output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Run(-1, "interrupted while waiting for git");
        }
        return new Run(process.exitValue(), output);
    }

    private static String executable() {
        for (String candidate : GIT_CANDIDATES) {
            if (candidate.contains("/") ? Files.isExecutable(Path.of(candidate)) : onPath(candidate)) {
                return candidate;
            }
        }
        return "git";
    }

    private static boolean onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }
}
