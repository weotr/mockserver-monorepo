package org.mockserver.netty.integration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Boots a MockServer instance in a forked JVM via {@code java -jar} against
 * the locally-built {@code mockserver-netty-no-dependencies-X.Y.Z.jar}.
 *
 * <p>The runner deliberately uses only the JDK ({@link ProcessBuilder} and
 * {@link HttpClient}) and avoids loading {@code MockServerClient} or any
 * shaded MockServer class on the test JVM's classpath — otherwise the test
 * JVM would end up with two copies of {@code org.mockserver.httpclient.
 * NettyHttpClient}: one unshaded from {@code mockserver-core}, one shaded
 * from this module's own jar — and binary-incompatible signatures on the
 * Netty types in the constructor cause a runtime {@code NoSuchMethodError}.
 *
 * <p>The forked JVM's merged stdout/stderr is drained on a background thread
 * into a buffer (and echoed to the test JVM's stdout so it still lands in the
 * surefire/failsafe log), so tests can assert on what the jar actually printed
 * while booting — see {@link #getOutput()} and {@link #awaitOutputContaining}.
 */
public class NoDependenciesJarRunner {

    public static final boolean DEBUG = false;
    private static final String NO_DEPS_ARTIFACT_PREFIX = "mockserver-netty-no-dependencies-";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration READY_POLL_INTERVAL = Duration.ofMillis(250);

    private final Process process;
    private final int port;
    /** Captured merged stdout/stderr of the forked JVM. Guarded by itself. */
    private final StringBuilder output = new StringBuilder();

    private NoDependenciesJarRunner(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public Process getProcess() {
        return process;
    }

    /**
     * Locate the shaded jar built by this module, fork a JVM to run it, and
     * block until {@code /mockserver/status} responds 200 (or fail after
     * {@link #READY_TIMEOUT}).
     */
    public static NoDependenciesJarRunner startServerUsingNoDependenciesJar(int mockServerPort) {
        return startServerUsingNoDependenciesJar(mockServerPort, null);
    }

    /**
     * As {@link #startServerUsingNoDependenciesJar(int)}, but additionally points
     * the forked JVM at {@code propertyFile} via {@code -Dmockserver.propertyFile=&lt;path&gt;}
     * — the exact system property {@code ConfigurationProperties} honours to locate
     * a populated {@code mockserver.properties}.
     *
     * <p>This drives the shaded jar through its property-file-read path during
     * {@code ConfigurationProperties} static initialisation, which is what surfaced
     * the {@code ExceptionInInitializerError}/{@code NoClassDefFoundError} in issues
     * #2344 and #2338 — the sensitive-value redaction path runs while a populated
     * file is read, and a transitive class reachable only there could be missing
     * from the uber jar without this guard catching it.
     *
     * @param propertyFile absolute path to a {@code mockserver.properties} file, or
     *                     {@code null} to boot without one
     */
    public static NoDependenciesJarRunner startServerUsingNoDependenciesJar(int mockServerPort, File propertyFile) {
        File jarFile = locateShadedJar();
        List<String> arguments = new ArrayList<>(Collections.singletonList(getJavaBin()));
        arguments.add("-Dfile.encoding=UTF-8");
        if (propertyFile != null) {
            // ConfigurationProperties first tries the classpath via getResourceAsStream,
            // then falls back to new FileInputStream(propertyFile()). The forked JVM's
            // classpath is the jar only, so the absolute path resolves via that filesystem
            // fallback — exercising the populated-property-file read at static-init time.
            arguments.add("-Dmockserver.propertyFile=" + propertyFile.getAbsolutePath());
        }
        if (DEBUG) {
            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005");
        }
        arguments.add("-jar");
        arguments.add(jarFile.getAbsolutePath());
        arguments.add("-serverPort");
        arguments.add(Integer.toString(mockServerPort));

        ProcessBuilder processBuilder = new ProcessBuilder(arguments);
        // Merge stderr into stdout so a single reader captures everything the
        // jar prints — including the SLF4J diagnostics, which go to stderr.
        processBuilder.redirectErrorStream(true);

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to start " + jarFile.getAbsolutePath(), ioe);
        }

        NoDependenciesJarRunner runner = new NoDependenciesJarRunner(process, mockServerPort);
        runner.startOutputGobbler();
        runner.waitUntilReady();
        return runner;
    }

    /** Stop the forked JVM. Idempotent. */
    public void stop() {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * The forked JVM's merged stdout/stderr captured so far. The stream is
     * drained continuously on a background thread, so this reflects everything
     * the jar has printed up to the moment of the call.
     */
    public String getOutput() {
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * Block until the captured output contains {@code token}, returning
     * {@code true} as soon as it does, or {@code false} if {@code timeout}
     * elapses first.
     */
    public boolean awaitOutputContaining(String token, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (getOutput().contains(token)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return getOutput().contains(token);
            }
        }
    }

    /**
     * Drain the forked JVM's output on a daemon thread: append every line to
     * {@link #output} and echo it to the test JVM's stdout. Draining is also
     * required for correctness — if the pipe buffer fills because nobody reads
     * it, the forked JVM blocks on its next write.
     */
    private void startOutputGobbler() {
        Thread gobbler = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append(System.lineSeparator());
                    }
                    System.out.println(line);
                }
            } catch (IOException streamClosed) {
                // forked JVM exited and closed the stream — nothing more to read
            }
        }, "no-dependencies-jar-output");
        gobbler.setDaemon(true);
        gobbler.start();
    }

    private void waitUntilReady() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/status"))
            .timeout(Duration.ofSeconds(2))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();

        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new RuntimeException("MockServer process exited before becoming ready (exit code "
                    + process.exitValue() + "). Forked jar output:\n" + getOutput());
            }
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException notListeningYet) {
                // server not yet listening — keep polling
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("Interrupted while waiting for MockServer to start", ie);
            }
            try {
                Thread.sleep(READY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("Interrupted while waiting for MockServer to start", ie);
            }
        }
        process.destroyForcibly();
        throw new RuntimeException("MockServer did not respond on port " + port + " within "
            + READY_TIMEOUT + ". Forked jar output:\n" + getOutput());
    }

    private static File locateShadedJar() {
        String version = System.getProperty("project.version", "");
        String basedir = System.getProperty("project.basedir", ".");
        if (version.isEmpty()) {
            // Fall back to whatever version sits next to the test class on disk.
            File targetDir = new File(basedir, "target");
            File[] candidates = targetDir.listFiles((d, name) ->
                name.startsWith(NO_DEPS_ARTIFACT_PREFIX) && name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
            if (candidates != null && candidates.length > 0) {
                return candidates[0];
            }
            throw new RuntimeException("Cannot locate " + NO_DEPS_ARTIFACT_PREFIX + "*.jar under " + targetDir.getAbsolutePath());
        }
        File jarFile = new File(basedir + "/target/" + NO_DEPS_ARTIFACT_PREFIX + version + ".jar");
        if (jarFile.exists()) {
            return jarFile;
        }
        File alt = new File(basedir + "/mockserver-netty-no-dependencies/target/" + NO_DEPS_ARTIFACT_PREFIX + version + ".jar");
        if (alt.exists()) {
            return alt;
        }
        throw new RuntimeException("Can't find jar file in the following locations: " +
            Arrays.asList(jarFile.getAbsolutePath(), alt.getAbsolutePath()));
    }

    private static String getJavaBin() {
        File javaHomeDirectory = new File(System.getProperty("java.home"));
        for (String javaExecutable : new String[]{"java", "java.exe"}) {
            File javaExeLocation = new File(javaHomeDirectory, "bin" + File.separator + javaExecutable);
            if (javaExeLocation.exists() && javaExeLocation.isFile()) {
                return javaExeLocation.getAbsolutePath();
            }
        }
        return "java";
    }
}
