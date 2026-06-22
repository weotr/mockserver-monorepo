package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.FuzzyBody.fuzzy;

/**
 * @author jamesdbloom
 */
public class FuzzyBodyTest {

    @Test
    public void shouldReturnValuesSetInConstructor() {
        // when
        FuzzyBody fuzzyBody = new FuzzyBody("some_body");

        // then
        assertThat(fuzzyBody.getValue(), is("some_body"));
        assertThat(fuzzyBody.getType(), is(Body.Type.FUZZY));
        assertThat(fuzzyBody.getThreshold(), is(FuzzyBody.DEFAULT_THRESHOLD));
        assertThat(fuzzyBody.isIgnoreCase(), is(false));
    }

    @Test
    public void shouldReturnValuesFromStaticBuilder() {
        // when
        FuzzyBody fuzzyBody = fuzzy("some_body", 0.6d, true);

        // then
        assertThat(fuzzyBody.getValue(), is("some_body"));
        assertThat(fuzzyBody.getType(), is(Body.Type.FUZZY));
        assertThat(fuzzyBody.getThreshold(), is(0.6d));
        assertThat(fuzzyBody.isIgnoreCase(), is(true));
    }

    @Test
    public void shouldClampThresholdAboveOne() {
        assertThat(new FuzzyBody("x", 5.0d).getThreshold(), is(1.0d));
    }

    @Test
    public void shouldClampThresholdBelowZero() {
        assertThat(new FuzzyBody("x", -1.0d).getThreshold(), is(0.0d));
    }

    @Test
    public void shouldBeEqualWhenAllFieldsMatch() {
        assertThat(new FuzzyBody("x", 0.7d, true), is(new FuzzyBody("x", 0.7d, true)));
    }

    @Test
    public void shouldNotBeEqualWhenThresholdDiffers() {
        assertThat(new FuzzyBody("x", 0.7d).equals(new FuzzyBody("x", 0.8d)), is(false));
    }
}
