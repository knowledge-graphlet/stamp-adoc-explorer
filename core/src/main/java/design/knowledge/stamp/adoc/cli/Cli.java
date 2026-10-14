/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.cli;

import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Headless view of the extractor: {@code Cli <repo-root> <file>} prints one
 * line per STAMP (newest first, with the lines it introduced or annotated),
 * any annotation that could not be applied, and then the file with each
 * line prefixed by its STAMP's short SHA, date and author, and a mark when
 * the line has been annotated: ✗ refuted, ✓ affirmed, … commented on.
 */
public final class Cli {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private Cli() { }

    /**
     * @param args repository root and a file path (absolute or repo-relative)
     * @throws IOException if the repository or the file cannot be read
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Cli <repo-root> <file>");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path file = Path.of(args[1]);
        try (GitRepo repo = GitRepo.open(root)) {
            StampFile snapshot = repo.stampHistoryOf(file).atHead();
            List<StampRecord> records = new ArrayList<>(snapshot.stamps().values());
            records.sort(Comparator.comparing(StampRecord::time).reversed());
            System.out.printf("%s @ %s — %d STAMP(s), %d ignored rev(s)%n",
                    snapshot.file(), snapshot.revisionSha().substring(0, 7), records.size(), snapshot.ignoredRevs().size());
            for (StampRecord r : records) {
                String role = r.introduced().isEmpty()
                        ? annotated(r) + (r.signed() ? " signed" : " unsigned")
                        : "introduced " + r.introduced().getCardinality() + " line(s) " + r.introduced()
                          + (annotated(r).isEmpty() ? "" : ", " + annotated(r));
                System.out.printf("  %s %s %-8s %-28s %s  %s%n", r.commitSha().substring(0, 7), DAY.format(r.time()),
                        r.status().name().toLowerCase(), r.author().email(), role, r.summary());
            }
            for (String problem : snapshot.problems()) {
                System.out.println("  ! " + problem);
            }
            int lines = maxLine(records);
            StampRecord[] byLine = new StampRecord[lines + 1];
            char[] mark = new char[lines + 1];
            for (StampRecord r : records) {
                r.introduced().forEach((int line) -> byLine[line] = r);
            }
            for (StampRecord r : records) {                      // a refutation outranks an affirmation, either outranks a comment
                r.commented().forEach((int line) -> mark[line] = mark[line] == 0 ? '…' : mark[line]);
                r.affirmed().forEach((int line) -> mark[line] = mark[line] == '✗' ? '✗' : '✓');
                r.refuted().forEach((int line) -> mark[line] = '✗');
            }
            List<String> text = Files.readAllLines(root.resolve(snapshot.file()));
            for (int i = 1; i <= text.size() && i < byLine.length; i++) {
                StampRecord r = byLine[i];
                System.out.printf("%4d  %s %s %-16.16s %s | %s%n", i, r == null ? "       " : r.commitSha().substring(0, 7),
                        r == null ? "          " : DAY.format(r.time()), r == null ? "" : r.author().name(),
                        mark[i] == 0 ? " " : String.valueOf(mark[i]), text.get(i - 1));
            }
        }
    }

    private static String annotated(StampRecord r) {
        List<String> parts = new ArrayList<>();
        if (!r.affirmed().isEmpty()) {
            parts.add("affirmed " + r.affirmed());
        }
        if (!r.refuted().isEmpty()) {
            parts.add("refuted " + r.refuted());
        }
        if (!r.commented().isEmpty()) {
            parts.add("commented on " + r.commented());
        }
        return String.join(", ", parts);
    }

    private static int maxLine(List<StampRecord> records) {
        int max = 0;
        for (StampRecord r : records) {
            if (!r.introduced().isEmpty()) {
                max = Math.max(max, r.introduced().last());
            }
            for (org.roaringbitmap.RoaringBitmap annotated : List.of(r.affirmed(), r.refuted(), r.commented())) {
                if (!annotated.isEmpty()) {
                    max = Math.max(max, annotated.last());
                }
            }
        }
        return max;
    }
}
