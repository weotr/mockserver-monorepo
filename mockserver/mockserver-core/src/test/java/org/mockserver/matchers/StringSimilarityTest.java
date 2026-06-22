package org.mockserver.matchers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class StringSimilarityTest {

    private static final double TOLERANCE = 0.001d;

    @Test
    public void shouldReturnOneForIdenticalStrings() {
        assertThat(StringSimilarity.jaroWinkler("hello", "hello"), is(1.0d));
        assertThat(StringSimilarity.jaro("hello", "hello"), is(1.0d));
    }

    @Test
    public void shouldReturnOneForTwoEmptyStrings() {
        assertThat(StringSimilarity.jaroWinkler("", ""), is(1.0d));
    }

    @Test
    public void shouldReturnOneForTwoNullStrings() {
        assertThat(StringSimilarity.jaroWinkler(null, null), is(1.0d));
    }

    @Test
    public void shouldReturnZeroWhenComparingEmptyWithNonEmpty() {
        assertThat(StringSimilarity.jaroWinkler("", "hello"), is(0.0d));
        assertThat(StringSimilarity.jaroWinkler("hello", ""), is(0.0d));
        assertThat(StringSimilarity.jaroWinkler(null, "hello"), is(0.0d));
    }

    @Test
    public void shouldReturnZeroForCompletelyDifferentStrings() {
        assertThat(StringSimilarity.jaroWinkler("abc", "xyz"), is(0.0d));
    }

    @Test
    public void shouldComputeKnownJaroValues() {
        // canonical reference values for the Jaro distance
        assertThat(StringSimilarity.jaro("MARTHA", "MARHTA"), is(closeTo(0.944d, TOLERANCE)));
        assertThat(StringSimilarity.jaro("DWAYNE", "DUANE"), is(closeTo(0.822d, TOLERANCE)));
        assertThat(StringSimilarity.jaro("DIXON", "DICKSONX"), is(closeTo(0.767d, TOLERANCE)));
    }

    @Test
    public void shouldComputeKnownJaroWinklerValues() {
        // canonical reference values for the Jaro-Winkler similarity
        assertThat(StringSimilarity.jaroWinkler("MARTHA", "MARHTA"), is(closeTo(0.961d, TOLERANCE)));
        assertThat(StringSimilarity.jaroWinkler("DWAYNE", "DUANE"), is(closeTo(0.840d, TOLERANCE)));
        assertThat(StringSimilarity.jaroWinkler("DIXON", "DICKSONX"), is(closeTo(0.813d, TOLERANCE)));
    }

    @Test
    public void shouldGiveHighSimilarityForSmallTypos() {
        assertThat(StringSimilarity.jaroWinkler("hello world", "hallo world") > 0.9d, is(true));
    }

    @Test
    public void shouldBeSymmetric() {
        double forward = StringSimilarity.jaroWinkler("kitten", "sitting");
        double backward = StringSimilarity.jaroWinkler("sitting", "kitten");
        assertThat(forward, is(backward));
    }

    @Test
    public void shouldBeDeterministicAcrossRepeatedCalls() {
        double first = StringSimilarity.jaroWinkler("the quick brown fox", "the quik brown fox");
        double second = StringSimilarity.jaroWinkler("the quick brown fox", "the quik brown fox");
        assertThat(first, is(second));
    }
}
