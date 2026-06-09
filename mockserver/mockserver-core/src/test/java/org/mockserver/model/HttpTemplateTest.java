package org.mockserver.model;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpTemplate.TemplateType.JAVASCRIPT;
import static org.mockserver.model.HttpTemplate.TemplateType.VELOCITY;
import static org.mockserver.model.HttpTemplate.template;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.hamcrest.CoreMatchers.not;
/**
 * @author jamesdbloom
 */
public class HttpTemplateTest {

    @Test
    @SuppressWarnings("AccessStaticViaInstance")
    public void shouldAlwaysCreateNewObject() {
        assertThat(template(JAVASCRIPT), is(template(JAVASCRIPT)));
        assertThat(template(VELOCITY), is(template(VELOCITY)));
        assertThat(template(JAVASCRIPT), not(sameInstance(template(JAVASCRIPT))));
        assertThat(template(VELOCITY), not(sameInstance(template(VELOCITY))));
    }

    @Test
    public void returnsTemplate() {
        assertThat(new HttpTemplate(JAVASCRIPT).withTemplate("some_template").getTemplate(), is("some_template"));
    }

    @Test
    public void returnsTemplateType() {
        assertThat(new HttpTemplate(JAVASCRIPT).getTemplateType(), is(JAVASCRIPT));
    }

    @Test
    public void returnsDelay() {
        assertThat(new HttpForward().withDelay(new Delay(TimeUnit.HOURS, 1)).getDelay(), is(new Delay(TimeUnit.HOURS, 1)));
        assertThat(new HttpForward().withDelay(TimeUnit.HOURS, 1).getDelay(), is(new Delay(TimeUnit.HOURS, 1)));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertThat(template(JAVASCRIPT)
                .withTemplate("some_template")
                .withDelay(TimeUnit.HOURS, 1)
                .toString(), is("{" + NEW_LINE +
                "  \"delay\" : {" + NEW_LINE +
                "    \"timeUnit\" : \"HOURS\"," + NEW_LINE +
                "    \"value\" : 1" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"template\" : \"some_template\"," + NEW_LINE +
                "  \"templateType\" : \"JAVASCRIPT\"" + NEW_LINE +
                "}"));
    }

}
