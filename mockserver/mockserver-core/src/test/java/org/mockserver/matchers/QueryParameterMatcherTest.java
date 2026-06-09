package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import static org.mockserver.model.NottableString.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class QueryParameterMatcherTest {

    @Test
    public void shouldMatchMatchingParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameter.*", "parameter.*")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingParameterWhenNotApplied() {
        // given
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));

        // then - not matcher
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // and - not parameter
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // and - not matcher and not parameter
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingParameterWithNotParameterAndNormalParameter() {
        // not matching parameter
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // not extra parameter (number of parameters don't match)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue"),
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // not only parameter
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));

        // not all parameters (but matching)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameter.*"), not(".*"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // not all parameters (but not matching name)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameter.*"), not("parameter.*"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("notParameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("notParameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        // not all parameters (but not matching value)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameter.*"), not("parameter.*"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "notParameterOneValueOne", "notParameterOneValueTwo"),
                new Parameter("parameterTwoName", "notParameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMatchingParameterWithOnlyParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterThree", "parameterThreeValueOne")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterOneName"), not("parameterOneValueOne"), not("parameterOneValueTwo"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingParameterWithOnlyParameterForEmptyList() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterThree", "parameterThreeValueOne")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterThree", "parameterThreeValueOne")
        ), true).matches(null, new Parameters()), is(false));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(null, new Parameters()), is(true));
    }

    @Test
    public void shouldNotMatchMatchingParameterWithNotParameterAndNormalParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMatchingParameterWithOnlyNotParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("notParameterTwoName", "parameterTwoValue")
            )
        ), is(true));
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterTwoName"), "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchMatchingParameterWithOnlyNotParameterForBodyWithSingleParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), null, true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), null, true))
            .matches(
                null,
                new Parameters().withEntries(
                    new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                    new Parameter("parameterTwoName", "parameterTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test

    public void shouldNotMatchEmptyExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true))
            .matches(
                null,
                new Parameters().withEntries(
                    new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                    new Parameter("parameterTwoName", "parameterTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectParameterName() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("INCORRECTparameterTwoName", "parameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterNameWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("INCORRECTparameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectParameterValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "INCORRECTparameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "INCORRECTparameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectParameterNameAndValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("INCORRECTparameterTwoName", "INCORRECTparameterTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterNameAndValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("INCORRECTparameterTwoName", "INCORRECTparameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullParameterValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullParameterValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullParameterValueInExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
                new Parameter("parameterTwoName", "parameterTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMissingParameter() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMissingParameterWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters().withEntries(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(
            null,
            new Parameters().withEntries(
                new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true).matches(null, null), is(true));
    }

    @Test
    public void shouldNotMatchNullTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true)).matches(null, null), is(false));
    }

    @Test
    public void shouldMatchEmptyTest() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true).matches(null, new Parameters()), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Parameters(), true)).matches(null, new Parameters()), is(false));
    }
}
