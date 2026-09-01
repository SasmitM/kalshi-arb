package com.kalshiarb.recorder;

import com.kalshiarb.book.Book;
import com.kalshiarb.detector.ExhaustiveSumConstraint;
import com.kalshiarb.detector.FeeModel;
import com.kalshiarb.detector.Opportunity;
import com.kalshiarb.kalshi.ws.WsFrame;
import com.kalshiarb.kalshi.ws.WsFrameCodec;
import com.kalshiarb.model.EventTicker;
import com.kalshiarb.model.MarketTicker;
import com.kalshiarb.model.Money;
import com.kalshiarb.model.Quantity;
import com.kalshiarb.persistence.ArchivedFrame;
import com.kalshiarb.persistence.GzipJsonLinesFrameReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the detector over recorded frames and reports what the constraints actually found.
 *
 * <p>This produces the project's deliverable, which is a <em>measurement</em> rather than a
 * trade: how often an exhaustive bracket set violates its $1.00 sum, how large those violations
 * are, and how many survive fees at the size the book can actually absorb. A near-zero
 * exploitable count is the expected answer and is reported as the finding, not hidden.
 *
 * <p>Every price is depth-walked, so the reported edge is what the whole size would achieve, not
 * the touch. The touch is what makes a violation look tradable; the depth walk is what usually
 * removes it.
 *
 * <p>Usage: {@code DetectorReplay [archiveDirOrFile] [maxContracts]}, defaults
 * {@code data/frames 100}.
 */
public final class DetectorReplay {

    private final WsFrameCodec codec = new WsFrameCodec();
    private final ExhaustiveSumConstraint constraint;
    private final Quantity probeSize;

    private final Map<MarketTicker, Book> books = new LinkedHashMap<>();
    private final Map<EventTicker, List<MarketTicker>> family = new LinkedHashMap<>();
    private final Map<EventTicker, Long> expectedSeq = new LinkedHashMap<>();

    private long framesRead;
    private long evaluations;
    private long grossViolations;
    private long profitableAfterFees;
    private long sellSideViolations;
    private long buySideViolations;
    private long maxGrossMicros;
    private long bestNetMicros = Long.MIN_VALUE;
    private Opportunity best;
    private final List<Opportunity> profitable = new ArrayList<>();

    DetectorReplay(FeeModel fees, Quantity probeSize) {
        this.constraint = new ExhaustiveSumConstraint(fees);
        this.probeSize = probeSize;
    }

