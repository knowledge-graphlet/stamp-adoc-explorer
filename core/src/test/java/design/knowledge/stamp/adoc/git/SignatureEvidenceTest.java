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

import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.Person;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignatureEvidenceTest {

    private static final GitIdentityAdapter IDENTITY = new GitIdentityAdapter(Map.of(
            "kec@old.example", new Person("Keith", "kec@knowledge.design")));
    private static final Person KEITH = new Person("Keith", "kec@old.example");

    @Test
    void aGoodSignatureByTheAnnotatorIsDirectAfterConsolidation() {
        assertEquals(SignatureEvidence.DIRECT, SignatureEvidence.of("G", "kec@knowledge.design", KEITH, IDENTITY));
        assertEquals(SignatureEvidence.DIRECT, SignatureEvidence.of("G", "Keith <KEC@old.example>", KEITH, IDENTITY));
    }

    @Test
    void aGoodSignatureBySomeoneElseIsProxy() {
        assertEquals(SignatureEvidence.PROXY, SignatureEvidence.of("G", "exporter@knowledge.design", KEITH, IDENTITY));
        assertEquals(SignatureEvidence.PROXY, SignatureEvidence.of("G", "", KEITH, IDENTITY), "verified, but unnamed");
    }

    @Test
    void anythingGitDidNotVerifyIsUnsigned() {
        for (String status : new String[] {"N", "B", "U", "E", "X", "Y", "R", ""}) {
            assertEquals(SignatureEvidence.UNSIGNED, SignatureEvidence.of(status, "kec@knowledge.design", KEITH, IDENTITY), status);
        }
    }

    @TempDir
    Path root;

    /** Real SSH-signed commits, verified by git against an allowed-signers file, as the walker would see them. */
    @Test
    void gitsOwnVerificationClassifiesSshSignedCommits() throws Exception {
        Assumptions.assumeTrue(run(root, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "rita", "-f", root.resolve("rita").toString()) == 0,
                "ssh-keygen is not available");
        Path repo = Files.createDirectories(root.resolve("repo"));
        Files.writeString(root.resolve("allowed"), "rita@example.test " + Files.readString(root.resolve("rita.pub")));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Rita");
        git(repo, "config", "user.email", "rita@example.test");
        git(repo, "config", "gpg.format", "ssh");
        git(repo, "config", "user.signingkey", root.resolve("rita").toString());
        git(repo, "config", "gpg.ssh.allowedSignersFile", root.resolve("allowed").toString());
        git(repo, "commit", "-q", "--allow-empty", "-S", "-m", "signed");
        String signed = head(repo);
        git(repo, "commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "unsigned");
        String unsigned = head(repo);
        GitIdentityAdapter raw = GitIdentityAdapter.raw();

        assertEquals(SignatureEvidence.DIRECT, SignatureEvidence.classify(repo, signed, new Person("Rita", "rita@example.test"), raw));
        assertEquals(SignatureEvidence.PROXY, SignatureEvidence.classify(repo, signed, new Person("Kerry", "kerry@example.test"), raw),
                "rita signed what kerry said");
        assertEquals(SignatureEvidence.UNSIGNED, SignatureEvidence.classify(repo, unsigned, new Person("Rita", "rita@example.test"), raw));
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        assertEquals(0, run(repo, command.toArray(String[]::new)), String.join(" ", command));
    }

    private static String head(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo.toFile()).start();
        String sha = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return sha;
    }

    private static int run(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }
}
