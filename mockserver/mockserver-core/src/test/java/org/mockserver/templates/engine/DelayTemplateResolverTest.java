package org.mockserver.templates.engine;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpTemplate;
import org.slf4j.event.Level;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class DelayTemplateResolverTest {

    private final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private DelayTemplateResolver resolver;

    @Before
    public void setUp() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        resolver = new DelayTemplateResolver(mockServerLogger, configuration);
    }

    @Test
    public void shouldReturnNullDelayUnchanged() {
        assertThat(resolver.resolve(null, request()), is(nullValue()));
    }

    @Test
    public void shouldReturnStaticDelayUnchanged() {
        // given
        Delay staticDelay = new Delay(TimeUnit.SECONDS, 3);

        // when
        Delay resolved = resolver.resolve(staticDelay, request().withBody("anything"));

        // then - identical object, no template resolution applied
        assertThat(resolved, is(staticDelay));
        assertThat(resolved.sampleValueMillis(), is(3000L));
    }

    @Test
    public void shouldReturnDistributionDelayUnchanged() {
        // given
        Delay distributionDelay = Delay.uniform(TimeUnit.MILLISECONDS, 100, 200);

        // when
        Delay resolved = resolver.resolve(distributionDelay, request().withBody("anything"));

        // then
        assertThat(resolved, is(distributionDelay));
    }

    @Test
    public void shouldComputeDelayFromRequestBodyLengthWithVelocity() {
        // given - 10ms per request body character
        Delay templateDelay = Delay.template(HttpTemplate.TemplateType.VELOCITY, "#set($d = $request.body.length() * 10)$d");

        // when
        Delay resolved = resolver.resolve(templateDelay, request().withBody("12345"));

        // then
        assertThat(resolved.hasTemplate(), is(false));
        assertThat(resolved.getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(resolved.sampleValueMillis(), is(50L));
    }

    @Test
    public void shouldComputeDelayWithMustache() {
        // given
        Delay templateDelay = Delay.template(HttpTemplate.TemplateType.MUSTACHE, "250");

        // when
        Delay resolved = resolver.resolve(templateDelay, request().withBody("ignored"));

        // then
        assertThat(resolved.sampleValueMillis(), is(250L));
    }

    @Test
    public void shouldTruncateDecimalRenderedValue() {
        // given
        Delay templateDelay = Delay.template(HttpTemplate.TemplateType.MUSTACHE, "250.9");

        // when
        Delay resolved = resolver.resolve(templateDelay, request());

        // then - truncated towards zero
        assertThat(resolved.sampleValueMillis(), is(250L));
    }

    @Test
    public void shouldFallBackToStaticValueWhenTemplateRendersNonNumeric() {
        // given - template renders non-numeric text, static fallback is 7ms
        Delay templateDelay = new Delay(TimeUnit.MILLISECONDS, 7, null, "not-a-number", HttpTemplate.TemplateType.MUSTACHE);

        // when
        Delay resolved = resolver.resolve(templateDelay, request());

        // then - falls back to static value, template stripped
        assertThat(resolved.hasTemplate(), is(false));
        assertThat(resolved.sampleValueMillis(), is(7L));
    }

    @Test
    public void shouldFallBackToZeroWhenTemplateRendersBlankAndNoStaticValue() {
        // given - blank render, no static fallback
        Delay templateDelay = Delay.template(HttpTemplate.TemplateType.MUSTACHE, "   ");

        // when
        Delay resolved = resolver.resolve(templateDelay, request());

        // then
        assertThat(resolved.sampleValueMillis(), is(0L));
    }

    @Test
    public void shouldFallBackToStaticValueWhenTemplateRendersNegative() {
        // given - negative is rejected, static fallback is 5ms
        Delay templateDelay = new Delay(TimeUnit.MILLISECONDS, 5, null, "-100", HttpTemplate.TemplateType.MUSTACHE);

        // when
        Delay resolved = resolver.resolve(templateDelay, request());

        // then
        assertThat(resolved.sampleValueMillis(), is(5L));
    }

    @Test
    public void shouldFallBackWhenTemplateTypeIsJavaScript() {
        // given - JavaScript is not supported for delay templating; static fallback is 9ms
        Delay templateDelay = new Delay(TimeUnit.MILLISECONDS, 9, null, "42", HttpTemplate.TemplateType.JAVASCRIPT);

        // when
        Delay resolved = resolver.resolve(templateDelay, request());

        // then
        assertThat(resolved.sampleValueMillis(), is(9L));
    }

    @Test
    public void shouldFallBackWhenRequestIsNull() {
        // given
        Delay templateDelay = new Delay(TimeUnit.MILLISECONDS, 11, null, "42", HttpTemplate.TemplateType.MUSTACHE);

        // when - no request to render against
        Delay resolved = resolver.resolve(templateDelay, null);

        // then - returned unchanged (still a template delay, applied later as static)
        assertThat(resolved, is(templateDelay));
    }
}
