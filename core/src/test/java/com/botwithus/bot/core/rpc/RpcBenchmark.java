package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.msgpack.MessagePackCodec;
import com.botwithus.bot.core.pipe.PipeClient;

import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RPC latency benchmark for the Java scripting framework.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>raw</b> (default): Direct pipe send + blocking read, bypassing the
 *       RpcClient polling loop. Comparable to the Python benchmark.</li>
 *   <li><b>rpc</b>: Through the full RpcClient stack including the
 *       {@code available()} + {@code Thread.sleep(1)} polling loop.</li>
 * </ul>
 *
 * <pre>
 *   ./gradlew :core:benchmark                          # raw mode, all methods
 *   ./gradlew :core:benchmark --args="--mode rpc"      # through RpcClient
 *   ./gradlew :core:benchmark --args="--markdown"      # markdown output
 * </pre>
 */
public class RpcBenchmark {

    private static final Map<String, BenchmarkTarget> BENCHMARKS = new LinkedHashMap<>();

    static {
        BENCHMARKS.put("ping", new BenchmarkTarget("rpc.ping", null));
        BENCHMARKS.put("get_login_state", new BenchmarkTarget("get_login_state", null));
        BENCHMARKS.put("get_varp", new BenchmarkTarget("get_varp", Map.of("var_id", 0)));
        BENCHMARKS.put("get_game_cycle", new BenchmarkTarget("get_game_cycle", null));
        BENCHMARKS.put("get_local_player", new BenchmarkTarget("get_local_player", null));
        BENCHMARKS.put("query_inventories", new BenchmarkTarget("query_inventories", Map.of()));
        BENCHMARKS.put("list_methods", new BenchmarkTarget("rpc.list_methods", null));
        BENCHMARKS.put("query_entities", new BenchmarkTarget("query_entities",
                Map.of("type", "npc", "maxResults", 10)));
    }

    record BenchmarkTarget(String method, Map<String, Object> params) {}

    record BenchmarkResult(String name, double[] latenciesUs, int errors) {

        double min()  { return latenciesUs.length > 0 ? latenciesUs[0] : 0; }
        double max()  { return latenciesUs.length > 0 ? latenciesUs[latenciesUs.length - 1] : 0; }

        double percentile(double p) {
            if (latenciesUs.length == 0) return 0;
            int idx = (int) (latenciesUs.length * p);
            return latenciesUs[Math.min(idx, latenciesUs.length - 1)];
        }

        double mean() {
            if (latenciesUs.length == 0) return 0;
            double sum = 0;
            for (double v : latenciesUs) sum += v;
            return sum / latenciesUs.length;
        }

        double stddev() {
            if (latenciesUs.length < 2) return 0;
            double m = mean();
            double sumSq = 0;
            for (double v : latenciesUs) sumSq += (v - m) * (v - m);
            return Math.sqrt(sumSq / (latenciesUs.length - 1));
        }
    }

    private final PipeClient pipe;
    private final RpcClient rpc;
    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final int iterations;
    private final int warmup;
    private final List<String> methods;
    private final boolean rawMode;

    public RpcBenchmark(PipeClient pipe, RpcClient rpc, int iterations, int warmup,
                        List<String> methods, boolean rawMode) {
        this.pipe = pipe;
        this.rpc = rpc;
        this.iterations = iterations;
        this.warmup = warmup;
        this.methods = methods;
        this.rawMode = rawMode;
    }

    /**
     * Raw round-trip: encode → send → blocking read → decode.
     * No polling loop, no Thread.sleep. Matches the Python benchmark approach.
     */
    private Map<String, Object> rawRoundTrip(String method, Map<String, Object> params) {
        int id = idCounter.getAndIncrement();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("id", id);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }

        pipe.send(MessagePackCodec.encode(request));

