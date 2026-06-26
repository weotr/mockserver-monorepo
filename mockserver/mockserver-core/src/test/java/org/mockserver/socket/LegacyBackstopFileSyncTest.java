package org.mockserver.socket;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Regression guard for issue #2232 (fix commit 07aaf49a8).
 *
 * <p>To keep legacy clients working, the project restored byte-identical copies of a few
 * bundled resources at their <em>pre-monorepo</em> paths (directly under the repository root,
 * e.g. {@code mockserver-core/...}) alongside the canonical copies that now live under the
 * {@code mockserver/} module tree (e.g. {@code mockserver/mockserver-core/...}). An old
 * published client (mockserver-client@5.15.0) fetches the CA certificate from a hardcoded
 * {@code raw.githubusercontent.com} URL whose path matches the pre-monorepo layout; GitHub's
 * repo-rename redirect rewrites the host but not the in-repo path, so the backstop copy at the
 * legacy path is what makes that URL resolve instead of 404-ing.
 *
 * <p>These backstop files live <strong>outside</strong> {@code mockserver/pom.xml}'s module set,
 * so no Maven module compiles, copies, or references them. Nothing validates them — they can
 * silently drift from the canonical copy, or be deleted, reintroducing #2232 with no failing
 * build. This test is that missing validation: for every backstop file it asserts the file
 * exists and is byte-identical to its canonical counterpart under {@code mockserver/}.
 *
 * <p>The test is plain JUnit and Docker-free. It locates the repository root by walking up from
 * the module basedir until it finds a directory that contains the canonical {@code mockserver/}
 * sub-tree, so it works regardless of the working directory Surefire launches with.
 */
public class LegacyBackstopFileSyncTest {

    /**
     * Each pair is {@code { legacyBackstopRelativePath, canonicalRelativePath }}, both relative to
     * the repository root. Add a row here whenever a new legacy backstop copy is introduced.
     */
    private static final String[][] BACKSTOP_TO_CANONICAL = {
        {
            "mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem",
            "mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem"
        },
        {
            "mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json",
            "mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json"
        }
    };

    @Test
    public void everyLegacyBackstopFileIsByteIdenticalToItsCanonicalCounterpart() throws Exception {
        File repoRoot = locateRepositoryRoot();

        for (String[] pair : BACKSTOP_TO_CANONICAL) {
            Path backstop = repoRoot.toPath().resolve(pair[0]);
            Path canonical = repoRoot.toPath().resolve(pair[1]);

            assertThat(
                "canonical source file is missing (cannot validate backstop) - expected at " + canonical,
                Files.exists(canonical), is(true));

            assertThat(
                "legacy backstop file for issue #2232 is missing - expected at " + backstop
                    + " - re-create it as a byte-identical copy of " + canonical
                    + " (its absence 404s the bundled CA fetch for legacy clients)",
                Files.exists(backstop), is(true));

            byte[] backstopBytes = Files.readAllBytes(backstop);
            byte[] canonicalBytes = Files.readAllBytes(canonical);

            assertThat(
                "legacy backstop file for issue #2232 has drifted from its canonical source - "
                    + backstop + " is no longer byte-identical to " + canonical
                    + " - re-sync it from the canonical copy (e.g. `cp " + pair[1] + " " + pair[0] + "`)",
                Arrays.equals(backstopBytes, canonicalBytes), is(true));
        }
    }

    /**
     * Walks up from the module basedir ({@code user.dir} when Surefire runs this module) until it
     * finds the repository root, identified as the directory that contains the canonical
     * {@code mockserver/mockserver-core/.../CertificateAuthorityCertificate.pem} sub-path. This is
     * robust to the exact module the test is launched from.
     */
    private static File locateRepositoryRoot() {
        String canonicalMarker = BACKSTOP_TO_CANONICAL[0][1];
        File current = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (current != null) {
            if (new File(current, canonicalMarker).isFile()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException(
            "could not locate the repository root by walking up from user.dir="
                + System.getProperty("user.dir") + " looking for marker '" + canonicalMarker + "'");
    }
}
