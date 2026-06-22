package org.mockserver.scim;

import org.junit.Test;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;

public class ScimCallbackSupportTest {

    private ScimResourceStore.Provider provider(boolean requireBearerToken, String expectedBearerToken) {
        return new ScimResourceStore.Provider(
            "/scim/v2",
            new CrudDataStore("id", IdStrategy.UUID),
            new CrudDataStore("id", IdStrategy.UUID),
            requireBearerToken, expectedBearerToken, true, true);
    }

    private HttpRequest requestWithBearer(String token) {
        HttpRequest request = request("/scim/v2/Users");
        if (token != null) {
            request.withHeader("Authorization", "Bearer " + token);
        }
        return request;
    }

    @Test
    public void rejectsWhenEnforcementOnAndExpectedTokenBlankAndAnyTokenPresented() {
        // FAIL CLOSED: enforcement enabled, but no expected token configured => reject any token
        ScimResourceStore.Provider provider = provider(true, "");
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer("any-token"), provider);
        assertThat(response, notNullValue());
        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void rejectsWhenEnforcementOnAndExpectedTokenNullAndAnyTokenPresented() {
        // FAIL CLOSED: enforcement enabled, but expected token is null => reject any token
        ScimResourceStore.Provider provider = provider(true, null);
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer("any-token"), provider);
        assertThat(response, notNullValue());
        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void rejectsWhenEnforcementOnAndConfiguredTokenAndWrongTokenPresented() {
        ScimResourceStore.Provider provider = provider(true, "secret-token");
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer("wrong-token"), provider);
        assertThat(response, notNullValue());
        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void rejectsWhenEnforcementOnAndNoAuthorizationHeader() {
        ScimResourceStore.Provider provider = provider(true, "secret-token");
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer(null), provider);
        assertThat(response, notNullValue());
        assertThat(response.getStatusCode(), is(401));
    }

    @Test
    public void acceptsWhenEnforcementOnAndConfiguredTokenAndCorrectTokenPresented() {
        ScimResourceStore.Provider provider = provider(true, "secret-token");
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer("secret-token"), provider);
        assertThat(response, is(nullValue()));
    }

    @Test
    public void acceptsWhenEnforcementOff() {
        // enforcement disabled => allowed regardless of token
        ScimResourceStore.Provider provider = provider(false, null);
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer(null), provider);
        assertThat(response, is(nullValue()));
    }

    @Test
    public void acceptsWhenProviderNull() {
        // null provider (e.g. discovery endpoints) => allowed
        HttpResponse response = ScimCallbackSupport.bearerGate(requestWithBearer("any-token"), null);
        assertThat(response, is(nullValue()));
    }
}
