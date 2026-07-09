package io.github.richeyworks.superbeefsort.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.strategy.RedBlackStrategy;
import io.github.richeyworks.superbeefsort.csrbt.Autopilot;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The first <em>useful</em> body: a sliding-window percentile service — "p95 over the last N
 * observations" answered in O(log n) — where the index underneath is an adaptive CSRBT set flown
 * by {@link Autopilot}. Windowed ordered set + order statistics <b>is</b> a live quantile store:
 * the FIFO window keeps exactly the last {@code WINDOW} observations (CSRBT evicts the oldest),
 * {@code percentile(p)} walks subtree sizes, and the control plane re-shapes the tree to the
 * observed traffic with zero human tuning. The pitch in one chart: watch {@code strategy} and the
 * quantiles move together when the workload regime shifts.
 *
 * <p><b>The duplicate trick.</b> An ordered <em>set</em> collapses repeated values, but real
 * observations repeat constantly — so each observation is stored as
 * {@code (value << SEQ_BITS) | seq}: value-ordered (quantiles decode by shifting back), yet every
 * observation distinct. The sequence wraps at 2²² while the window is capped at 2²⁰, so a
 * colliding key has always been evicted three million observations before its number comes up
 * again. Values are clamped to {@code [0, 2^41)} — micros, millis, bytes, whatever you observe.</p>
 *
 * <p>Run: {@code ./gradlew run --args="percentiles"} → dashboard at
 * <b>http://127.0.0.1:8078/</b>. API: {@code GET /observe?v=1234} (or {@code v=1,2,3}),
 * {@code GET /stats}, {@code GET /percentile?p=99}, {@code GET /demo?on=false} to silence the
 * built-in traffic generator (on by default so the dashboard is alive immediately; it cycles
 * steady → spike regimes so you can watch p99 and the strategy react). Binds loopback only.</p>
 */
public final class PercentileService {

    static final int SEQ_BITS = 22;
    static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    static final long MAX_VALUE = (1L << (63 - SEQ_BITS)) - 1;
    private static final int PORT = 8078;
    private static final int WINDOW = 100_000;               // "the last 100k observations"

    private final Autopilot<Long> pilot;
    private final OrderedSet<Long> set;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong observed = new AtomicLong();
    private final AtomicBoolean demoTraffic = new AtomicBoolean(true);

    private PercentileService() {
        OrderedSet<Long> s = OrderedSet.withNaturalOrder(new RedBlackStrategy<Long>());
        s.setMaxSize(WINDOW);
        this.set = s;
        this.pilot = Autopilot.of(WorkloadAdaptation.attach(s, MorphPolicy.defaults()),
                java.time.Duration.ofSeconds(5));
    }

    public static void run() {
        try {
            new PercentileService().start(PORT);
        } catch (IOException e) {
            System.err.println("percentile service failed to start: " + e.getMessage());
        }
    }

    // ── The codec: observation -> distinct, value-ordered key ────────────────────────────────

    /** Encode an observation: value-major, sequence-minor — ordered by value, always distinct. */
    public static long encode(long value, long sequence) {
        long v = Math.max(0, Math.min(MAX_VALUE, value));
        return (v << SEQ_BITS) | (sequence & SEQ_MASK);
    }

    /** Recover the observed value from a stored key. */
    public static long decode(long key) {
        return key >>> SEQ_BITS;
    }

    // ── Service ───────────────────────────────────────────────────────────────────────────────

