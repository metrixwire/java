package com.metrixwire.agent;

import com.metrixwire.MetrixWire;
import com.metrixwire.instrument.HttpAdvice;
import com.metrixwire.instrument.HttpHandlerAdvice;
import com.metrixwire.instrument.JdbcAdvice;
import com.metrixwire.instrument.ServletAdvice;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.io.File;
import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Builds and installs the Byte Buddy {@link AgentBuilder} that weaves the
 * instrumentation advice. This class is loaded and invoked by
 * {@link MetrixWireAgent} <em>after</em> the agent jar has been appended to the
 * bootstrap classloader search, so it — together with the shaded Byte Buddy and
 * the {@code com.metrixwire.*} core classes — resolves consistently from the
 * bootstrap loader. That guarantees a single trace context is shared between the
 * servlet advice (in app-loaded servlet classes) and the JDBC / HTTP advice
 * (in JDK bootstrap classes).
 */
public final class AgentInstaller {

    private AgentInstaller() {
    }

    /**
     * Reads advice bytecode. Byte Buddy inlines advice by parsing the advice class
     * file; since our advice is on the bootstrap loader (which exposes no resource
     * stream), we point it straight at the agent jar. Falls back to the class-based
     * locator when the jar path is unknown.
     */
    private static ClassFileLocator locator;

    /** Invoked reflectively by {@link MetrixWireAgent#launch}. */
    public static void install(Instrumentation inst, String agentJarPath) {
        try {
            locator = buildLocator(agentJarPath);

            // Bootstrap config from -D properties / env and start the transport.
            MetrixWire.init();

            AgentBuilder builder = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            if (System.getProperty("metrixwire.debug") != null) {
                builder = builder.with(AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly());
            }
            builder = builder
                    // Ignore Byte Buddy and the agent's OWN classes so we never try to
                    // instrument ourselves. We intentionally do NOT blanket-ignore all of
                    // com.metrixwire.* — only the SDK's own packages — so a host app that
                    // happens to live under that namespace is still traced.
                    .ignore(
                            nameStartsWith("net.bytebuddy.")
                                    .or(nameStartsWith("com.metrixwire.shaded."))
                                    .or(nameStartsWith("com.metrixwire.agent."))
                                    .or(nameStartsWith("com.metrixwire.instrument."))
                                    .or(named("com.metrixwire.MetrixWire"))
                                    .or(named("com.metrixwire.CoreBridge"))
                                    .or(named("com.metrixwire.Transport"))
                                    .or(named("com.metrixwire.Trace"))
                                    .or(named("com.metrixwire.Span"))
                                    .or(named("com.metrixwire.TraceContext"))
                                    .or(named("com.metrixwire.Config"))
                                    .or(named("com.metrixwire.Json"))
                    );

            builder = installServlet(builder);
            builder = installHttpHandler(builder);
            builder = installJdbc(builder);
            builder = installHttp(builder);

            builder.installOn(inst);
        } catch (Throwable t) {
            // If instrumentation setup fails, the app must still run normally.
            log("agent install failed: " + t);
        }
    }

    // ── Servlet (javax + jakarta) ──────────────────────────────────────────────

