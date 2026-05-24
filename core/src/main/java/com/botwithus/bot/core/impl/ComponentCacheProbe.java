package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.Layout;
import com.botwithus.bot.core.shm.SharedRegion;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone smoke-test for the slice-17 {@code (iface, comp, version)}
 * component cache. Connects to a running injected DLL, repeatedly calls
 * {@link com.botwithus.bot.api.GameAPI#getComponent} on a chosen
 * {@code (iface, comp)}, and prints whether each call hit the cache and
 * what the producer-side ifaceVersion looked like at the time.
 *
 * <p>Cache hits are inferred from elapsed time (RPC round-trip is ~ms;
 * cache hit is sub-100µs) and from a wrapper {@code RpcClient} that
 * counts {@code get_component} calls.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   java -p <classpath> -m com.botwithus.bot.core/com.botwithus.bot.core.impl.ComponentCacheProbe \
 *        <iface> <comp> [--pid N] [--ms 250] [--no-cache]
 * }</pre>
 *
 * <p>Workflow for verifying Phase 2 end-to-end:</p>
 * <ol>
 *   <li>Inject the DLL into a running RS3 client (which exercises
 *       slice 13-16 producer hooks).</li>
 *   <li>Open an interface in-game (e.g. inventory, world map). Note
 *       its iface id from {@code probe ifaceversions} or by trial.</li>
 *   <li>Run this probe pointing at one component within that iface.</li>
 *   <li>Watch: first iteration is a miss (RPC count: 0→1); subsequent
 *       iterations should be cache hits (RPC count stays at 1) until
 *       you interact with the UI in a way that mutates the iface
 *       (drag a tab, click a button, etc.) — at which point the
 *       version bumps and you'll see the next iteration miss.</li>
 * </ol>
 */
public final class ComponentCacheProbe {

    private ComponentCacheProbe() {}

    public static void main(String[] args) throws InterruptedException {
        Cli cli = parseArgs(args);
        if (cli == null) return;

        long pid = SharedRegion.parsePid(cli.pipeName).orElseThrow(() ->
                new IllegalStateException("pipe '" + cli.pipeName + "' has no embedded pid"));

        try (PipeClient pipe = new PipeClient(cli.pipeName);
             SharedRegion region = SharedRegion.open(pid)) {

            // Wrap RpcClient so we can count get_component calls. The wrapper
            // is a thin extension that overrides the one method we care about
            // (callSync(method, params)) and forwards everything else. This is
            // load-bearing for the probe's "did the cache hit?" inference: the
            // elapsed-time signal is noisy on cold runs and JVM warmup.
            AtomicLong getCompCalls = new AtomicLong(0);
            RpcClient rpc = new RpcClient(pipe) {
                @Override
                public Map<String, Object> callSync(String method, Map<String, Object> params) {
                    if ("get_component".equals(method)) {
                        getCompCalls.incrementAndGet();
                    }
                    return super.callSync(method, params);
                }
            };
            rpc.setConnectionName(cli.pipeName);
            rpc.start();

            GameAPIImpl api = cli.useCache
                    ? new GameAPIImpl(rpc, null,
                            iface -> region.snapshot().ifaceVersion(iface))
                    : new GameAPIImpl(rpc);

            System.out.printf("Bound to pipe=%s (pid=%d)%n", cli.pipeName, pid);
            System.out.printf("Cache: %s  Iface: %d  Comp: %d  Poll: %d ms%n",
                    cli.useCache ? "ENABLED" : "disabled (legacy ctor)",
                    cli.iface, cli.comp, cli.pollMs);
            if (cli.iface < 0 || cli.iface >= Layout.IFACE_VERSION_CAP) {
                System.out.printf(
                        "WARNING: iface %d is outside [0, %d) — cache lookups bypass " +
                                "to RPC every call (slot has no version token).%n",
                        cli.iface, Layout.IFACE_VERSION_CAP);
            }
            System.out.println();
            System.out.println("  iter |  ifaceVer | rpcCalls | dCalls | elapsed_us | result");
            System.out.println("-------+-----------+----------+--------+------------+-------------------");

            long lastRpcCalls = 0;
            for (int iter = 1; !Thread.currentThread().isInterrupted(); ++iter) {
                int ifaceVer = region.snapshot().ifaceVersion(cli.iface);
                long t0 = System.nanoTime();
                Component c = api.getComponent(cli.iface, cli.comp);
                long elapsedUs = (System.nanoTime() - t0) / 1000;

                long rpcCalls = getCompCalls.get();
                long delta    = rpcCalls - lastRpcCalls;
                lastRpcCalls  = rpcCalls;

                String result = c == null
                        ? "null (not found)"
                        : String.format(Locale.ROOT,
                                "type=%d pos=(%d,%d) size=(%dx%d)",
                                c.type(), c.x(), c.y(), c.width(), c.height());
                String marker = delta == 0 ? "  HIT" : "MISS ";
                System.out.printf("  %4d | %9d | %8d | %s%2d | %10d | %s%n",
                        iter, ifaceVer, rpcCalls, marker, delta, elapsedUs, result);

                Thread.sleep(cli.pollMs);
            }
        }
    }

    private record Cli(int iface, int comp, String pipeName, int pollMs, boolean useCache) {}

    private static Cli parseArgs(String[] args) {
        if (args.length < 2) {
            usage();
            return null;
        }
        int iface, comp;
        try {
            iface = Integer.parseInt(args[0]);
            comp  = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            usage();
            return null;
        }

        Long pidArg = null;
        int pollMs = 250;
        boolean useCache = true;
        for (int i = 2; i < args.length; ++i) {
            String a = args[i];
            switch (a) {
                case "--pid"     -> pidArg   = Long.parseLong(args[++i]);
                case "--ms"      -> pollMs   = Integer.parseInt(args[++i]);
                case "--no-cache"-> useCache = false;
                default -> {
                    System.err.println("unknown arg: " + a);
                    usage();
                    return null;
                }
            }
        }

        String pipeName;
        if (pidArg != null) {
            pipeName = "BotWithUs_" + pidArg;
        } else {
            var pids = SharedRegion.discoverPids();
            if (pids.isEmpty()) {
                System.err.println("No BotWithUs_<pid> pipes found. Is the DLL injected?");
                return null;
            }
            if (pids.size() > 1) {
                System.err.println("Multiple games detected " + pids + " — picking first; pass --pid to disambiguate.");
            }
            pipeName = "BotWithUs_" + pids.getFirst();
        }
        return new Cli(iface, comp, pipeName, pollMs, useCache);
    }

    private static void usage() {
        System.err.println("usage: ComponentCacheProbe <iface> <comp> [--pid N] [--ms MS] [--no-cache]");
        System.err.println();
        System.err.println("  Polls getComponent(iface, comp) every MS milliseconds (default 250).");
        System.err.println("  Reports cache hits/misses by counting get_component RPC calls and");
        System.err.println("  measuring elapsed time per call.");
        System.err.println("  --no-cache uses the legacy ctor (no cache, RPC every call).");
    }
}
