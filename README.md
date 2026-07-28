# metrixwire-agent (Java)

Zero-config APM agent for **Java 8+**. Attach one `-javaagent` and every HTTP
request, database query and outbound HTTP call is instrumented automatically.
There is **no manual span API** and no application code to change. Non-blocking:
if the MetrixWire endpoint is down, your app keeps running normally.

## Usage

Build the agent jar (see below), then start your app with the agent attached:

```bash
java -javaagent:/path/to/metrixwire-agent-0.1.0.jar \
     -Dmetrixwire.apiKey=mw_your_key \
     -jar your-app.jar
```

That's it. Every incoming request becomes a **trace**, and every query / outbound
HTTP call within it becomes a **span**. No annotations, no dependencies added to
your build, no middleware to register.

## How the automatic tracing works

The agent uses [Byte Buddy](https://bytebuddy.net/) to weave lightweight advice
into well-known entry points at class-load time. A trace is opened for **every
incoming request across every servlet container and the embedded JDK server** —
no per-route setup:

| What | Traced automatically | How |
|---|---|---|
| **Spring Boot / Spring MVC** | ✅ | `DispatcherServlet` is an `HttpServlet` — instrumented at `service(...)`. Route = `METHOD /uri`. |
| **Tomcat · Jetty · Undertow · any Servlet container** | ✅ | Any `javax.servlet.http.HttpServlet` **or** `jakarta.servlet.http.HttpServlet` subclass. Both namespaces are guarded, so whichever your app ships works. |
| **Framework-less `com.sun.net.httpserver.HttpServer`** | ✅ | `HttpHandler#handle(HttpExchange)` opens a trace — keeps the zero-config promise even with no framework. |
| **Plain JDBC** (`Statement` / `PreparedStatement`) | ✅ | `execute`, `executeQuery`, `executeUpdate`, `executeBatch` — captures the SQL, timing and a best-effort `rowCount`. |
| **JDBC via HikariCP / any pool · JDBC drivers** (Postgres, MySQL, H2, …) | ✅ | Instrumented at the `java.sql.Statement` / `java.sql.Connection` level, so every driver and pool is covered. |
| **DB transactions** | ✅ | `setAutoCommit(false)` → `commit()` timing becomes a `long_transaction` span. |
| **Outbound `HttpURLConnection`** | ✅ | `getResponseCode()` → `http_call` span with the target URL + status code. |
| **Outbound `java.net.http.HttpClient`** (Java 11+) | ✅ | `send(...)` → `http_call` span (best-effort). |

Each request trace also records a best-effort **memory delta** (`memoryMb`),
**response size** (`responseBytes`) and any **uncaught exception**
(`type` / `message` / top stack frames) — the signals the MetrixWire detectors
key on (`slow_db_query`, `n_plus_one_query`, `too_many_queries`,
`large_result_set`, `long_transaction`, `slow_endpoint`, `large_response_payload`,
`memory_spike`, `unhandled_exception`, …).

## Configuration

Read from system properties, with environment-variable fallbacks:

| System property | Env fallback | Default | Meaning |
|---|---|---|---|
| `-Dmetrixwire.apiKey` | `METRIXWIRE_KEY` | *(none)* | Your project API key. **Required** — without it the SDK stays disabled. |
| `-Dmetrixwire.endpoint` | `METRIXWIRE_ENDPOINT` | `http://localhost:3000/ingest` | Ingest URL. A base URL is fine — `/ingest` is appended if missing. |
| `-Dmetrixwire.enabled` | `METRIXWIRE_ENABLED` | `true` | Master switch. Set to `false` to disable entirely. |

```bash
# via env instead of -D
export METRIXWIRE_KEY=mw_your_key
export METRIXWIRE_ENDPOINT=https://ingest.metrixwire.com
java -javaagent:metrixwire-agent-0.1.0.jar -jar your-app.jar
```

## Escape hatch (optional)

The agent captures uncaught exceptions automatically. If a framework catches its
own exception before the servlet advice can see it, you can attach it to the
active trace manually — this is **not** a manual span API:

```java
try {
    // ...
} catch (Throwable t) {
    com.metrixwire.MetrixWire.captureException(t); // never throws
    throw t;
}
```

## Non-blocking behavior

- Traces are batched and flushed on a **daemon thread** (every ~5s, or immediately
  once ~20 are queued), off the request path, with a short connect/read timeout.
- **All** transport and instrumentation errors are swallowed — monitoring never
  throws into or blocks your application.
- A final flush runs on a JVM shutdown hook.

## Building the agent

Requires Maven and a JDK (8+).

```bash
mvn -f sdks/java/pom.xml -DskipTests package
# → sdks/java/target/metrixwire-agent-0.1.0.jar   (shaded, Byte Buddy relocated)
```

The produced jar is a self-contained agent: its manifest declares
`Premain-Class` / `Agent-Class` (`com.metrixwire.agent.MetrixWireAgent`),
`Can-Retransform-Classes` and `Can-Redefine-Classes`, and it bundles a relocated
copy of Byte Buddy (`com.metrixwire.shaded.bytebuddy`) so it never clashes with
your application's dependencies. Byte Buddy is the **only** third-party library
the agent uses — trace serialization is a tiny hand-rolled JSON writer.

### How it stays isolated

On start-up the agent appends its own jar to the **bootstrap** classloader search
and then loads the instrumentation installer from there. This lets advice woven
into JDK bootstrap classes (`java.sql.*`, `java.net.HttpURLConnection`) resolve
the agent's helper classes, and guarantees a single shared trace context across
servlet, JDBC and HTTP instrumentation — with no `LinkageError` and no impact on
your application's classloaders.
