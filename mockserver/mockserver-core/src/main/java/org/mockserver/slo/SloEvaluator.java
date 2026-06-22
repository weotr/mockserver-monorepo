package org.mockserver.slo;

import org.mockserver.time.TimeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates an {@link SloCriteria} against the samples in {@link SloSampleStore},
 * producing an {@link SloVerdict}. Pure in-memory computation — no I/O, no
 * blocking; the window is always already-elapsed.
 *
 * <p><b>Window resolution.</b> A {@link SloWindow.Type#LOOKBACK} window resolves
 * to {@code [now - lookbackMillis, now]} using the controllable clock
 * ({@link TimeService#currentTimeMillis()}); an {@link SloWindow.Type#EXPLICIT}
 * window uses its given {@code [from, to]} bounds verbatim.
 *
 * <p><b>Per-objective evaluation.</b> {@code LATENCY_P*} objectives compute the
 * percentile over the in-window latencies (copied algorithm from
 * {@code PercentileTracker}); {@code ERROR_RATE} computes {@code errors / total}
 * and is {@link SloVerdict.Result#INCONCLUSIVE} when {@code total == 0}. An
 * objective with no latency samples is also INCONCLUSIVE.
 *
 * <p><b>AND-ing.</b> The top-level verdict is: any objective FAIL → FAIL; else any
 * objective INCONCLUSIVE, or the whole window below {@code minimumSampleCount}
 * → INCONCLUSIVE; else PASS.
 */
public class SloEvaluator {

    private final SloSampleStore sampleStore;

    public SloEvaluator() {
        this(SloSampleStore.getInstance());
    }

    public SloEvaluator(SloSampleStore sampleStore) {
        this.sampleStore = sampleStore;
    }

    public SloVerdict evaluate(SloCriteria criteria) {
        long[] resolvedWindow = resolveWindow(criteria.getWindow());
        long fromEpochMillis = resolvedWindow[0];
        long toEpochMillis = resolvedWindow[1];

        Set<String> hosts = criteria.getUpstreamHosts();

        List<SloObjectiveResult> objectiveResults = new ArrayList<>();

        boolean anyFail = false;
        boolean anyInconclusive = false;

        for (SloObjective objective : criteria.getObjectives()) {
            Scope scope = objective.getScope();
            SloObjectiveResult objectiveResult = evaluateObjective(objective, fromEpochMillis, toEpochMillis, scope, hosts);
            objectiveResults.add(objectiveResult);
            if (objectiveResult.getResult() == SloVerdict.Result.FAIL) {
                anyFail = true;
            } else if (objectiveResult.getResult() == SloVerdict.Result.INCONCLUSIVE) {
                anyInconclusive = true;
            }
        }

        // Total in-window sample count for the minimumSampleCount guard, taken
        // over the first objective's scope. In v1 every objective defaults to
        // FORWARD scope (INBOUND records nothing), so the first-objective scope
        // is representative; revisit if mixed-scope criteria become common.
        Scope countScope = criteria.getObjectives().isEmpty()
            ? Scope.FORWARD
            : criteria.getObjectives().get(0).getScope();
        long totalSamples = sampleStore.errorCountsInWindow(fromEpochMillis, toEpochMillis, countScope, hosts).getTotal();

        Integer minimumSampleCount = criteria.getMinimumSampleCount();
        boolean belowMinimum = minimumSampleCount != null && totalSamples < minimumSampleCount;

        SloVerdict.Result overall;
        if (anyFail) {
            overall = SloVerdict.Result.FAIL;
        } else if (anyInconclusive || belowMinimum) {
            overall = SloVerdict.Result.INCONCLUSIVE;
        } else {
            overall = SloVerdict.Result.PASS;
        }

        return new SloVerdict()
            .withName(criteria.getName())
            .withResult(overall)
            .withWindowFromEpochMillis(fromEpochMillis)
            .withWindowToEpochMillis(toEpochMillis)
            .withSampleCount(totalSamples)
            .withObjectiveResults(objectiveResults);
    }

    private SloObjectiveResult evaluateObjective(SloObjective objective, long fromEpochMillis, long toEpochMillis, Scope scope, Set<String> hosts) {
        SloObjectiveResult result = new SloObjectiveResult()
            .withSli(objective.getSli())
            .withComparator(objective.getComparator())
            .withThreshold(objective.getThreshold());

        Double observed;
        switch (objective.getSli()) {
            case LATENCY_P50:
            case LATENCY_P95:
            case LATENCY_P99: {
                long[] latencies = sampleStore.latenciesInWindow(fromEpochMillis, toEpochMillis, scope, hosts);
                observed = SloSampleStore.percentile(latencies, percentileFor(objective.getSli()));
                if (observed == null) {
                    return result
                        .withObservedValue(null)
                        .withResult(SloVerdict.Result.INCONCLUSIVE)
                        .withDetail("no samples in window");
                }
                break;
            }
            case ERROR_RATE: {
                SloSampleStore.ErrorCounts counts = sampleStore.errorCountsInWindow(fromEpochMillis, toEpochMillis, scope, hosts);
                if (counts.getTotal() == 0) {
                    return result
                        .withObservedValue(null)
                        .withResult(SloVerdict.Result.INCONCLUSIVE)
                        .withDetail("no requests in window");
                }
                observed = (double) counts.getErrors() / (double) counts.getTotal();
                break;
            }
            default:
                return result
                    .withObservedValue(null)
                    .withResult(SloVerdict.Result.INCONCLUSIVE)
                    .withDetail("unsupported indicator: " + objective.getSli());
        }

        boolean satisfied = objective.satisfiedBy(observed);
        return result
            .withObservedValue(observed)
            .withResult(satisfied ? SloVerdict.Result.PASS : SloVerdict.Result.FAIL);
    }

    private static int percentileFor(SloObjective.Sli sli) {
        switch (sli) {
            case LATENCY_P50:
                return 50;
            case LATENCY_P95:
                return 95;
            case LATENCY_P99:
                return 99;
            default:
                throw new IllegalArgumentException("not a latency percentile indicator: " + sli);
        }
    }

    /**
     * Resolve the window to absolute {@code [fromEpochMillis, toEpochMillis]}.
     * LOOKBACK is resolved against the controllable clock; EXPLICIT is used
     * verbatim.
     */
    private long[] resolveWindow(SloWindow window) {
        if (window == null) {
            throw new IllegalArgumentException("an SLO window is required");
        }
        if (window.getType() == SloWindow.Type.LOOKBACK) {
            Long lookbackMillis = window.getLookbackMillis();
            if (lookbackMillis == null || lookbackMillis <= 0) {
                throw new IllegalArgumentException("a positive lookbackMillis is required for a LOOKBACK window");
            }
            long now = TimeService.currentTimeMillis();
            return new long[]{now - lookbackMillis, now};
        }
        if (window.getType() == SloWindow.Type.EXPLICIT) {
            if (window.getFromEpochMillis() == null || window.getToEpochMillis() == null) {
                throw new IllegalArgumentException("both fromEpochMillis and toEpochMillis are required for an EXPLICIT window");
            }
            return new long[]{window.getFromEpochMillis(), window.getToEpochMillis()};
        }
        throw new IllegalArgumentException("an SLO window type is required (LOOKBACK or EXPLICIT)");
    }
}
