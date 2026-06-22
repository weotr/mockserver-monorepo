package org.mockserver.testing;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Build-time guard that detects test classes which mutate JVM-global static state but are NOT listed
 * in the sequential Surefire phase of {@code mockserver-core/pom.xml}. This closes the gap left by
 * {@link ParallelStaticStateGuardTest}, which only checks that the exclude/include lists are symmetric
 * but cannot detect a brand-new stateful test that is missing from BOTH lists.
 *
 * <p>Detection patterns (high-signal, curated to minimise false positives):
 * <ul>
 *   <li>{@code ConfigurationProperties.<setter>(<non-empty-arg>)} — static setter call with an argument
 *       (a no-arg call is a getter and is not flagged)</li>
 *   <li>{@code System.setProperty(} / {@code System.clearProperty(}</li>
 *   <li>{@code .getInstance().reset(} / {@code .getInstance().clear(} — singleton state mutation</li>
 *   <li>{@code Metrics.resetAdditionalMetricsForTesting(} / {@code PrometheusRegistry.defaultRegistry}
 *       — Prometheus global state</li>
 * </ul>
 *
 * <p>To suppress a false positive, add a class-level comment:
 * {@code // @ParallelStateGuardSuppress: <reason>}
 *
 * @see ParallelStaticStateGuardTest
 */
// @ParallelStateGuardSuppress: this guard test references mutation patterns only as detection regexes, not actual calls
public class GlobalStateMutationGuardTest {

    private static final Path POM = Paths.get("pom.xml");
    private static final Path TEST_ROOT = Paths.get("src", "test", "java");

    /** Files that are themselves part of the guard infrastructure and must not self-flag. */
    private static final Set<String> GUARD_FILES = Set.of(
        "GlobalStateMutationGuardTest.java",
        "ParallelStaticStateGuardTest.java"
    );

    /**
     * Each pattern is a compiled regex matched against individual source lines (after stripping
     * leading/trailing whitespace). The pattern name is used in violation messages.
     */
    private static final List<DetectionPattern> PATTERNS = List.of(
        // ConfigurationProperties setter: method call with at least one argument.
        // Matches: ConfigurationProperties.fooBar(something)
        // Does NOT match: ConfigurationProperties.fooBar()  (getter)
        // Does NOT match: ConfigurationProperties.fooBar()  inside another call like when(...ConfigurationProperties.x())
        new DetectionPattern(
            "ConfigurationProperties.<setter>(<arg>)",
            Pattern.compile("(?<![\\w])ConfigurationProperties\\.[a-z]\\w*\\((?!\\))[^)]+\\)")
        ),
        new DetectionPattern(
            "System.setProperty(",
            Pattern.compile("System\\.setProperty\\(")
        ),
        new DetectionPattern(
            "System.clearProperty(",
            Pattern.compile("System\\.clearProperty\\(")
        ),
        new DetectionPattern(
            ".getInstance().reset(",
            Pattern.compile("\\.getInstance\\(\\)\\.reset\\(")
        ),
        new DetectionPattern(
            ".getInstance().clear(",
            Pattern.compile("\\.getInstance\\(\\)\\.clear\\(")
        ),
        new DetectionPattern(
            "Metrics.resetAdditionalMetricsForTesting(",
            Pattern.compile("Metrics\\.resetAdditionalMetricsForTesting\\(")
        ),
        new DetectionPattern(
            "PrometheusRegistry.defaultRegistry",
            Pattern.compile("PrometheusRegistry\\.defaultRegistry")
        )
    );

    /** Suppression marker: if present anywhere in the file, the file is skipped. */
    private static final String SUPPRESS_MARKER = "@ParallelStateGuardSuppress";

    @Test
    public void allGlobalStateMutatingTestsMustBeInSequentialPhase() throws IOException {
        assertTrue("expected to find mockserver-core/pom.xml at " + POM.toAbsolutePath(), Files.exists(POM));
        assertTrue("expected to find src/test/java at " + TEST_ROOT.toAbsolutePath(), Files.isDirectory(TEST_ROOT));

        // Parse the sequential-phase includes from pom.xml
        String pom = new String(Files.readAllBytes(POM));
        Set<String> sequentialClassNames = extractSequentialClassNames(pom);

        // Scan all test .java files
        List<Violation> violations = new ArrayList<>();
        Files.walkFileTree(TEST_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith("Test.java") && !fileName.endsWith("Tests.java")) {
                    return FileVisitResult.CONTINUE;
                }
                if (GUARD_FILES.contains(fileName)) {
                    return FileVisitResult.CONTINUE;
                }

                String content = new String(Files.readAllBytes(file));

                // Check suppression marker
                if (content.contains(SUPPRESS_MARKER)) {
                    return FileVisitResult.CONTINUE;
                }

                // Extract class name (without .java)
                String className = fileName.replace(".java", "");

                // Check if already in the sequential phase
                if (sequentialClassNames.contains(className)) {
                    return FileVisitResult.CONTINUE;
                }

                // Scan line by line for mutation patterns
                String[] lines = content.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();

                    // Skip single-line comments
                    if (line.startsWith("//")) {
                        continue;
                    }
                    // Skip lines that are inside block comments (crude but effective)
                    // — look for lines starting with * (Javadoc/block comment body)
                    if (line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    // Skip import statements (they reference ConfigurationProperties but don't call setters)
                    if (line.startsWith("import ")) {
                        continue;
                    }
                    // Skip string literals containing the pattern — check if the match is inside quotes
                    // (This is a heuristic: if the line has the pattern but the whole line is a string
                    // assignment like `"ConfigurationProperties.foo(bar)"`, skip it. We do this by
                    // stripping quoted strings before matching.)
                    String lineWithoutStrings = line.replaceAll("\"[^\"]*\"", "\"\"");

                    for (DetectionPattern dp : PATTERNS) {
                        if (dp.pattern.matcher(lineWithoutStrings).find()) {
                            violations.add(new Violation(className, file.toString(), i + 1, line, dp.name));
                            break; // one violation per file is enough to flag it
                        }
                    }
                    // Once we've found one violation for this file, stop scanning it
                    if (!violations.isEmpty() && violations.get(violations.size() - 1).className.equals(className)) {
                        break;
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        if (!violations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Found ").append(violations.size())
                .append(" test class(es) that mutate JVM-global static state but are NOT in the sequential ")
                .append("Surefire phase. These will cause flaky failures under parallel=classes execution.\n\n");
            for (Violation v : violations) {
                msg.append("  - ").append(v.className).append(" (").append(v.filePath).append(")\n");
                msg.append("    line ").append(v.lineNumber).append(": ").append(v.lineContent.trim()).append("\n");
                msg.append("    matched pattern: ").append(v.patternName).append("\n\n");
            }
            msg.append("FIX: add **/").append("<ClassName>.java to BOTH the parallel <excludes> AND sequential ")
                .append("<includes> in mockserver-core/pom.xml (keep them symmetric). If the test is genuinely ")
                .append("parallel-safe (e.g. it only reads, uses a local Configuration instance, or the match ")
                .append("is a false positive), add a class-level comment:\n")
                .append("  // @ParallelStateGuardSuppress: <reason>\n");
            fail(msg.toString());
        }
    }

    /**
     * Extracts the simple class names (without extension) from the sequential-tests execution
     * {@code <includes>} in the pom.xml.
     */
    private Set<String> extractSequentialClassNames(String pom) {
        Matcher m = Pattern
            .compile("(?s)<execution>\\s*<id>sequential-tests</id>(.*?)</execution>")
            .matcher(pom);
        assertTrue("could not find <execution> with <id>sequential-tests</id> in pom.xml", m.find());
        String sequentialBlock = m.group(1);

        Set<String> classNames = new TreeSet<>();
        Matcher im = Pattern.compile("<include>\\s*(.*?)\\s*</include>").matcher(sequentialBlock);
        while (im.find()) {
            String pattern = im.group(1).trim();
            // Pattern is like **/ConfigurationTest.java — extract the class name
            String fileName = pattern;
            if (fileName.contains("/")) {
                fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
            }
            if (fileName.startsWith("**/")) {
                fileName = fileName.substring(3);
            }
            if (fileName.endsWith(".java")) {
                fileName = fileName.substring(0, fileName.length() - 5);
            }
            // Skip glob patterns like **/*IntegrationTest.java (these are excludes in the sequential phase)
            if (!fileName.contains("*")) {
                classNames.add(fileName);
            }
        }
        return classNames;
    }

    private static class DetectionPattern {
        final String name;
        final Pattern pattern;

        DetectionPattern(String name, Pattern pattern) {
            this.name = name;
            this.pattern = pattern;
        }
    }

    private static class Violation {
        final String className;
        final String filePath;
        final int lineNumber;
        final String lineContent;
        final String patternName;

        Violation(String className, String filePath, int lineNumber, String lineContent, String patternName) {
            this.className = className;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.patternName = patternName;
        }
    }
}
