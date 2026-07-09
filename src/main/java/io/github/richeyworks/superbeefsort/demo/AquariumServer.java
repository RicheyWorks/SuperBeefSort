package io.github.richeyworks.superbeefsort.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.richeyworks.csrbt.OrderedSet;
import io.github.richeyworks.csrbt.TreeNode1;
import io.github.richeyworks.csrbt.control.MorphController;
import io.github.richeyworks.csrbt.control.MorphPolicy;
import io.github.richeyworks.csrbt.event.TreeEvent;
import io.github.richeyworks.superbeefsort.BeefSort;
import io.github.richeyworks.superbeefsort.csrbt.WorkloadAdaptation;
import io.github.richeyworks.superbeefsort.workload.Regime;
import io.github.richeyworks.superbeefsort.workload.Workloads;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The aquarium: CSRBT as a live exhibit. One adaptive {@link OrderedSet} (born optimal via
 * SuperBeefSort, wired to CSRBT's control plane with real depth/rotation signals) swims through an
 * endless {@link Workloads#aquariumPlaylist playlist} of regimes — read-heavy, hot-key skew, write
 * burst, windowed climb — while every control-plane decision streams to the browser over
 * Server-Sent Events and the tree itself is redrawn live. Where the arena visualizer replays
 * tapes, this is the tank: you watch it adapt as it happens.
 *
 * <p>Run: {@code ./gradlew run --args="aquarium"} then open <b>http://127.0.0.1:8077/</b>.
 * Binds loopback only (matching the hardening posture; this is an exhibit, not a service). All
 * mutation and all live-structure reads happen on the single driver thread, honoring
 * {@code TreeExport}'s writer-thread contract; the event listener only bumps counters and
 * enqueues rare decision events, honoring the listener contract. Snapshots are depth-capped
 * (truncated subtrees carry their size) so a 60k-key tree streams as ~kilobytes, not megabytes.</p>
 */
public final class AquariumServer {

    private static final int PORT = 8077;
    private static final int STATS_EVERY = 500;
    private static final int STATE_EVERY = 1_500;
    private static final int ADAPT_EVERY = 3_000;
    private static final int SNAPSHOT_DEPTH = 9;          // <= 2^9-1 = 511 drawn nodes
    private static final int CLIENT_QUEUE_CAP = 1_000;

    private final CopyOnWriteArrayList<LinkedBlockingQueue<String>> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong inserts = new AtomicLong();
    private final AtomicLong removes = new AtomicLong();
    private final AtomicLong evicts = new AtomicLong();

    // ── The DJ booth: browser-requested regimes, handed to the driver thread ────────────────
    // The HTTP thread only sets the reference; the driver polls it between op bursts and owns
    // all actual mutation — the single-mutator contract is never crossed.
    private final java.util.concurrent.atomic.AtomicReference<Regime> requested = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.Map<String, java.util.function.Supplier<Regime>> djBooth = new java.util.LinkedHashMap<>();
    private final AtomicLong djSeed = new AtomicLong(9_000);   // fresh key streams per request

    private void stockDjBooth() {
        djBooth.put("read-heavy", () -> Workloads.readHeavyUniform(15_000, 60_000, djSeed.incrementAndGet()));
        djBooth.put("hot-key", () -> Workloads.hotKeySkew(15_000, 8, 60_000, djSeed.incrementAndGet()));
        djBooth.put("zipf", () -> Workloads.zipfRead(15_000, 60_000, 1.1, djSeed.incrementAndGet()));
        djBooth.put("write-burst", () -> Workloads.writeBurst(15_000, 60_000, djSeed.incrementAndGet()));
        djBooth.put("windowed-climb", () -> Workloads.windowedClimb(15_000, 8_000, djSeed.incrementAndGet()));
        djBooth.put("window-off", () -> new Regime("window off (steady churn)", 10_000, 0.60, 0.5,
                Workloads.uniformKeys(60_000, djSeed.incrementAndGet()), 0));
    }

    private String menuJson() {
        StringBuilder sb = new StringBuilder("{\"t\":\"menu\",\"regimes\":[");
        boolean first = true;
        for (String name : djBooth.keySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(name).append('"');
            first = false;
        }
        return sb.append("]}").toString();
    }

    public static void run() {
        try {
            new AquariumServer().start();
        } catch (IOException e) {
            System.err.println("aquarium failed to start: " + e.getMessage());
        }
    }

    private void start() throws IOException {
        // Birth: profile + sort + O(n) construction + control plane, exactly like the organism demo.
        WorkloadAdaptation<Integer> adaptation = BeefSort.with(Comparator.<Integer>naturalOrder())
                .source(Workloads.uniform(20_000, 60_000, 42))
                .buildAdaptive(MorphPolicy.defaults());
        OrderedSet<Integer> set = adaptation.set();

        // The tank's hydrophone: counters for the per-key flood, immediate fan-out for decisions.
        set.setEventListener(e -> {
            if (e instanceof TreeEvent.Insert) {
                inserts.incrementAndGet();
            } else if (e instanceof TreeEvent.Remove) {
                removes.incrementAndGet();
            } else if (e instanceof TreeEvent.Evict) {
                evicts.incrementAndGet();
            } else if (e instanceof TreeEvent.Morph<Integer> m) {
                publish("{\"t\":\"morph\",\"from\":\"" + esc(m.fromStrategy()) + "\",\"to\":\""
                        + esc(m.toStrategy()) + "\",\"committed\":" + m.committed() + "}");
            } else if (e instanceof TreeEvent.Repair<Integer> r) {
                publish("{\"t\":\"repair\",\"healthy\":" + r.healthy() + "}");
            }
        });

        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "aquarium-http");
            t.setDaemon(true);
            return t;
        }));
        stockDjBooth();
        http.createContext("/", this::servePage);
        http.createContext("/events", this::serveEvents);
        http.createContext("/dj", this::serveDj);
        http.start();
        System.out.println("Aquarium open: http://127.0.0.1:" + PORT + "/  (Ctrl+C to drain the tank)");

        drive(adaptation);   // blocks forever on the single mutator thread
    }

    // ── The driver: one thread, endless playlist ────────────────────────────────────────────

    private void drive(WorkloadAdaptation<Integer> adaptation) {
        OrderedSet<Integer> set = adaptation.set();
        Random mix = new Random(7);
        long op = 0;
        List<Regime> playlist = Workloads.aquariumPlaylist(1_000);
        int track = 0;
        while (true) {
            // DJ requests preempt the playlist; otherwise the next track plays.
            Regime djPick = requested.getAndSet(null);
            boolean dj = djPick != null;
            Regime regime = dj ? djPick : playlist.get(track++ % playlist.size());
            int lap = track / Math.max(1, playlist.size());

            if (regime.window() >= 0) {
                set.setMaxSize(regime.window());
            }
            publish("{\"t\":\"regime\",\"name\":\"" + esc(regime.name()) + "\",\"ops\":" + regime.ops()
                    + ",\"window\":" + set.getMaxSize() + ",\"lap\":" + lap
                    + ",\"dj\":" + dj + "}");
            for (int i = 1; i <= regime.ops(); i++) {
                int key = regime.keys().getAsInt();
                if (mix.nextDouble() < regime.readFraction()) {
                    adaptation.contains(key);                      // measured walk -> real depth
                } else if (mix.nextDouble() < regime.addBias()) {
                    adaptation.add(key);                           // measured rotations
                } else {
                    adaptation.remove(key);
                }
                op++;
                if (op % STATS_EVERY == 0) {
                    publishStats(op, set, regime);
                }
                if (op % STATE_EVERY == 0) {
                    publish("{\"t\":\"state\",\"strategy\":\"" + strategyOf(set)
                            + "\",\"state\":" + snapshot(set) + "}");
                }
                if (op % ADAPT_EVERY == 0) {
                    MorphController.MorphResult r = adaptation.maybeAdapt();
                    publish("{\"t\":\"eval\",\"op\":" + op + ",\"morphed\":" + r.morphed()
                            + ",\"from\":\"" + r.from() + "\",\"to\":\"" + r.to()
                            + "\",\"reason\":\"" + esc(r.reason()) + "\"}");
                }
                if (i % 250 == 0 && requested.get() != null) {
                    break;   // a DJ pick mid-regime: cut this track short, responsively
                }
            }
        }
    }

    /** GET /dj?name=<regime>: queue a booth regime; the driver picks it up within ~250 ops. */
    private void serveDj(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String name = (query != null && query.startsWith("name="))
                ? java.net.URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8) : "";
        var factory = djBooth.get(name);
        if (factory == null) {
            ex.sendResponseHeaders(404, -1);
        } else {
            requested.set(factory.get());
            ex.sendResponseHeaders(204, -1);
        }
        ex.close();
    }

    private void publishStats(long op, OrderedSet<Integer> set, Regime regime) {
        publish("{\"t\":\"stats\",\"op\":" + op
                + ",\"size\":" + set.size()
                + ",\"strategy\":\"" + strategyOf(set) + "\""
                + ",\"window\":" + set.getMaxSize()
                + ",\"rotations\":" + set.rotationCount()
                + ",\"inserts\":" + inserts.get()
                + ",\"removes\":" + removes.get()
                + ",\"evicts\":" + evicts.get()
                + ",\"regime\":\"" + esc(regime.name()) + "\"}");
    }

    private static String strategyOf(OrderedSet<Integer> set) {
        return set.getStrategy().getClass().getSimpleName();
    }

    /**
     * Depth-capped live snapshot in TreeExport's node dialect ({@code k}/{@code c}/{@code l}/{@code r},
     * truncated subtrees replaced by {@code {"trunc":size}}). Called only from the driver thread —
     * the single mutator — per the live-structure contract.
     */
    private static String snapshot(OrderedSet<Integer> set) {
        StringBuilder sb = new StringBuilder(16_384);
        node(set.getEngine().getRoot(), 1, sb);
        return sb.toString();
    }

    private static void node(TreeNode1<Integer> n, int depth, StringBuilder sb) {
        if (n == null || n.isNil()) {
            sb.append("null");
            return;
        }
        if (depth > SNAPSHOT_DEPTH) {
            sb.append("{\"trunc\":").append(n.getSize()).append('}');
            return;
        }
        sb.append("{\"k\":").append(n.getData())
          .append(",\"c\":\"").append(n.getColor()).append("\",\"l\":");
        node(n.getLeft(), depth + 1, sb);
        sb.append(",\"r\":");
        node(n.getRight(), depth + 1, sb);
        sb.append('}');
    }

    // ── SSE plumbing ────────────────────────────────────────────────────────────────────────

    private void publish(String json) {
        for (LinkedBlockingQueue<String> q : clients) {
            if (!q.offer(json)) {                     // slow client: drop oldest, keep the stream live
                q.poll();
                q.offer(json);
            }
        }
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>(CLIENT_QUEUE_CAP);
        clients.add(q);
        try (OutputStream out = ex.getResponseBody()) {
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.write(("data: " + menuJson() + "\n\n").getBytes(StandardCharsets.UTF_8));   // the DJ menu
            out.flush();
            while (true) {
                String msg = q.take();
                out.write(("data: " + msg + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException gone) {
            if (gone instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            clients.remove(q);
        }
    }

    private void servePage(HttpExchange ex) throws IOException {
        byte[] page;
        try (var in = AquariumServer.class.getResourceAsStream("/aquarium.html")) {
            page = (in == null)
                    ? "<h1>aquarium.html missing from resources</h1>".getBytes(StandardCharsets.UTF_8)
                    : in.readAllBytes();
        }
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, page.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(page);
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