    private static AgentBuilder installServlet(AgentBuilder builder) {
        // HttpServlet#service(ServletRequest, ServletResponse) in either namespace.
        // Match by subtype of the HttpServlet base class so all user servlets and
        // framework dispatcher servlets (Spring's DispatcherServlet, Jersey, etc.)
        // are covered.
        ElementMatcher.Junction<TypeDescription> servletTypes =
                hasSuperType(named("javax.servlet.http.HttpServlet"))
                        .or(hasSuperType(named("jakarta.servlet.http.HttpServlet")));

        return builder.type(servletTypes)
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription typeDescription,
                                                            ClassLoader classLoader,
                                                            JavaModule module,
                                                            java.security.ProtectionDomain pd) {
                        return b.visit(Advice.to(ServletAdvice.class, locator)
                                .on(named("service")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, namedOneOf(
                                                "javax.servlet.ServletRequest",
                                                "javax.servlet.http.HttpServletRequest",
                                                "jakarta.servlet.ServletRequest",
                                                "jakarta.servlet.http.HttpServletRequest")))));
                    }
                });
    }

    // ── Embedded com.sun.net.httpserver ─────────────────────────────────────────

    private static AgentBuilder installHttpHandler(AgentBuilder builder) {
        // HttpHandler#handle(HttpExchange) — covers the JDK's built-in HTTP server,
        // which is not a Servlet container. Skip the JDK's own internal handlers so
        // we only trace application handlers.
        return builder.type(hasSuperType(named("com.sun.net.httpserver.HttpHandler"))
                        .and(not(nameStartsWith("com.sun.net.httpserver")))
                        .and(not(nameStartsWith("sun.net.httpserver")))
                        .and(not(nameStartsWith("jdk."))))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription t, ClassLoader cl, JavaModule m,
                                                            java.security.ProtectionDomain pd) {
                        return b.visit(Advice.to(HttpHandlerAdvice.class, locator)
                                .on(named("handle").and(takesArguments(1))));
                    }
                });
    }

    // ── JDBC ───────────────────────────────────────────────────────────────────

    private static AgentBuilder installJdbc(AgentBuilder builder) {
        // Connection#prepareStatement(String, ...) — capture prepared SQL.
        // Match by NAME (not a .class literal): this installer is loaded by the
        // bootstrap loader, which on JDK 9+ can't see the platform-module java.sql.
        builder = builder.type(hasSuperType(named("java.sql.Connection")).and(not(isInterface())))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription t, ClassLoader cl, JavaModule m,
                                                            java.security.ProtectionDomain pd) {
                        return b
                                .visit(Advice.to(JdbcAdvice.PrepareStatement.class, locator)
                                        .on(namedOneOf("prepareStatement", "prepareCall")
                                                .and(takesArgument(0, String.class))))
                                .visit(Advice.to(JdbcAdvice.SetAutoCommit.class, locator)
                                        .on(named("setAutoCommit").and(takesArguments(1))))
                                .visit(Advice.to(JdbcAdvice.Commit.class, locator)
                                        .on(named("commit").and(takesArguments(0))));
                    }
                });

        // Statement (and PreparedStatement, a subtype) execute methods.
        builder = builder.type(hasSuperType(named("java.sql.Statement")).and(not(isInterface())))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription t, ClassLoader cl, JavaModule m,
                                                            java.security.ProtectionDomain pd) {
                        return b
                                // execute*(String sql, ...) — Statement path.
                                .visit(Advice.to(JdbcAdvice.StatementExecuteWithSql.class, locator)
                                        .on(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                                                .and(takesArgument(0, String.class))))
                                // execute*() — PreparedStatement path (SQL from registry).
                                .visit(Advice.to(JdbcAdvice.PreparedExecuteNoArg.class, locator)
                                        .on(namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                                                .and(takesArguments(0))))
                                .visit(Advice.to(JdbcAdvice.ExecuteBatch.class, locator)
                                        .on(namedOneOf("executeBatch", "executeLargeBatch")
                                                .and(takesArguments(0))));
                    }
                });
        return builder;
    }

    // ── Outbound HTTP ────────────────────────────────────────────────────────────

    private static AgentBuilder installHttp(AgentBuilder builder) {
        // java.net.HttpURLConnection#getResponseCode() — the send point.
        builder = builder.type(hasSuperType(named("java.net.HttpURLConnection")).and(not(isAbstract())))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription t, ClassLoader cl, JavaModule m,
                                                            java.security.ProtectionDomain pd) {
                        return b.visit(Advice.to(HttpAdvice.UrlConnection.class, locator)
                                .on(named("getResponseCode").and(takesArguments(0))));
                    }
                });

        // java.net.http.HttpClient#send(HttpRequest, BodyHandler) — best effort.
        builder = builder.type(hasSuperType(named("java.net.http.HttpClient")).and(not(isAbstract())))
                .transform(new AgentBuilder.Transformer() {
                    @Override
                    public DynamicType.Builder<?> transform(DynamicType.Builder<?> b,
                                                            TypeDescription t, ClassLoader cl, JavaModule m,
                                                            java.security.ProtectionDomain pd) {
                        return b.visit(Advice.to(HttpAdvice.JdkHttpClient.class, locator)
                                .on(named("send").and(takesArguments(2))));
                    }
                });
        return builder;
    }

    private static ClassFileLocator buildLocator(String agentJarPath) {
        try {
            if (agentJarPath != null) {
                File jar = new File(agentJarPath);
                if (jar.isFile()) {
                    return ClassFileLocator.ForJarFile.of(jar);
                }
            }
        } catch (Throwable t) {
            log("advice locator fell back to classloader: " + t);
        }
        // Fallback: read advice bytecode via the advice class's own classloader.
        return ClassFileLocator.ForClassLoader.of(AgentInstaller.class.getClassLoader());
    }

    private static void log(String msg) {
        try {
            System.err.println("[metrixwire] " + msg);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
