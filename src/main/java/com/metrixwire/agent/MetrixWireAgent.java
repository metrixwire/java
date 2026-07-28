package com.metrixwire.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.jar.JarFile;

/**
 * The agent entry point and bootstrap launcher.
 *
 * <p>Zero-config: the user runs
 * {@code java -javaagent:metrixwire-agent.jar -Dmetrixwire.apiKey=mw_... -jar app.jar}
 * with no application code changes. {@code premain} (JVM start) and
 * {@code agentmain} (dynamic attach) both:
 *
 * <ol>
 *   <li>append the agent jar to the <em>bootstrap</em> classloader search, then</li>
 *   <li>reflectively invoke {@link AgentInstaller#install(Instrumentation)}.</li>
 * </ol>
 *
 * <p>Why the two-step dance: the agent instruments JDK bootstrap classes
 * ({@code java.sql.*}, {@code java.net.HttpURLConnection}, …). Advice woven into a
 * bootstrap class can only reference classes visible to the bootstrap loader.
 * By appending the jar to the bootstrap search first and only <em>then</em>
 * loading the installer (and, transitively, the shaded Byte Buddy + the
 * {@code com.metrixwire.*} core), everything resolves consistently from the
 * bootstrap loader — one copy, one shared trace context, no {@code LinkageError}.
 *
 * <p>This launcher itself deliberately touches <strong>no</strong> Byte Buddy or
 * {@code com.metrixwire} core class before the bootstrap append. Nothing here is
 * allowed to throw into the host application.
 */
public final class MetrixWireAgent {

    private MetrixWireAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        launch(inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        launch(inst);
    }

    private static void launch(Instrumentation inst) {
        try {
            File jar = locateAgentJar();
            if (jar != null) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            }

            // Load the installer AFTER the bootstrap append so it (and everything it
            // pulls in) resolves from the bootstrap loader. Reflection keeps this
            // launcher from statically linking those classes on its own loader. We
            // pass the jar path so the installer can build a ClassFileLocator that
            // reads advice bytecode (Byte Buddy needs the raw class file, and the
            // bootstrap loader exposes no resource stream for it).
            Class<?> installer = Class.forName("com.metrixwire.agent.AgentInstaller");
            Method install = installer.getMethod("install", Instrumentation.class, String.class);
            install.invoke(null, inst, jar == null ? null : jar.getAbsolutePath());
        } catch (Throwable t) {
            // If anything goes wrong, the app must still run normally.
            log("agent bootstrap failed: " + t);
        }
    }

    /**
     * Locate the running agent jar. It must be a real jar file so it can be appended
     * to the bootstrap search and read by a ClassFileLocator. Returns null otherwise.
     */
    private static File locateAgentJar() {
        try {
            java.net.URL loc = MetrixWireAgent.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            File jar = new File(loc.toURI());
            if (jar.isFile() && jar.getName().endsWith(".jar")) {
                return jar;
            }
        } catch (Throwable t) {
            log("could not locate agent jar: " + t);
        }
        return null;
    }

    private static void log(String msg) {
        try {
            System.err.println("[metrixwire] " + msg);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
