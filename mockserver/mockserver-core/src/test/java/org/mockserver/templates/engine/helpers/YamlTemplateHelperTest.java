package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class YamlTemplateHelperTest {

    private final YamlTemplateHelper helper = new YamlTemplateHelper();

    @Test
    public void shouldConvertYamlToJson() {
        String yaml = "name: Bob\nage: 30\n";
        assertThat(helper.toJson(yaml), is("{\"name\":\"Bob\",\"age\":30}"));
    }

    @Test
    public void shouldConvertNestedYamlToJson() {
        String yaml = "person:\n  name: Bob\n  roles:\n    - admin\n    - user\n";
        assertThat(helper.toJson(yaml), is("{\"person\":{\"name\":\"Bob\",\"roles\":[\"admin\",\"user\"]}}"));
    }

    @Test
    public void shouldConvertNullOrEmptyToEmpty() {
        assertThat(helper.toJson(null), is(""));
        assertThat(helper.toJson(""), is(""));
    }

    @Test
    public void shouldParseScalarField() {
        String yaml = "name: Bob\nage: 30\n";
        assertThat(helper.parse(yaml, "name"), is("Bob"));
        assertThat(helper.parse(yaml, "age"), is("30"));
    }

    @Test
    public void shouldParseObjectFieldAsJson() {
        String yaml = "person:\n  name: Bob\n";
        assertThat(helper.parse(yaml, "person"), is("{\"name\":\"Bob\"}"));
    }

    @Test
    public void shouldParseMissingFieldAsEmpty() {
        assertThat(helper.parse("name: Bob\n", "missing"), is(""));
        assertThat(helper.parse(null, "name"), is(""));
        assertThat(helper.parse("name: Bob\n", null), is(""));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object yamlHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("yaml");
        assertThat(yamlHelper, is(notNullValue()));
        assertThat(yamlHelper, instanceOf(YamlTemplateHelper.class));
    }
}
