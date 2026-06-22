package org.mockserver.authentication.authorization;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.authentication.authorization.ControlPlaneRole.ADMIN;
import static org.mockserver.authentication.authorization.ControlPlaneRole.MUTATE;
import static org.mockserver.authentication.authorization.ControlPlaneRole.READ;

/**
 * Unit tests for the coarse, hierarchical control-plane role model:
 * ADMIN ⊇ MUTATE ⊇ READ.
 */
public class ControlPlaneRoleTest {

    @Test
    public void adminSatisfiesEverything() {
        assertThat(ADMIN.satisfies(READ), is(true));
        assertThat(ADMIN.satisfies(MUTATE), is(true));
        assertThat(ADMIN.satisfies(ADMIN), is(true));
    }

    @Test
    public void mutateSatisfiesReadAndMutateButNotAdmin() {
        assertThat(MUTATE.satisfies(READ), is(true));
        assertThat(MUTATE.satisfies(MUTATE), is(true));
        assertThat(MUTATE.satisfies(ADMIN), is(false));
    }

    @Test
    public void readSatisfiesOnlyRead() {
        assertThat(READ.satisfies(READ), is(true));
        assertThat(READ.satisfies(MUTATE), is(false));
        assertThat(READ.satisfies(ADMIN), is(false));
    }

    @Test
    public void satisfiesNullIsFalse() {
        assertThat(ADMIN.satisfies(null), is(false));
    }

    @Test
    public void parseIsCaseInsensitiveAndTrimmed() {
        assertThat(ControlPlaneRole.parse("read"), is(READ));
        assertThat(ControlPlaneRole.parse(" MUTATE "), is(MUTATE));
        assertThat(ControlPlaneRole.parse("Admin"), is(ADMIN));
    }

    @Test
    public void parseUnknownOrBlankIsNull() {
        assertThat(ControlPlaneRole.parse(null), is(nullValue()));
        assertThat(ControlPlaneRole.parse(""), is(nullValue()));
        assertThat(ControlPlaneRole.parse("superuser"), is(nullValue()));
    }
}
