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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * What a commit's signature says about an annotation it carries
 * (ike-lab-documents topic {@code dev-annotation-signing}). Git is the notary:
 * the class is computed from git whenever it is needed and never stored, so
 * the graph never holds an unverifiable claim about a signature.
 *
 * <p>Verification is git's own ({@code %G?} and {@code %GS}): for SSH
 * signatures the principal comes from the repository's
 * {@code gpg.ssh.allowedSignersFile}, which maps keys to people. The signer
 * and the annotator are compared after mailmap consolidation, so a person
 * who commits under two addresses is one person here too.
 */
public enum SignatureEvidence {

    /** A good signature by the annotator. */
    DIRECT,

    /**
     * A good signature by someone other than the annotator: the signer attests
     * that the commit faithfully records what the annotator said, as when an
     * annotation made in the graph is exported — not that the annotator said it.
     */
    PROXY,

    /** No signature, or one git could not verify. */
    UNSIGNED;

    private static final int TIMEOUT_SECONDS = 30;

    /**
     * @param repoRoot  working-tree root
     * @param sha       the annotation commit
     * @param annotator the annotator: the {@code by} author when the trailer names one, else the commit author
     * @param identity  the repository's identity adapter
     * @return the evidence class
     * @throws IOException if git cannot be started
     */
    public static SignatureEvidence classify(Path repoRoot, String sha, Person annotator, GitIdentityAdapter identity)
            throws IOException {
        GitCli.Run run = GitCli.run(repoRoot, TIMEOUT_SECONDS, List.of("log", "-1", "--format=%G?%n%GS", sha));
        if (!run.ok()) {
            return UNSIGNED;
        }
        List<String> lines = run.output().lines().toList();
        return of(lines.isEmpty() ? "" : lines.get(0), lines.size() < 2 ? "" : lines.get(1), annotator, identity);
    }

    /**
     * The classification from git's verification result.
     *
     * @param status    git's {@code %G?}: {@code G} is a good, trusted signature
     * @param signer    git's {@code %GS}: the signer, an email or {@code Name <email>}
     * @param annotator the annotator
     * @param identity  the identity adapter
     * @return the evidence class
     */
    static SignatureEvidence of(String status, String signer, Person annotator, GitIdentityAdapter identity) {
        if (!"G".equals(status.strip())) {
            return UNSIGNED;
        }
        String email = emailOf(signer);
        if (email.isEmpty()) {
            return PROXY;                                        // verified, but by no one we can name
        }
        String signerEmail = identity.canonical(new Person("", email)).email().toLowerCase(Locale.ROOT);
        String annotatorEmail = identity.canonical(annotator).email().toLowerCase(Locale.ROOT);
        return signerEmail.equals(annotatorEmail) ? DIRECT : PROXY;
    }

    private static String emailOf(String signer) {
        String s = signer.strip();
        int open = s.indexOf('<');
        int close = s.indexOf('>', open + 1);
        if (open >= 0 && close > open) {
            return s.substring(open + 1, close).strip();
        }
        return s.contains("@") ? s : "";
    }
}
