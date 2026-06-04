package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.Delay;

import java.util.concurrent.TimeUnit;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class DelayToJavaSerializerTest {

    @Test
    public void shouldSerializeFullObjectWithForwardAsJava() {
        assertThat(
            new DelayToJavaSerializer().serialize(1,
                new Delay(TimeUnit.SECONDS, 10)
            )
        , is("new Delay(TimeUnit.SECONDS, 10)"));
    }

    @Test
    public void shouldSerializeUniformDistributionAsJava() {
        assertThat(
            new DelayToJavaSerializer().serialize(1,
                Delay.uniform(TimeUnit.MILLISECONDS, 100, 500)
            )
        , is("Delay.uniform(TimeUnit.MILLISECONDS, 100, 500)"));
    }

    @Test
    public void shouldSerializeLogNormalDistributionAsJava() {
        assertThat(
            new DelayToJavaSerializer().serialize(1,
                Delay.logNormal(TimeUnit.MILLISECONDS, 200, 800)
            )
        , is("Delay.logNormal(TimeUnit.MILLISECONDS, 200, 800)"));
    }

    @Test
    public void shouldSerializeGaussianDistributionAsJava() {
        assertThat(
            new DelayToJavaSerializer().serialize(1,
                Delay.gaussian(TimeUnit.MILLISECONDS, 200, 50)
            )
        , is("Delay.gaussian(TimeUnit.MILLISECONDS, 200, 50)"));
    }

    @Test
    public void shouldSerializeNullDelay() {
        assertThat(
            new DelayToJavaSerializer().serialize(1, null)
        , is(""));
    }
}