    public static void main(String[] args) throws IOException {
        Path target = Path.of(args.length > 0 ? args[0] : "data/frames");
        long maxContracts = args.length > 1 ? Long.parseLong(args[1]) : 100;

        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                s.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz"))
                        .sorted().forEach(files::add);
            }
        } else {
            files.add(target);
        }
        if (files.isEmpty()) {
            System.err.println("no .jsonl.gz archives under " + target.toAbsolutePath());
            System.exit(1);
        }

        // KXHIGH* weather series publish fee_type "quadratic" and fee_multiplier 1.
        DetectorReplay replay =
                new DetectorReplay(FeeModel.weather(), Quantity.ofContracts(maxContracts));
        for (Path file : files) {
            System.out.printf("scanning %s (%.2f MB), probe size %d contracts%n",
                    file, Files.size(file) / 1_048_576.0, maxContracts);
            replay.run(file);
        }
        replay.report();
    }

    void run(Path file) throws IOException {
        try (GzipJsonLinesFrameReader reader = new GzipJsonLinesFrameReader(file)) {
            reader.forEach(this::onFrame);
        }
    }

    private void onFrame(ArchivedFrame archived) {
        framesRead++;
        WsFrame frame;
        try {
            frame = codec.decode(archived.json());
        } catch (RuntimeException e) {
            return;
        }

        EventTicker fam = archived.family();
        switch (frame) {
            case WsFrame.Snapshot snap -> {
                if (isSessionReset(fam, snap.seq())) {
                    resetFamily(fam);
                }
                books.computeIfAbsent(snap.ticker(), Book::new)
                        .applySnapshot(snap.yesBids(), snap.noBids());
                track(fam, snap.ticker());
                evaluate(fam);
            }
            case WsFrame.Delta d -> {
                if (isSessionReset(fam, d.seq())) {
                    resetFamily(fam);
                }
                Book book = books.get(d.ticker());
                if (book == null) {
                    return;
                }
                try {
                    book.applyDelta(d.side(), d.price(), d.deltaMicroContracts());
                } catch (RuntimeException e) {
                    return;
                }
                track(fam, d.ticker());
                evaluate(fam);
            }
            default -> {
                if (frame instanceof WsFrame.Sequenced s) {
                    isSessionReset(fam, s.seq());
                }
            }
        }
    }

    private boolean isSessionReset(EventTicker fam, long seq) {
        Long expected = expectedSeq.put(fam, seq + 1);
        return expected != null && seq < expected;
    }

    private void resetFamily(EventTicker fam) {
        for (MarketTicker m : family.getOrDefault(fam, List.of())) {
            books.remove(m);
        }
        family.remove(fam);
    }

    private void track(EventTicker fam, MarketTicker market) {
        family.computeIfAbsent(fam, k -> new ArrayList<>());
        List<MarketTicker> markets = family.get(fam);
        if (!markets.contains(market)) {
            markets.add(market);
        }
    }

    /**
     * Evaluates one family's bracket set after every book change, as a live engine would.
     *
     * <p>Only once all six brackets are present: the buy side needs full coverage to mean
     * anything, and a partial set would manufacture violations that do not exist.
     */
    private void evaluate(EventTicker fam) {
        List<MarketTicker> markets = family.get(fam);
        if (markets == null || markets.size() < 6) {
            return;
        }
        List<Book> brackets = new ArrayList<>(markets.size());
        for (MarketTicker m : markets) {
            Book b = books.get(m);
            if (b == null) {
                return;
            }
            brackets.add(b);
        }

        evaluations++;
        for (Opportunity opp : constraint.evaluate(fam, brackets, probeSize)) {
            grossViolations++;
            if (opp.direction() == Opportunity.Direction.SELL_BRACKETS) {
                sellSideViolations++;
            } else {
                buySideViolations++;
            }
            maxGrossMicros = Math.max(maxGrossMicros, opp.grossEdge().micros());
            if (opp.netEdge().micros() > bestNetMicros) {
                bestNetMicros = opp.netEdge().micros();
                best = opp;
            }
            if (opp.isProfitable()) {
                profitableAfterFees++;
                if (profitable.size() < 20) {
                    profitable.add(opp);
                }
            }
        }
    }

    void report() {
        System.out.println();
        System.out.println("==== detector over recorded data ====");
        System.out.printf("  frames scanned          : %d%n", framesRead);
        System.out.printf("  book states evaluated   : %d%n", evaluations);
        System.out.printf("  gross violations        : %d  (sell %d / buy %d)%n",
                grossViolations, sellSideViolations, buySideViolations);
        System.out.printf("  max gross edge          : %s%n",
                grossViolations == 0 ? "n/a" : new Money(maxGrossMicros).toString());
        System.out.println();
        System.out.printf("  SURVIVING FEES          : %d%n", profitableAfterFees);

        if (grossViolations > 0) {
            System.out.printf("  violation rate          : %.3f%% of evaluated states%n",
                    100.0 * grossViolations / Math.max(1, evaluations));
        }
        if (best != null) {
            System.out.println();
            System.out.println("  best opportunity by net edge:");
            System.out.println("    " + best);
            System.out.printf("    fees consumed %.1f%% of the gross edge%n",
                    best.feeBurdenPercent());
            for (Opportunity.Leg leg : best.legs()) {
                System.out.printf("      %-26s @ %s  size %-10s fee %s%n",
                        leg.market(), leg.price(), leg.availableSize().toContractString(),
                        leg.fee());
            }
        }
        for (Opportunity opp : profitable.stream()
                .sorted(Comparator.comparingLong(o -> -o.netEdge().micros()))
                .toList()) {
            System.out.println("  PROFITABLE: " + opp);
        }

        System.out.println();
        if (evaluations == 0) {
            System.out.println("No complete bracket set was ever assembled; nothing evaluated.");
        } else if (grossViolations == 0) {
            System.out.println("Result: no constraint violation at any evaluated state. The "
                    + "brackets priced consistently throughout.");
        } else if (profitableAfterFees == 0) {
            System.out.printf("Result: %d gross violations, NONE survived fees at depth. "
                            + "This is the expected null result -- the edge exists in the "
                            + "prices and not in the market.%n",
                    grossViolations);
        } else {
            System.out.printf("Result: %d of %d gross violations survived fees. Treat with "
                            + "suspicion and check the book state at those timestamps.%n",
                    profitableAfterFees, grossViolations);
        }
    }
}
