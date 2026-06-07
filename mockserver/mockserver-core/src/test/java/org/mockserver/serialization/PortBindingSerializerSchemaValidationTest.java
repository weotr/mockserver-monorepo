package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.PortBinding;
import org.mockserver.version.Version;

import java.util.Arrays;

import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.PortBinding.portBinding;

/**
 * @author jamesdbloom
 */
public class PortBindingSerializerSchemaValidationTest {


    @Test
    public void shouldIgnoreExtraFields() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "    \"ports\": [" + NEW_LINE +
            "        0," + NEW_LINE +
            "        1090," + NEW_LINE +
            "        0" + NEW_LINE +
            "    ]," + NEW_LINE +
            "    \"extra_field\": \"extra_value\"" + NEW_LINE +
            "}";

        // when
        PortBinding portBinding = new PortBindingSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertEquals(portBinding(0, 1090, 0), portBinding);
    }

    @Test
    public void shouldDeserializeCompleteObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "    \"ports\": [" + NEW_LINE +
            "        0," + NEW_LINE +
            "        1090," + NEW_LINE +
            "        0" + NEW_LINE +
            "    ]" + NEW_LINE +
            "}";

        // when
        PortBinding portBinding = new PortBindingSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertEquals(portBinding(0, 1090, 0), portBinding);
    }

    @Test
    public void shouldDeserializePartialObject() {
        // given
        String requestBytes = "{ }";

        // when
        PortBinding portBinding = new PortBindingSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertEquals(portBinding(), portBinding);
    }

    @Test
    public void shouldSerializeCompleteObject() {
        // when
        String jsonPortBinding = new PortBindingSerializer(new MockServerLogger()).serialize(
            new PortBinding().setPorts(Arrays.asList(0, 1090, 0))
        );

        // then
        assertThat(jsonPortBinding, containsString("\"ports\" : [ 0, 1090, 0 ]"));
        assertThat(jsonPortBinding, containsString("\"artifactId\" : \"mockserver-core\""));
        assertThat(jsonPortBinding, containsString("\"groupId\" : \"org.mock-server\""));
        assertThat(jsonPortBinding, containsString("\"version\" : "));
        // gitHash is populated at build time and is build-environment dependent: present for git
        // checkouts (the abbreviated commit hash), omitted when no git metadata is available.
        String gitHash = Version.getGitHash();
        if (gitHash != null && !gitHash.isEmpty()) {
            assertThat(jsonPortBinding, containsString("\"gitHash\" : \"" + gitHash + "\""));
        } else {
            assertThat(jsonPortBinding, not(containsString("\"gitHash\"")));
        }
    }
}
