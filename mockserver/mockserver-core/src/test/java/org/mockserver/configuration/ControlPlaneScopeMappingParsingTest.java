package org.mockserver.configuration;

import org.junit.Test;
import org.mockserver.authentication.authorization.ControlPlaneRole;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;

/**
 * Unit tests for parsing the {@code controlPlaneScopeMapping} property string into a
 * scope-to-role map. Pure parsing (no global ConfigurationProperties state), so it runs
 * in the parallel Surefire phase.
 */
// @ParallelStateGuardSuppress: parseScopeMapping is a pure static parser that returns a
// new map and mutates NO global state; the guard's setter heuristic is a false positive here.
public class ControlPlaneScopeMappingParsingTest {

    @Test
    public void parsesValuePairsCaseInsensitively() {
        Map<String, ControlPlaneRole> map =
            ConfigurationProperties.parseScopeMapping("platform-admins=admin,qa-team=Mutate, viewers = read ");
        assertThat(map.get("platform-admins"), is(ControlPlaneRole.ADMIN));
        assertThat(map.get("qa-team"), is(ControlPlaneRole.MUTATE));
        assertThat(map.get("viewers"), is(ControlPlaneRole.READ));
    }

    @Test
    public void blankYieldsEmptyMap() {
        assertThat(ConfigurationProperties.parseScopeMapping(""), is(anEmptyMap()));
        assertThat(ConfigurationProperties.parseScopeMapping(null), is(anEmptyMap()));
    }

    @Test
    public void unrecognisedRolesAndMalformedPairsAreSkippedFailClosed() {
        // a typo'd role, a missing '=', a blank key, and a blank role must NOT widen access
        Map<String, ControlPlaneRole> map =
            ConfigurationProperties.parseScopeMapping("ops=superuser,bare-scope,=admin,trailing=");
        assertThat(map, is(anEmptyMap()));
    }

    @Test
    public void validPairsSurviveAlongsideMalformedOnes() {
        Map<String, ControlPlaneRole> map =
            ConfigurationProperties.parseScopeMapping("ops=superuser,viewers=read");
        assertThat(map, not(hasKey("ops")));
        assertThat(map.get("viewers"), is(ControlPlaneRole.READ));
    }
}
