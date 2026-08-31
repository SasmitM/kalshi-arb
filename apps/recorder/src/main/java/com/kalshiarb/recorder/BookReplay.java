package com.kalshiarb.recorder;

import com.kalshiarb.book.Book;
import com.kalshiarb.book.BookDiff;
import com.kalshiarb.book.BookInvariants;
import com.kalshiarb.book.DeltaApplier;
import com.kalshiarb.kalshi.ws.WsFrame;
import com.kalshiarb.kalshi.ws.WsFrameCodec;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.recorder.archive.ArchivedFrame;
import com.kalshiarb.recorder.archive.GzipJsonLinesFrameReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Replays an archive through the book layer and verifies the reconstruction.
 *
 * <p>This is task 6's acceptance criterion made executable, and it is the only check in the
 * project that can catch a delta-application bug. Everything else — decode counters, gap
 * counters, drop counters — stays green while a book quietly diverges from the exchange's. The
 * verification is: apply deltas incrementally, and when a periodic snapshot arrives, compare the
 * reconstructed ladder against it <b>exactly</b>, at every level.
 *
 * <p>Also checks the crossed-book invariant after every single update, since a crossed book is
 * itself evidence that deltas were mis-applied.
 *
 * <p>Sequence handling mirrors the live path: {@code seq} is per-connection, so each event family
 * is replayed as its own independent stream. A snapshot resets that stream's baseline; an
 * unexpected seq is reported rather than silently accepted.
 *
 * <p>Lives here rather than in {@code apps/replay} on purpose — CLAUDE.md defers the replay
 * <em>engine</em> (the latency simulator), and this module already owns archive I/O. It moves when
 * that app exists.
 *
 * <p>Usage: {@code BookReplay [archiveDirOrFile]}, default {@code data/frames}.
 */
public final class BookReplay {

    private final WsFrameCodec codec = new WsFrameCodec();

    /** Per-market reconstructed books. */
    private final Map<MarketTicker, Book> books = new LinkedHashMap<>();

    /** Per-family expected next seq, since seq is scoped to the connection. */
    private final Map<EventTicker, Long> expectedSeq = new HashMap<>();

    /** Markets that have taken at least one delta since their last snapshot. */
    private final Map<MarketTicker, Integer> deltasSinceSnapshot = new HashMap<>();

    /** Which markets belong to which family, so a session reset can invalidate them. */
    private final Map<EventTicker, java.util.Set<MarketTicker>> marketsByFamily =
            new HashMap<>();

    private long framesRead;
    private long deltasApplied;
    private long snapshotsSeen;
    private long controlFrames;
    private long decodeFailures;
    private long seqBreaks;
    private long negativeLevels;
    private long crossedBooks;
    private long sessionResets;
    private long skippedAcrossReset;
    private long errorFrames;

