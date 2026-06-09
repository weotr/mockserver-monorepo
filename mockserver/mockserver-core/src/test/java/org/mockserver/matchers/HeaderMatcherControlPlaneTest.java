package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;

import static org.mockserver.model.NottableString.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class HeaderMatcherControlPlaneTest {

    @Test
    public void shouldMatchMatchingHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("header.*", "header.*")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingRegexHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOne.*", "headerOne.*", "headerOneValueTwo"),
            new Header("headerT.*Name", "headerT.*Value")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("header.*", "header.*")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingHeaderWhenNotApplied() {
        // given
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));

        // then - not matcher
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        // and - not header
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        // and - multiple not headers
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerOneName"), not("headerOneValue")),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("notHeaderOneName", "headerOneValue"),
                new Header("notHeaderTwoName", "headerTwoValue")
            )
        ), is(true));
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerOneName"), "headerOneValue"),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValue"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        // and - not matcher and not header
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingHeaderWithNotHeaderAndNormalHeader() {
        // not matching header
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        // not single header
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerThree"), not("headerThreeValueOne"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));

        // not multiple headers
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerOneName"), not("headerOneValue")),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("notHeaderOneName", "notHeaderOneValue"),
                new Header("notHeaderTwoName", "notHeaderTwoValue")
            )
        ), is(true));

        // not all headers (but matching)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("header.*"), not(".*"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        // not all headers (but not matching name)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("header.*"), not("header.*"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("notHeaderOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("notHeaderTwoName", "headerTwoValue")
            )
        ), is(false));

        // not all headers (but not matching value)
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("header.*"), not("header.*"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "notHeaderOneValueOne", "notHeaderOneValueTwo"),
                new Header("headerTwoName", "notHeaderTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMatchingHeaderWithOnlyHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerThree"), not("headerThreeValueOne"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerThree", "headerThreeValueOne")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerOneName"), not("headerOneValueOne"), not("headerOneValueTwo"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingHeaderWithOnlyHeaderForEmptyList() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerThree", "headerThreeValueOne")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerThree", "headerThreeValueOne")
        ), true).matches(
            null,
            new Headers()
        ), is(false));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerThree"), not("headerThreeValueOne"))
        ), true).matches(
            null,
            new Headers()
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingRegexControlPlaneHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOne.*", "headerOne.*", "headerOneValueTwo"),
                new Header("headerT.*Name", "headerT.*Value")
            )
        ), is(true));

        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("header.*", "header.*", "head.*"),
                new Header("head.*", "head.*")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingHeaderWithNotHeaderAndNormalHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMatchingHeaderWithOnlyNotHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("notHeaderTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingHeaderWithOnlyNotHeaderForBodyWithSingleHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header(not("headerTwoName"), not("headerTwoValue"))
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), null, true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), null, true))
            .matches(
                null,
                new Headers().withEntries(
                    new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                    new Header("headerTwoName", "headerTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchEmptyExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true))
            .matches(
                null,
                new Headers().withEntries(
                    new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                    new Header("headerTwoName", "headerTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectHeaderName() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("INCORRECTheaderTwoName", "headerTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectHeaderNameWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("INCORRECTheaderTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectHeaderValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "INCORRECTheaderTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectHeaderValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "INCORRECTheaderTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectHeaderNameAndValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("INCORRECTheaderTwoName", "INCORRECTheaderTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectHeaderNameAndValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("INCORRECTheaderTwoName", "INCORRECTheaderTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullHeaderValue() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullHeaderValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullHeaderValueInExpectation() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
                new Header("headerTwoName", "headerTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMissingHeader() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMissingHeaderWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers().withEntries(
            new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo"),
            new Header("headerTwoName", "headerTwoValue")
        ), true)).matches(
            null,
            new Headers().withEntries(
                new Header("headerOneName", "headerOneValueOne", "headerOneValueTwo")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true).matches(
            null,
            new Headers()
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true)).matches(
            null,
            new Headers()
        ), is(false));
    }

    @Test
    public void shouldMatchEmptyTest() {
        assertThat(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true).matches(
            null,
            new Headers()
        ), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new MultiValueMapMatcher(new MockServerLogger(), new Headers(), true)).matches(
            null,
            new Headers()
        ), is(false));
    }

}
