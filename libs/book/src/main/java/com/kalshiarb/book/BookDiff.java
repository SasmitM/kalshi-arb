package com.kalshiarb.book;

import com.kalshiarb.model.FixedPoint;
import com.kalshiarb.model.PriceLevel;
import com.kalshiarb.model.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares a reconstructed {@link Book} against an authoritative snapshot.
 *
 * <p>This is the whole verification story for book reconstruction. Delta application is the one
 * part of the pipeline with no natural error signal: apply a delta twice, apply one out of order,
 * or miss one across an unrecovered gap, and the book quietly diverges from the exchange's while
 * every other metric stays green. The only way to catch it is to periodically ask the exchange
 * for the truth and compare.
 *
 * <p>Comparison is exact. Not approximate, not top-of-book: every level on both ladders, at
 * micro-dollar and micro-contract precision. A single contract of drift at one price level is a
 * bug, because the cause of a one-contract drift is the same as the cause of a catastrophic one.
 */
public record BookDiff(List<String> differences) {

    public BookDiff {
        differences = List.copyOf(differences);
    }

    public boolean matches() {
        return differences.isEmpty();
    }

    public int count() {
        return differences.size();
    }

    /**
     * Diffs {@code book}'s current state against the levels a snapshot reports.
     *
     * @param book the reconstructed book
     * @param yes  snapshot YES bids
     * @param no   snapshot NO bids
     */
    public static BookDiff against(Book book, List<PriceLevel> yes, List<PriceLevel> no) {
        List<String> out = new ArrayList<>();
        compareSide(book, Side.YES, yes, out);
        compareSide(book, Side.NO, no, out);
        return new BookDiff(out);
    }

    private static void compareSide(
            Book book, Side side, List<PriceLevel> expectedLevels, List<String> out) {

        Map<Long, Long> expected = new TreeMap<>();
        if (expectedLevels != null) {
            for (PriceLevel level : expectedLevels) {
                long size = level.quantity().microContracts();
                if (size != 0) {
                    expected.put(level.price().micros(), size);
                }
            }
        }
        Map<Long, Long> actual = book.ladder(side);

        for (Map.Entry<Long, Long> e : expected.entrySet()) {
            Long have = actual.get(e.getKey());
            if (have == null) {
                out.add(side + " missing level $" + FixedPoint.format(e.getKey(), 4)
                        + " expected size " + FixedPoint.format(e.getValue(), 2));
            } else if (!have.equals(e.getValue())) {
                out.add(side + " size mismatch at $" + FixedPoint.format(e.getKey(), 4)
                        + ": reconstructed " + FixedPoint.format(have, 2)
                        + " vs snapshot " + FixedPoint.format(e.getValue(), 2));
            }
        }
        for (Map.Entry<Long, Long> e : actual.entrySet()) {
            if (!expected.containsKey(e.getKey())) {
                out.add(side + " extra level $" + FixedPoint.format(e.getKey(), 4)
                        + " size " + FixedPoint.format(e.getValue(), 2)
                        + " not in snapshot");
            }
        }
    }

    /** First few differences, for a log line that stays readable. */
    public String summary(int max) {
        if (matches()) {
            return "exact match";
        }
        List<String> head = differences.subList(0, Math.min(max, differences.size()));
        String more = differences.size() > max
                ? " (+" + (differences.size() - max) + " more)" : "";
        return String.join("; ", head) + more;
    }
}
