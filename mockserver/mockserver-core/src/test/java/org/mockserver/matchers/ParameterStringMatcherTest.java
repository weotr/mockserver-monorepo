package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class ParameterStringMatcherTest {

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));

        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameter.*", "parameter.*")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchMatchingStringWhenNotApplied() {
        // given
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));

        // then - not matcher
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        // and - not parameter
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        // and - multiple not parameters
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterOneName"), not("parameterOneValueOne"), not("parameterOneValueTwo")),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(null, "" +
            "notParameterOneName=parameterOneValueOne" +
            "&notParameterOneName=parameterOneValueTwo" +
            "&notParameterTwoName=parameterTwoValue"), is(true));
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterOneName"), not("parameterOneValueOne"), not("parameterOneValueTwo")),
            new Parameter(not("parameterTwoName"), "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        // and - not parameter
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true)).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldMatchMatchingStringWithNotParameterAndNormalParameter() {
        // not matching parameter
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        // not extra parameter
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));

        // not only parameter
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));

        // not only parameter
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterOne"), not("parameterOneValueOne")),
            new Parameter(not("parameterTwo"), not("parameterTwoValueOne"))
        ), true).matches(null, "" +
            "notParameterOne=notParameterOneValueOne" +
            "&notParameterTwo=notParameterTwoValueOne"), is(true));

        // not all parameters (but matching)
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameter.*"), not(".*"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        // not all parameters (but not matching name)
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameter.*"), not("parameter.*"))
        ), true).matches(null, "" +
            "notParameterOneName=parameterOneValueOne" +
            "&notParameterOneName=parameterOneValueTwo" +
            "&notParameterTwoName=parameterTwoValue"), is(false));

        // not all parameters (but not matching value)
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameter.*"), not("parameter.*"))
        ), true).matches(null, "" +
            "parameterOneName=notParameterOneValueOne" +
            "&parameterOneName=notParameterOneValueTwo" +
            "&parameterTwoName=notParameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchMatchingStringWithOnlyParameter() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterThree", "parameterThreeValueOne")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));

        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterOneName"), not("parameterOneValueOne"), not("parameterOneValueTwo"))
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldMatchMatchingStringWithOnlyParameterForEmptyBody() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(),
            new Parameters(),
            true).matches(null, "parameterThree=parameterThreeValueOne"), is(true));

        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterThree", "parameterThreeValueOne")
        ), true).matches(null, ""), is(false));

        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterThree"), not("parameterThreeValueOne"))
        ), true).matches(null, ""), is(true));
    }

    @Test
    public void shouldNotMatchMatchingStringWithNotParameterAndNormalParameter() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchMatchingStringWithOnlyNotParameter() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&notParameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchMatchingStringWithOnlyNotParameterForBodyWithSingleParameter() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter(not("parameterTwoName"), not("parameterTwoValue"))), true).matches(null, "" +
            "parameterTwoName=parameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), null, true).matches(null, "some_value"), is(true));
    }

    @Test
    public void shouldNotMatchNullExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), null, true)).matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true).matches(null, "some_value"), is(true));
    }

    @Test
    public void shouldNotMatchEmptyExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true)).matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectParameterName() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&INCORRECTParameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterNameWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&INCORRECTParameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectParameterValue() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=INCORRECTParameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&parameterTwoName=INCORRECTParameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectParameterNameAndValue() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&INCORRECTParameterTwoName=INCORRECTParameterTwoValue"), is(false));
    }

    @Test
    public void shouldMatchIncorrectParameterNameAndValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterOneValueOne", "parameterOneValueTwo"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterOneValueOne" +
            "&parameterOneName=parameterOneValueTwo" +
            "&INCORRECTParameterTwoName=INCORRECTParameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchNullParameterValue() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), false).matches(null, "" +
            "parameterOneName=parameterValueOne" +
            "&parameterTwoName="), is(false));
    }

    @Test
    public void shouldNotMatchNullParameterValueForControlPlane() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterValueOne" +
            "&parameterTwoName="), is(false));
    }

    @Test
    public void shouldMatchNullParameterValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterValueOne" +
            "&parameterTwoName="), is(true));
    }

    @Test
    public void shouldMatchNullParameterValueInExpectation() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "")
        ), true).matches(null, "" +
            "parameterOneName=parameterValueOne" +
            "&parameterTwoName=parameterTwoValue"), is(true));
    }

    @Test
    public void shouldNotMatchMissingParameter() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true).matches(null, "" +
            "parameterOneName=parameterValueOne"), is(false));
    }

    @Test
    public void shouldMatchMissingParameterWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(
            new Parameter("parameterOneName", "parameterValueOne"),
            new Parameter("parameterTwoName", "parameterTwoValue")
        ), true)).matches(null, "" +
            "parameterOneName=parameterValueOne"), is(true));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true).matches(null, null), is(true));
    }

    @Test
    public void shouldNotMatchNullTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true)).matches(null, null), is(false));
    }

    @Test
    public void shouldMatchEmptyTest() {
        assertThat(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true).matches(null, ""), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new ParameterStringMatcher(configuration(), new MockServerLogger(), new Parameters(), true)).matches(null, ""), is(false));
    }
}
