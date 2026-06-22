package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class ScimFilterTest {

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;

    private ObjectNode user(String userName, String displayName) {
        ObjectNode user = NODE_FACTORY.objectNode();
        user.put("userName", userName);
        if (displayName != null) {
            user.put("displayName", displayName);
        }
        return user;
    }

    private List<ObjectNode> users() {
        return Arrays.asList(
            user("bjensen@example.com", "Barbara"),
            user("jsmith@example.com", null),
            user("alice@other.com", "Alice")
        );
    }

    @Test
    public void blankFilterReturnsNull() {
        assertNull(ScimFilter.parse(null));
        assertNull(ScimFilter.parse("   "));
    }

    @Test
    public void eqMatchesExactlyOneCaseInsensitively() {
        ScimFilter filter = ScimFilter.parse("userName eq \"BJENSEN@EXAMPLE.COM\"");
        assertThat(filter.apply(users()), contains(users().get(0)));
    }

    @Test
    public void coMatchesSubstring() {
        ScimFilter filter = ScimFilter.parse("userName co \"example.com\"");
        List<ObjectNode> result = filter.apply(users());
        assertThat(result, contains(users().get(0), users().get(1)));
    }

    @Test
    public void swMatchesPrefix() {
        ScimFilter filter = ScimFilter.parse("userName sw \"alice\"");
        assertThat(filter.apply(users()), contains(users().get(2)));
    }

    @Test
    public void prMatchesPresentAttribute() {
        ScimFilter filter = ScimFilter.parse("displayName pr");
        assertThat(filter.apply(users()), contains(users().get(0), users().get(2)));
    }

    @Test
    public void unknownAttributeMatchesNothing() {
        ScimFilter filter = ScimFilter.parse("nickName eq \"x\"");
        assertThat(filter.apply(users()), is(empty()));
    }

    @Test
    public void quotedValueWithSpacesParses() {
        ScimFilter filter = ScimFilter.parse("displayName eq \"Barbara\"");
        assertThat(filter.getValue(), is("Barbara"));
        assertThat(filter.getAttribute(), is("displayName"));
        assertThat(filter.getOperator(), is("eq"));
    }

    @Test
    public void malformedFilterThrows() {
        assertThrows(IllegalArgumentException.class, () -> ScimFilter.parse("userName <> \"x\""));
        assertThrows(IllegalArgumentException.class, () -> ScimFilter.parse("userName and displayName pr"));
    }
}
