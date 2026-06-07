package org.mockserver.netty.proxy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Shared test infrastructure for Docker-CLI-based end-to-end integration tests
 * ({@link SoOriginalDstEndToEndIT}, {@link TproxyEndToEndIT}). These tests use
 * {@code docker build} / {@code docker run} directly (via {@link ProcessBuilder})
 * rather than Testcontainers, to avoid version-compatibility issues between
 * docker-java and newer Docker Engine releases.
 * <p>
 * Package-private — only used within the proxy test package.
 */
final class DockerCliTestSupport {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(DockerCliTestSupport.class);

    private DockerCliTestSupport() {
        // utility class
    }

    /**
     * Checks Docker availability using the {@code docker info} CLI command.
     *
     * @return {@code true} if Docker is available and responsive
     */
    static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start();
            // Drain output to prevent hanging
            try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // discard
                }
            }
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs a process, optionally feeding stdin, and returns the exit code.
     * <p>
     * If the last element of {@code cmdAndStdin} contains "{@code FROM }" it is
     * treated as Dockerfile content to pipe to stdin; the remaining elements form
     * the command array.
     *
     * @param cmdAndStdin command tokens, with an optional Dockerfile-content last element
     * @return the process exit code, or {@code -1} if it did not finish within 120 seconds
     */
    static int runProcess(String... cmdAndStdin) throws IOException, InterruptedException {
        // Last element is stdin content if it contains a Dockerfile directive
        String stdin = null;
        String[] cmd;
        if (cmdAndStdin[cmdAndStdin.length - 1].contains("FROM ")) {
            stdin = cmdAndStdin[cmdAndStdin.length - 1];
            cmd = new String[cmdAndStdin.length - 1];
            System.arraycopy(cmdAndStdin, 0, cmd, 0, cmd.length);
        } else {
            cmd = cmdAndStdin;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        }

        // Drain output
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.debug("docker build: {}", line);
            }
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    /**
     * Finds the MockServer fat JAR in the build output directory.
     *
     * @return path to the fat JAR, or {@code null} if not found
     */
    static Path findFatJar() {
        try {
            Path targetDir = Paths.get("target");
            if (!Files.isDirectory(targetDir)) {
                // Try relative to mockserver-netty module
                targetDir = Paths.get("mockserver-netty", "target");
            }
            if (!Files.isDirectory(targetDir)) {
                return null;
            }
            return Files.list(targetDir)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("mockserver-netty-")
                        && name.endsWith("-jar-with-dependencies.jar");
                })
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
