package ottv2.worker.match;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight Prometheus endpoint for the standalone match worker. */
public final class WorkerMetrics implements AutoCloseable {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Timer execution = Timer.builder("ottv2_match_execution_seconds").publishPercentileHistogram().register(registry);
    private final Timer queueLag = Timer.builder("ottv2_match_queue_lag_seconds").publishPercentileHistogram().register(registry);
    private final Counter finished = Counter.builder("ottv2_matches_finished_total").register(registry);
    private final Counter failed = Counter.builder("ottv2_matches_failed_total").register(registry);
    private final HttpServer server;

    public WorkerMetrics(int port) throws IOException {
        new ClassLoaderMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        registry.gauge("ottv2_worker_in_flight", inFlight);
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.setExecutor(null);
        server.start();
    }

    public Timer.Sample begin(java.util.Date queuedAt) {
        inFlight.incrementAndGet();
        if (queuedAt != null) queueLag.record(Math.max(0, System.currentTimeMillis() - queuedAt.getTime()),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        return Timer.start(registry);
    }

    public void completed(Timer.Sample sample) {
        sample.stop(execution);
        finished.increment();
        inFlight.decrementAndGet();
    }

    public void failed(Timer.Sample sample) {
        sample.stop(execution);
        failed.increment();
        inFlight.decrementAndGet();
    }

    @Override public void close() {
        server.stop(1);
        registry.close();
    }
}