    private void start(int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "percentile-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/", ex -> page(ex));
        http.createContext("/observe", ex -> observe(ex));
        http.createContext("/stats", ex -> json(ex, statsJson()));
        http.createContext("/percentile", ex -> percentile(ex));
        http.createContext("/demo", ex -> demoToggle(ex));
        http.start();
        System.out.println("Percentile service: http://127.0.0.1:" + port + "/");
        System.out.println("  observe:  curl \"http://127.0.0.1:" + port + "/observe?v=1234\"");
        System.out.println("  stats:    curl \"http://127.0.0.1:" + port + "/stats\"");
        System.out.println("  quantile: curl \"http://127.0.0.1:" + port + "/percentile?p=99\"");
        System.out.println("  demo traffic is ON (cycling steady/spike); /demo?on=false to silence");
        demoLoop();   // blocks; Ctrl+C to stop
    }

    /** Record one observation through the autopilot (thread-safe front door). */
    private void record(long value) {
        pilot.add(encode(value, seq.getAndIncrement()));
        observed.incrementAndGet();
    }

    private void observe(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        if (q == null || !q.startsWith("v=")) {
            respond(ex, 400, "{\"error\":\"use /observe?v=123 or v=1,2,3\"}");
            return;
        }
        int n = 0;
        try {
            for (String part : q.substring(2).split(",")) {
                record(Long.parseLong(part.trim()));
                n++;
            }
        } catch (NumberFormatException bad) {
            respond(ex, 400, "{\"error\":\"non-numeric observation\"}");
            return;
        }
        respond(ex, 200, "{\"accepted\":" + n + "}");
    }

    private void percentile(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        int p;
        try {
            p = Integer.parseInt(q != null && q.startsWith("p=") ? q.substring(2).trim() : "");
        } catch (NumberFormatException bad) {
            respond(ex, 400, "{\"error\":\"use /percentile?p=0..100\"}");
            return;
        }
        if (p < 1 || p > 100) {
            respond(ex, 400, "{\"error\":\"p must be 1..100 (min is in /stats)\"}");
            return;
        }
        Long key = set.percentile(p);
        respond(ex, 200, "{\"p\":" + p + ",\"value\":" + (key == null ? null : decode(key)) + "}");
    }

    private String statsJson() {
        // All reads are torn-read-free CSRBT reads — safe from any HTTP thread while traffic flows.
        Long min = set.minimum();
        Long max = set.maximum();
        Long p50 = set.percentile(50);
        Long p90 = set.percentile(90);
        Long p95 = set.percentile(95);
        Long p99 = set.percentile(99);
        return "{\"count\":" + set.size()
                + ",\"window\":" + set.getMaxSize()
                + ",\"observed\":" + observed.get()
                + ",\"min\":" + dec(min) + ",\"p50\":" + dec(p50) + ",\"p90\":" + dec(p90)
                + ",\"p95\":" + dec(p95) + ",\"p99\":" + dec(p99) + ",\"max\":" + dec(max)
                + ",\"strategy\":\"" + set.getStrategy().getClass().getSimpleName() + "\""
                + ",\"pilotCycles\":" + pilot.cycles()
                + ",\"verdict\":\"" + pilot.lastVerdict().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + ",\"demoTraffic\":" + demoTraffic.get() + "}";
    }

    private static String dec(Long key) {
        return key == null ? "null" : Long.toString(decode(key));
    }

    private void demoToggle(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        demoTraffic.set(!(q != null && q.contains("on=false")));
        respond(ex, 200, "{\"demoTraffic\":" + demoTraffic.get() + "}");
    }

    /**
     * The built-in load: latency-shaped observations (lognormal-ish body, heavy tail) cycling
     * 20s steady → 8s spike (×6 with a fatter tail), so the dashboard shows quantiles and the
     * adaptive index reacting to a regime shift without any external traffic.
     */
    private void demoLoop() {
        Random rnd = new Random(11);
        long phaseStart = System.currentTimeMillis();
        boolean spike = false;
        while (true) {
            long now = System.currentTimeMillis();
            if (now - phaseStart > (spike ? 8_000 : 20_000)) {
                spike = !spike;
                phaseStart = now;
            }
            if (demoTraffic.get()) {
                double body = Math.exp(rnd.nextGaussian() * 0.4) * (spike ? 6_000 : 1_000);
                long v = (long) (rnd.nextDouble() < (spike ? 0.05 : 0.01) ? body * 8 : body);
                record(v);
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────────────────────────

    private void page(HttpExchange ex) throws IOException {
        byte[] body;
        try (var in = PercentileService.class.getResourceAsStream("/percentiles.html")) {
            body = (in == null)
                    ? "<h1>percentiles.html missing from resources</h1>".getBytes(StandardCharsets.UTF_8)
                    : in.readAllBytes();
        }
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void json(HttpExchange ex, String body) throws IOException {
        respond(ex, 200, body);
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }
}
