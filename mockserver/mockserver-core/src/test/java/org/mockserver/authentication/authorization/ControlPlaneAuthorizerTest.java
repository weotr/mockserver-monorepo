package org.mockserver.authentication.authorization;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.authentication.authorization.ControlPlaneRole.ADMIN;
import static org.mockserver.authentication.authorization.ControlPlaneRole.MUTATE;
import static org.mockserver.authentication.authorization.ControlPlaneRole.READ;

/**
 * Unit tests for mapping verified scopes/groups to coarse roles and the per-operation
 * read/mutate authorization decision.
 */
public class ControlPlaneAuthorizerTest {

    private static Map<String, ControlPlaneRole> mapping() {
        Map<String, ControlPlaneRole> map = new LinkedHashMap<>();
        map.put("platform-admins", ADMIN);
        map.put("qa-team", MUTATE);
        map.put("viewers", READ);
        return map;
    }

    private final ControlPlaneAuthorizer authorizer = new ControlPlaneAuthorizer(mapping());

    @Test
    public void requiredRoleIsReadForReadsAndMutateForMutations() {
        assertThat(authorizer.requiredRole(true), is(READ));
        assertThat(authorizer.requiredRole(false), is(MUTATE));
    }

    @Test
    public void mapsScopesToGrantedRoles() {
        assertThat(authorizer.grantedRoles(Set.of("viewers")), contains(READ));
        assertThat(authorizer.grantedRoles(Set.of("unmapped-scope")), is(empty()));
    }

    @Test
    public void adminPassesEveryOperation() {
        assertThat(authorizer.isAuthorized(Set.of("platform-admins"), true), is(true));
        assertThat(authorizer.isAuthorized(Set.of("platform-admins"), false), is(true));
    }

    @Test
    public void mutatePassesReadAndMutate() {
        assertThat(authorizer.isAuthorized(Set.of("qa-team"), true), is(true));
        assertThat(authorizer.isAuthorized(Set.of("qa-team"), false), is(true));
    }

    @Test
    public void readOnlyPassesReadButNotMutate() {
        assertThat(authorizer.isAuthorized(Set.of("viewers"), true), is(true));
        assertThat(authorizer.isAuthorized(Set.of("viewers"), false), is(false));
    }

    @Test
    public void noMappedScopeIsDeniedEverythingFailClosed() {
        assertThat(authorizer.isAuthorized(Set.of("random"), true), is(false));
        assertThat(authorizer.isAuthorized(Set.of("random"), false), is(false));
        assertThat(authorizer.isAuthorized(Set.of(), false), is(false));
        assertThat(authorizer.isAuthorized(null, false), is(false));
    }

    @Test
    public void emptyMappingGrantsNothing() {
        ControlPlaneAuthorizer empty = new ControlPlaneAuthorizer(null);
        assertThat(empty.isAuthorized(Set.of("viewers"), true), is(false));
        assertThat(empty.grantedRoles(Set.of("viewers")), is(empty()));
    }

    @Test
    public void highestGrantedRoleWinsAcrossMultipleScopes() {
        // a principal holding both a read and an admin scope is authorized to mutate
        assertThat(authorizer.isAuthorized(Set.of("viewers", "platform-admins"), false), is(true));
    }
}
