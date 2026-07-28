package com.metrixwire;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Batches finished traces and flushes them to the ingest endpoint on a daemon
 * thread. Every network path is wrapped so a dead/slow MetrixWire API can never
 * crash or block the host app: a short connect/read timeout, all errors
 * swallowed, sends happen off the request path. A final flush runs on the JVM
 * shutdown hook.
 */
final class Transport {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Config config;
    private final ConcurrentLinkedQueue<Trace> queue = new ConcurrentLinkedQueue<Trace>();
    private final AtomicInteger size = new AtomicInteger(0);
    private volatile boolean running = false;
    private Thread worker;

    Transport(Config config) {
        this.config = config;
    }

    void start() {
        if (running || !config.enabled) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "metrixwire-flush");
        worker.setDaemon(true); // never keep the JVM alive just for us
        worker.start();

        // Last-chance flush on shutdown.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    running = false;
                    flushAll();
                }
            }, "metrixwire-shutdown"));
        } catch (Throwable t) {
            // Some containers forbid adding shutdown hooks — ignore.
        }
    }

    void enqueue(Trace trace) {
        if (!config.enabled || trace == null) {
            return;
        }
        queue.offer(trace);
        int n = size.incrementAndGet();
        if (n >= config.maxBatch && worker != null) {
            // Nudge the worker to flush promptly on a full batch.
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(config.flushIntervalMs);
            } catch (InterruptedException e) {
                // Woken early by a full batch — fall through and flush.
            } catch (Throwable t) {
                // ignore
            }
            try {
                flushBatch();
            } catch (Throwable t) {
                // Monitoring must never break the app.
            }
        }
    }

    /** Send one batch (up to maxBatch traces). */
    private void flushBatch() {
        List<Trace> batch = drain(config.maxBatch);
        if (!batch.isEmpty()) {
            send(batch);
        }
    }

    /** Drain and send everything currently queued (used on shutdown). */
    private void flushAll() {
        try {
            List<Trace> batch;
            while (!(batch = drain(config.maxBatch)).isEmpty()) {
                send(batch);
            }
        } catch (Throwable t) {
            // swallow
        }
    }

    private List<Trace> drain(int max) {
        List<Trace> batch = new ArrayList<Trace>();
        Trace t;
        while (batch.size() < max && (t = queue.poll()) != null) {
            size.decrementAndGet();
            batch.add(t);
        }
        return batch;
    }

    private void send(List<Trace> traces) {
        if (traces.isEmpty() || config.apiKey.isEmpty()) {
            return;
        }
        HttpURLConnection conn = null;
        try {
            List<Object> traceMaps = new ArrayList<Object>();
            for (Trace t : traces) {
                traceMaps.add(t.toMap());
            }
            Map<String, Object> body = new java.util.LinkedHashMap<String, Object>();
            body.put("traces", traceMaps);
            byte[] payload = Json.write(body).getBytes(UTF8);

            URL url = new URL(config.endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.timeoutMs);
            conn.setReadTimeout(config.timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", config.apiKey);
            conn.setFixedLengthStreamingMode(payload.length);

            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }
            // Read the status to complete the request; ignore the value.
            conn.getResponseCode();
        } catch (Throwable t) {
            // Silently ignore — never propagate transport failures.
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }
}
