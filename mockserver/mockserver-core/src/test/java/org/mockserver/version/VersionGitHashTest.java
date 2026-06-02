package org.mockserver.version;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Contract for {@link Version#getGitHash()}, the abbreviated git commit hash captured at build time
 * by the git-commit-id-maven-plugin and substituted into the generated {@link Version} class.
 */
public class VersionGitHashTest {

    @Test
    public void gitHashIsNeverNull() {
        assertThat(Version.getGitHash(), notNullValue());
    }

    @Test
    public void gitHashIsResolvedNotAnUnsubstitutedPlaceholder() {
        // if templating failed to substitute the value the getValue fallback returns "" rather than
        // leaking the literal "${git.commit.id.abbrev}" maven property reference
        assertThat(Version.getGitHash(), not(containsString("$")));
    }

    @Test
    public void gitHashIsEitherEmptyOrAHexAbbreviation() {
        // empty when no git metadata is available (e.g. a source tarball), otherwise a lower-case
        // hex commit-hash abbreviation
        assertThat(Version.getGitHash().matches("[0-9a-f]*"), is(true));
    }
}