    // The verification counters that actually matter.
    private long comparisons;
    private long exactMatches;
    private final List<String> mismatches = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "data/frames");

        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz"))
                        .sorted()
                        .forEach(files::add);
            }
        } else {
            files.add(target);
        }
        if (files.isEmpty()) {
            System.err.println("no .jsonl.gz archives under " + target.toAbsolutePath());
            System.exit(1);
        }

        BookReplay replay = new BookReplay();
        for (Path file : files) {
            System.out.printf("replaying %s (%.2f MB)%n", file, Files.size(file) / 1_048_576.0);
            replay.replay(file);
        }
        replay.report();
        // Exit non-zero unless reconstruction was genuinely verified. An archive with no
        // checkpoints is not a pass.
        System.exit(replay.isVerified() ? 0 : 1);
    }

    void replay(Path file) throws IOException {
        try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
            reader.forEach(this::onFrame);
            if (reader.truncatedStream()) {
                System.out.printf("  note: archive ends mid-member (recorder still running or "
                        + "was killed); %d lines unparseable%n", reader.malformedLines());
            }
        }
    }

    private void onFrame(ArchivedFrame archived) {
        framesRead++;
        WsFrame frame;
        try {
            frame = codec.decode(archived.json());
        } catch (RuntimeException e) {
            decodeFailures++;
            return;
        }

        switch (frame) {
            case WsFrame.Snapshot snap -> onSnapshot(archived.family(), snap);
            case WsFrame.Delta delta -> onDelta(archived.family(), delta);
            case WsFrame.Ok ok -> {
                controlFrames++;
                if (ok.hasSeq()) {
                    // Control frames occupy sequence numbers; skipping them here would make the
                    // next delta look like it had jumped.
                    checkSeq(archived.family(), ok.seq());
                }
            }
            case WsFrame.ErrorFrame err -> {
                controlFrames++;
                errorFrames++;
                // Errors are sequenced too. Skipping their seq was manufacturing phantom gaps.
                if (err.hasSeq()) {
                    checkSeq(archived.family(), err.seq());
                }
                if (errorFrames <= 10) {
                    System.err.printf("  exchange error on %s (seq %d): code=%s %s%n",
                            archived.family(), err.seq(), err.code(), err.message());
                }
            }
            default -> {
                // subscribed / unhandled carry no book state and no seq.
            }
        }
    }

    private void onSnapshot(EventTicker family, WsFrame.Snapshot snap) {
        snapshotsSeen++;
        checkSeq(family, snap.seq());

        marketsByFamily.computeIfAbsent(family, k -> new java.util.LinkedHashSet<>())
                .add(snap.ticker());

        Book book = books.get(snap.ticker());
        int deltas = deltasSinceSnapshot.getOrDefault(snap.ticker(), 0);

        // THE VERIFICATION. Only meaningful if deltas were applied since the last snapshot --
        // comparing a snapshot against a book that was just built from a snapshot proves
        // nothing.
        if (book != null && deltas > 0) {
            comparisons++;
            BookDiff diff = BookDiff.against(book, snap.yesBids(), snap.noBids());
            if (diff.matches()) {
                exactMatches++;
            } else {
                mismatches.add(snap.ticker() + " after " + deltas + " deltas (seq "
                        + snap.seq() + "): " + diff.summary(4));
            }
        }

        if (book == null) {
            book = new Book(snap.ticker());
            books.put(snap.ticker(), book);
        }
        book.applySnapshot(snap.yesBids(), snap.noBids());
        deltasSinceSnapshot.put(snap.ticker(), 0);
        checkInvariant(book);
    }

    private void onDelta(EventTicker family, WsFrame.Delta delta) {
        checkSeq(family, delta.seq());
        marketsByFamily.computeIfAbsent(family, k -> new java.util.LinkedHashSet<>())
                .add(delta.ticker());

        Book book = books.get(delta.ticker());
        if (book == null) {
            // A delta before this market's first snapshot. The live path buffers these; on
            // replay there is nothing to apply them to, so they are skipped rather than
            // fabricating a book out of a partial update.
            return;
        }
        try {
            book.applyDelta(delta.side(), delta.price(), delta.deltaMicroContracts());
            deltasApplied++;
            deltasSinceSnapshot.merge(delta.ticker(), 1, Integer::sum);
        } catch (DeltaApplier.NegativeLevelException e) {
            negativeLevels++;
            if (negativeLevels <= 5) {
                System.err.println("  NEGATIVE LEVEL: " + e.getMessage());
            }
            return;
        }
        checkInvariant(book);
    }

    private void checkInvariant(Book book) {
        try {
            BookInvariants.check(book);
        } catch (BookInvariants.CrossedBookException e) {
            crossedBooks++;
            if (crossedBooks <= 5) {
                System.err.println("  CROSSED: " + e.getMessage());
            }
        }
    }

    private void checkSeq(EventTicker family, long seq) {
        Long expected = expectedSeq.get(family);
        if (expected != null && seq != expected) {
            if (seq < expected) {
                // A seq going BACKWARDS is a new session, not a gap: a reconnect or a recorder
                // restart reassigns the sid and restarts seq at 1. Frames were genuinely not
                // recorded across that boundary, so the reconstructed book cannot be expected
                // to match the next snapshot -- comparing across it would test whether the
                // market moved while we were disconnected, which is not a property of this
                // code. Invalidate instead, exactly as the live path does on reconnect.
                sessionResets++;
                onSessionReset(family, expected, seq);
            } else {
                seqBreaks++;
                if (seqBreaks <= 5) {
                    System.err.printf("  seq break on %s: expected %d, got %d (%d missing)%n",
                            family, expected, seq, seq - expected);
                }
            }
        }
        expectedSeq.put(family, seq + 1);
    }

    /** Drops comparison eligibility for a family whose stream restarted. */
    private void onSessionReset(EventTicker family, long expected, long seq) {
        System.err.printf("  session boundary on %s: seq restarted (%d -> %d); books "
                        + "invalidated, comparisons skipped across the boundary%n",
                family, expected, seq);
        for (MarketTicker market : marketsByFamily.getOrDefault(family, java.util.Set.of())) {
            if (deltasSinceSnapshot.getOrDefault(market, 0) > 0) {
                skippedAcrossReset++;
            }
            deltasSinceSnapshot.put(market, 0);
            books.remove(market);
        }
    }

    /** No failures were observed. Says nothing about whether anything was verified. */
    boolean noFailures() {
        return decodeFailures == 0 && negativeLevels == 0 && crossedBooks == 0
                && seqBreaks == 0 && mismatches.isEmpty();
    }

    /**
     * Reconstruction was actually verified: at least one snapshot was compared against a book
     * built from deltas, and nothing failed.
     *
     * <p>The {@code comparisons > 0} clause is the point. Without it this reports success on an
     * archive containing no checkpoints, which is the same green-while-unverified signal that let
     * a broken archive format run for seven minutes earlier in this project. "No failures" and
     * "verified" are different claims and must not print the same word.
     */
    boolean isVerified() {
        return noFailures() && comparisons > 0;
    }

    long comparisons() {
        return comparisons;
    }

    long exactMatches() {
        return exactMatches;
    }

    List<String> mismatches() {
        return List.copyOf(mismatches);
    }

    long deltasApplied() {
        return deltasApplied;
    }

    void report() {
        System.out.println();
        System.out.println("==== replay ====");
        System.out.printf("  frames read        : %d%n", framesRead);
        System.out.printf("  snapshots          : %d%n", snapshotsSeen);
        System.out.printf("  deltas applied     : %d%n", deltasApplied);
        System.out.printf("  control frames     : %d%n", controlFrames);
        System.out.printf("  markets            : %d%n", books.size());
        System.out.println();
        System.out.println("==== verification ====");
        System.out.printf("  decode failures    : %d%n", decodeFailures);
        System.out.printf("  exchange errors    : %d%n", errorFrames);
        System.out.printf("  session boundaries : %d (comparisons skipped: %d)%n",
                sessionResets, skippedAcrossReset);
        System.out.printf("  seq breaks         : %d%n", seqBreaks);
        System.out.printf("  negative levels    : %d%n", negativeLevels);
        System.out.printf("  crossed books      : %d%n", crossedBooks);
        System.out.printf("  snapshot comparisons: %d  exact=%d  mismatched=%d%n",
                comparisons, exactMatches, mismatches.size());

        if (comparisons == 0) {
            System.out.println();
            System.out.println("  NO COMPARISONS POSSIBLE. Every snapshot arrived before any");
            System.out.println("  delta was applied to that market, so nothing was verified.");
            System.out.println("  The recorder requests periodic snapshot checkpoints every 5");
            System.out.println("  minutes; let it run longer, or force a reconnect, then retry.");
        }
        for (String m : mismatches.subList(0, Math.min(10, mismatches.size()))) {
            System.out.println("  MISMATCH " + m);
        }
        System.out.println();
        if (!noFailures()) {
            System.out.println("FAILED: see counters above.");
        } else if (comparisons == 0) {
            System.out.printf("UNVERIFIED: %d deltas applied with no invariant violation, but "
                            + "ZERO snapshots were compared, so reconstruction is not proven.%n",
                    deltasApplied);
        } else {
            System.out.printf("OK: reconstruction verified against %d snapshot checkpoints "
                    + "(all exact), invariants held across %d deltas.%n",
                    comparisons, deltasApplied);
        }
    }
}
