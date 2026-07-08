package io.github.richeyworks.superbeefsort.intelligence.client;

import io.github.richeyworks.superbeefsort.core.SortResult;
import io.github.richeyworks.superbeefsort.core.StrategyId;
import io.github.richeyworks.superbeefsort.intelligence.CircuitBreaker;
import io.github.richeyworks.superbeefsort.intelligence.grpc.ObserveRequest;
import io.github.richeyworks.superbeefsort.intelligence.grpc.PredictRequest;
import io.github.richeyworks.superbeefsort.intelligence.grpc.PredictResponse;
import io.github.richeyworks.superbeefsort.intelligence.grpc.SbsIntelligenceGrpc;
import io.github.richeyworks.superbeefsort.intelligence.grpc.SortOutcome;
import io.github.richeyworks.superbeefsort.profile.DataProfile;
import io.github.richeyworks.superbeefsort.select.IntelligenceClient;
import io.github.richeyworks.superbeefsort.select.SelectionPolicy;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * gRPC implementation of the core {@link IntelligenceClient} seam (Phase 4b, ADR
 * docs/adr-phase4-python-intelligence.md action item 6). Talks to the Python {@code SbsIntelligence} service
 * (tools/phase4/service). Lives in the optional {@code sbs-intelligence-client} module so the engine core
 * carries no grpc/netty dependency.
 *
 * <p><b>Fallback-first.</b> Every call is wrapped in a per-call deadline and a {@link CircuitBreaker}: a
 * timeout/error records a failure and returns {@link Optional#empty()} (so {@link io.github.richeyworks.superbeefsort.select.RemoteStrategySelector}
 * keeps its local delegate), and once the breaker opens, calls short-circuit without touching the network until
 * a cooldown lets one probe through. Neither {@link #predict} nor {@link #observe} ever throws — the contract
 * the seam requires. Construct it and pass to {@code new RemoteStrategySelector(client, ...)}.</p>
 *
 * <p>Build/verify on the host with {@code -PwithIntelligence}; the Java stubs are generated from
 * {@code src/main/proto/sbs_intelligence.proto}.</p>
 */
public final class GrpcIntelligenceClient implements IntelligenceClient, AutoCloseable {

    /** Default per-call deadline; selection is on a per-job hot path, so the consult must be snappy. */
    public static final long DEFAULT_DEADLINE_MILLIS = 50L;
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final long DEFAULT_COOLDOWN_MILLIS = 30_000L;

    private final ManagedChannel channel;
    private final boolean ownsChannel;
    private final SbsIntelligenceGrpc.SbsIntelligenceBlockingStub stub;
    private final long deadlineMillis;
    private final CircuitBreaker breaker;

    /** Connect to {@code target} (e.g. {@code "127.0.0.1:50051"}) with the default deadline + breaker. */
    public GrpcIntelligenceClient(String target) {
        this(target, DEFAULT_DEADLINE_MILLIS);
    }

    public GrpcIntelligenceClient(String target, long deadlineMillis) {
        this(ManagedChannelBuilder.forTarget(target).usePlaintext().build(), true, deadlineMillis,
                new CircuitBreaker(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MILLIS));
    }

    /** Full control (e.g. a caller-managed channel or an in-process channel for tests). */
    public GrpcIntelligenceClient(ManagedChannel channel, boolean ownsChannel, long deadlineMillis,
                                  CircuitBreaker breaker) {
        if (deadlineMillis <= 0) {
            throw new IllegalArgumentException("deadlineMillis must be > 0: " + deadlineMillis);
        }
        this.channel = channel;
        this.ownsChannel = ownsChannel;
        this.stub = SbsIntelligenceGrpc.newBlockingStub(channel);
        this.deadlineMillis = deadlineMillis;
        this.breaker = breaker;
    }

    @Override
    public Optional<Advice> predict(DataProfile profile, SelectionPolicy policy) {
        if (!breaker.allowRequest()) {
            return Optional.empty(); // breaker open -> short-circuit to the local delegate
        }
        try {
            PredictResponse r = stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .predict(PredictRequest.newBuilder()
                            .setProfile(toProto(profile))
                            .setPolicy(policy.name())
                            .build());
            breaker.recordSuccess();
            if (r.getStrategyId().isEmpty()) {
                return Optional.empty(); // server declined to advise (e.g. non-SMART)
            }
            return Optional.of(new IntelligenceClient.Advice(r.getStrategyId(), r.getConfidence(), r.getModelVersion()));
        } catch (RuntimeException e) { // StatusRuntimeException (deadline/unavailable) or anything else
            breaker.recordFailure();
            return Optional.empty();
        }
    }

    @Override
    public void observe(DataProfile profile, StrategyId chosen, SortResult outcome) {
        if (!breaker.allowRequest()) {
            return; // best-effort; drop the observation rather than block or queue
        }
        try {
            stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .observe(ObserveRequest.newBuilder()
                            .setProfile(toProto(profile))
                            .setChosenStrategy(chosen.value())
                            .setOutcome(SortOutcome.newBuilder()
                                    .setComparisons(outcome.comparisons())
                                    .setMoves(outcome.moves())
                                    .setElapsedNanos(outcome.elapsedNanos())
                                    .setPeakAuxBytes(outcome.peakAuxBytes())
                                    .build())
                            .build());
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure();
        }
    }

    /** Core {@link DataProfile} -> the wire message. The server derives the model feature vector from these. */
    private static io.github.richeyworks.superbeefsort.intelligence.grpc.DataProfile toProto(DataProfile p) {
        io.github.richeyworks.superbeefsort.intelligence.grpc.DataProfile.Builder b =
                io.github.richeyworks.superbeefsort.intelligence.grpc.DataProfile.newBuilder()
                        .setSize(p.size())
                        .setSortednessRatio(p.sortednessRatio())
                        .setHasDuplicates(p.hasDuplicatesSampled())
                        .setDistinctEstimate(p.distinctEstimate())
                        .setHasKeyStats(p.hasKeyStats())
                        .setCountingFeasible(p.hasKeyStats() && p.keyStats().countingFeasible())
                        .setDistributionOrd(p.distribution().ordinal())
                        .setLongestRun(p.longestRun())
                        .setInversions(p.inversions())
                        .setInversionsExact(p.inversionsExact())
                        .setHasByteKey(p.hasByteSequenceKey());
        if (p.hasKeyStats()) {
            b.setKeySpan(p.keyStats().span());
        }
        return b.build();
    }

    @Override
    public void close() {
        if (ownsChannel) {
            channel.shutdown();
        }
    }
}