        // Blocking read — no polling, no sleep
        while (true) {
            byte[] responseBytes = pipe.readMessage();
            Map<String, Object> msg = MessagePackCodec.decode(responseBytes);
            // Skip event messages, wait for our response
            if (!msg.containsKey("event")) {
                return msg;
            }
        }
    }

    public BenchmarkResult runBenchmark(String name) {
        BenchmarkTarget target = BENCHMARKS.get(name);
        if (target == null) {
            System.err.println("Unknown benchmark: " + name);
            return new BenchmarkResult(name, new double[0], 0);
        }

        // Warmup
        for (int i = 0; i < warmup; i++) {
            try {
                if (rawMode) {
                    rawRoundTrip(target.method(), target.params());
                } else {
                    rpc.callSyncRaw(target.method(), target.params());
                }
            } catch (Exception e) {
                // ignore warmup errors
            }
        }

        // Benchmark
        List<Double> latencies = new ArrayList<>(iterations);
        int errors = 0;

        for (int i = 0; i < iterations; i++) {
            long startNs = System.nanoTime();
            try {
                if (rawMode) {
                    rawRoundTrip(target.method(), target.params());
                } else {
                    rpc.callSyncRaw(target.method(), target.params());
                }
                long elapsedNs = System.nanoTime() - startNs;
                latencies.add(elapsedNs / 1_000.0); // convert to microseconds
            } catch (Exception e) {
                errors++;
            }
        }

        double[] sorted = latencies.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new BenchmarkResult(name, sorted, errors);
    }

    public List<BenchmarkResult> runAll() {
        List<BenchmarkResult> results = new ArrayList<>();
        for (String name : methods) {
            if (!BENCHMARKS.containsKey(name)) {
                System.err.println("Skipping unknown benchmark: " + name);
                continue;
            }
            results.add(runBenchmark(name));
        }
        return results;
    }

    // ========================== Output ==========================

    public static void printResults(List<BenchmarkResult> results, PrintStream out) {
        out.println();
        out.printf("%-22s %10s %10s %10s %10s %10s %10s %10s%n",
                "Method", "min (us)", "p50 (us)", "p95 (us)", "p99 (us)", "max (us)", "mean (us)", "std (us)");
        out.println("-".repeat(102));

        for (BenchmarkResult r : results) {
            if (r.latenciesUs().length == 0) {
                out.printf("%-22s  no successful responses (errors=%d)%n", r.name(), r.errors());
                continue;
            }
            out.printf("%-22s %10.1f %10.1f %10.1f %10.1f %10.1f %10.1f %10.1f%n",
                    r.name(), r.min(), r.percentile(0.50), r.percentile(0.95),
                    r.percentile(0.99), r.max(), r.mean(), r.stddev());
            if (r.errors() > 0) {
                out.printf("  (errors: %d)%n", r.errors());
            }
        }
        out.println();
    }

    public static void printMarkdown(List<BenchmarkResult> results, PrintStream out) {
        out.println();
        out.println("| Method | min (us) | p50 (us) | p95 (us) | p99 (us) | max (us) | mean (us) | std (us) |");
        out.println("|--------|----------|----------|----------|----------|----------|-----------|----------|");

        for (BenchmarkResult r : results) {
            if (r.latenciesUs().length == 0) continue;
            out.printf("| `%s` | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |%n",
                    r.name(), r.min(), r.percentile(0.50), r.percentile(0.95),
                    r.percentile(0.99), r.max(), r.mean(), r.stddev());
        }
        out.println();
    }

    // ========================== Main ==========================

    public static void main(String[] args) {
        int iterations = 1000;
        int warmup = 50;
        String pipeName = null;
        List<String> methods = null;
        boolean markdown = false;
        boolean rawMode = true; // default to raw for fair comparison

        // Simple arg parsing
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-n", "--iterations" -> iterations = Integer.parseInt(args[++i]);
                case "-w", "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--pipe" -> pipeName = args[++i];
                case "--markdown", "--md" -> markdown = true;
                case "--mode" -> {
                    String mode = args[++i];
                    rawMode = mode.equals("raw");
                }
                case "--methods" -> {
                    methods = new ArrayList<>();
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        methods.add(args[++i]);
                    }
                }
                case "--help", "-h" -> {
                    System.out.println("Usage: RpcBenchmark [options]");
                    System.out.println("  -n, --iterations N   Iterations per method (default: 1000)");
                    System.out.println("  -w, --warmup N       Warmup iterations (default: 50)");
                    System.out.println("  --pipe NAME          Pipe name (auto-detected if omitted)");
                    System.out.println("  --mode raw|rpc       raw = direct pipe I/O (default), rpc = through RpcClient");
                    System.out.println("  --methods m1 m2 ...  Specific methods to benchmark");
                    System.out.println("  --markdown, --md     Output as Markdown table");
                    System.out.println("  -h, --help           Show this help");
                    System.out.println();
                    System.out.println("Available methods: " + String.join(", ", BENCHMARKS.keySet()));
                    return;
                }
            }
        }

        if (methods == null) {
            methods = new ArrayList<>(BENCHMARKS.keySet());
        }

        // Auto-detect pipe
        if (pipeName == null) {
            List<String> pipes = PipeClient.scanPipes();
            if (pipes.isEmpty()) {
                System.err.println("No BotWithUs pipe found. Is the game running?");
                System.exit(1);
            }
            pipeName = pipes.getFirst();
            System.out.println("Auto-detected pipe: " + pipeName);
        }

        System.out.printf("Pipe: \\\\.\\pipe\\%s%n", pipeName);
        System.out.printf("Iterations: %d  Warmup: %d%n", iterations, warmup);
        System.out.printf("Mode: %s%n", rawMode ? "raw (direct pipe I/O)" : "rpc (through RpcClient)");
        System.out.printf("Runtime: Java %s%n", Runtime.version());

        PipeClient pipe = new PipeClient(pipeName);
        RpcClient rpc = rawMode ? null : new RpcClient(pipe);

        try {
            RpcBenchmark benchmark = new RpcBenchmark(pipe, rpc, iterations, warmup, methods, rawMode);
            List<BenchmarkResult> results = benchmark.runAll();

            if (markdown) {
                printMarkdown(results, System.out);
            } else {
                printResults(results, System.out);
            }
        } finally {
            if (rpc != null) rpc.close();
            else pipe.close();
        }
    }
}
