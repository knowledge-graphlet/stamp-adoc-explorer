/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.dialect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which new unit of text is which old one, across one revision (IKE-Network/ike-issues#1313;
 * decision 2 of {@code design-2026-10-21-stored-text-segments}). A paired unit keeps its
 * identity, with new text when its text changed; an unpaired new unit is introduced and an
 * unpaired old one retired.
 *
 * <ol>
 * <li><em>Equal:</em> the longest common subsequence of equal texts.</li>
 * <li><em>Moved:</em> a text removed in one place and added unchanged in another, when it has a
 * letter or a digit.</li>
 * <li><em>Revised:</em> within each run of unmatched units, an order-preserving pairing that
 * maximizes word overlap, accepting a pair whose texts share, in order, at least half the words
 * of the shorter and at least three words (or all of a shorter one's).</li>
 * <li><em>Positional residue:</em> a single old and a single new unit left between two pairs
 * pair by position.</li>
 * </ol>
 *
 * <p>The scheme follows WikiWho's for Wikipedia authorship: match unchanged units first, then
 * diff the words of what is left.
 */
public final class TextPairing {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]");
    /** The share of the shorter text's words a revised pair must keep. */
    static final double THRESHOLD = 0.5;

    private TextPairing() {
    }

    /**
     * Pairs sentences, or any units compared by word overlap.
     *
     * @param before the old texts, in order
     * @param after  the new texts, in order
     * @return for each new unit, the index of the old unit it is, or -1 when it is introduced
     */
    public static int[] pair(List<String> before, List<String> after) {
        return pair(before, after, null);
    }

    /**
     * Pairs units whose revisions may only be paired where {@code compatible} allows: inline
     * elements pair only with elements of their kind in the same sentence.
     *
     * @param before     the old texts, in order
     * @param after      the new texts, in order
     * @param compatible whether old unit {@code i} may be a revision of new unit {@code j}; null for any
     * @return for each new unit, the index of the old unit it is, or -1 when it is introduced
     */
    public static int[] pair(List<String> before, List<String> after, BiPredicate<Integer, Integer> compatible) {
        int n = before.size();
        int m = after.size();
        int[] result = new int[m];
        Arrays.fill(result, -1);
        boolean[] used = new boolean[n];
        List<int[]> anchors = lcs(before, after, 0, n, 0, m);
        for (int[] p : anchors) {
            result[p[1]] = p[0];
            used[p[0]] = true;
        }
        // moved: equal text across runs
        for (int j = 0; j < m; j++) {
            if (result[j] >= 0 || !significant(after.get(j))) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if (!used[i] && before.get(i).equals(after.get(j))) {
                    result[j] = i;
                    used[i] = true;
                    break;
                }
            }
        }
        // revised: each run of unmatched units between two equal ones
        List<int[]> bounds = new ArrayList<>(anchors);
        bounds.add(new int[] {n, m});
        int i0 = 0;
        int j0 = 0;
        for (int[] bound : bounds) {
            List<Integer> olds = new ArrayList<>();
            for (int i = i0; i < bound[0]; i++) {
                if (!used[i]) {
                    olds.add(i);
                }
            }
            List<Integer> news = new ArrayList<>();
            for (int j = j0; j < bound[1]; j++) {
                if (result[j] < 0) {
                    news.add(j);
                }
            }
            fill(before, after, olds, news, result, used, compatible);
            i0 = bound[0] + 1;
            j0 = bound[1] + 1;
        }
        return result;
    }

    /** Pairs one run by overlap, then a lone leftover pair between pairs by position. */
    private static void fill(List<String> before, List<String> after, List<Integer> olds, List<Integer> news, int[] result,
                             boolean[] used, BiPredicate<Integer, Integer> compatible) {
        if (olds.isEmpty() || news.isEmpty()) {
            return;
        }
        int a = olds.size();
        int b = news.size();
        double[][] sim = new double[a][b];
        for (int x = 0; x < a; x++) {
            for (int y = 0; y < b; y++) {
                boolean allowed = compatible == null || compatible.test(olds.get(x), news.get(y));
                sim[x][y] = allowed ? overlap(before.get(olds.get(x)), after.get(news.get(y))) : 0;
            }
        }
        double[][] best = new double[a + 1][b + 1];
        for (int x = a - 1; x >= 0; x--) {
            for (int y = b - 1; y >= 0; y--) {
                double take = sim[x][y] >= THRESHOLD ? sim[x][y] + best[x + 1][y + 1] : -1;
                best[x][y] = Math.max(take, Math.max(best[x + 1][y], best[x][y + 1]));
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int x = 0;
        int y = 0;
        while (x < a && y < b) {
            if (sim[x][y] >= THRESHOLD && Math.abs(best[x][y] - (sim[x][y] + best[x + 1][y + 1])) < 1e-9) {
                pairs.add(new int[] {x++, y++});
            } else if (best[x + 1][y] >= best[x][y + 1]) {
                x++;
            } else {
                y++;
            }
        }
        List<int[]> bounds = new ArrayList<>();
        bounds.add(new int[] {-1, -1});
        bounds.addAll(pairs);
        bounds.add(new int[] {a, b});
        for (int k = 1; k < bounds.size(); k++) {
            int[] lo = bounds.get(k - 1);
            int[] hi = bounds.get(k);
            if (hi[0] - lo[0] == 2 && hi[1] - lo[1] == 2) {
                int ox = lo[0] + 1;
                int ny = lo[1] + 1;
                if (compatible == null || compatible.test(olds.get(ox), news.get(ny))) {
                    pairs.add(new int[] {ox, ny});           // the positional residue
                }
            }
        }
        for (int[] p : pairs) {
            result[news.get(p[1])] = olds.get(p[0]);
            used[olds.get(p[0])] = true;
        }
    }

    /**
     * Word overlap: the words two texts share in order over the shorter's word count, 0 when they
     * share fewer than three words and neither has fewer.
     *
     * @param a one text
     * @param b another
     * @return the overlap, from 0 to 1
     */
    public static double overlap(String a, String b) {
        List<String> x = words(a);
        List<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return x.isEmpty() && y.isEmpty() ? 1 : 0;
        }
        int common = lcs(x, y, 0, x.size(), 0, y.size()).size();
        int shorter = Math.min(x.size(), y.size());
        return common < Math.min(3, shorter) ? 0 : (double) common / shorter;
    }

    private static List<String> words(String s) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            words.add(m.group());
        }
        return words;
    }

    private static boolean significant(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** The longest common subsequence of equal elements of {@code a[a0, a1)} and {@code b[b0, b1)}, as index pairs in order. */
    private static List<int[]> lcs(List<String> a, List<String> b, int a0, int a1, int b0, int b1) {
        int n = a1 - a0;
        int m = b1 - b0;
        int[][] l = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                l[i][j] = a.get(a0 + i).equals(b.get(b0 + j)) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(a0 + i).equals(b.get(b0 + j))) {
                pairs.add(new int[] {a0 + i++, b0 + j++});
            } else if (l[i + 1][j] >= l[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }
}
