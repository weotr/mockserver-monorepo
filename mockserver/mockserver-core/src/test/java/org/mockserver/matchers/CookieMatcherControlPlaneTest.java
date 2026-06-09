package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Cookies;

import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class CookieMatcherControlPlaneTest {

    @Test
    public void shouldMatchSingleCookieMatcherAndSingleMatchingCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchSingleCookieMatcherAndSingleNoneMatchingCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "cookieOneValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "notCookieOneValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMultipleCookieMatcherAndMultipleMatchingCookies() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchRegexCookieMatcher() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookie.*", "cookie.*")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shoulMatchMatchingRegexControlPlaneCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookie.*", "cookie.*"),
                new Cookie("cook.*", "cook.*")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMultipleCookieMatcherAndMultipleNoneMatchingCookiesWithOneMismatch() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "notCookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchMultipleCookieMatcherAndMultipleNoneMatchingCookiesWithMultipleMismatches() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "cookieOneValue"),
                new Cookie("notCookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "notCookieOneValue"),
                new Cookie("cookieTwoName", "notCookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookie.*", "cookie.*")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "cookieOneValue"),
                new Cookie("notCookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookie.*", "cookie.*")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "notCookieOneValue"),
                new Cookie("cookieTwoName", "notCookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchMultipleCookieMatcherAndMultipleNotEnoughMatchingCookies() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMatchingCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookie.*", "cookie.*")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingCookieWhenNotAppliedToMatcher() {
        // given
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));

        // then - not matcher
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // and - not cookie
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie(not("cookie.*Name"), not("cookie.*Value"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // and - multiple not cookies
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieOneName"), not("cookieOneValue")),
            new Cookie(not("cookie.*Name"), not("cookie.*Value"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // and - not matcher and not cookie
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie(not("cookie.*Name"), not("cookie.*Value"))
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingCookieWithNotCookieAndNormalCookie() {
        // not matching cookie
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie(not("cookie.*Name"), not("cookie.*Value"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // not cookie
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie(not("cookie.*Name"), not("cookie.*Value"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // not single cookie
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieThreeName"), not("cookieThreeValue"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));

        // not multiple cookies
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieOneName"), not("cookieOneValue")),
            new Cookie(not("cookieTwoName"), not("cookieTwoValue"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "notCookieOneValue"),
                new Cookie("notCookieTwoName", "notCookieTwoValue")
            )
        ), is(true));

        // not all cookies (but not matching name and value)
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookie.*"), not(".*"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // not all cookies (but not matching name)
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookie.*"), not("cookie.*"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "cookieOneValue"),
                new Cookie("notCookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        // not all cookies (but not matching value)
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(string("cookie.*"), not("cookie.*"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "notCookieOneValue"),
                new Cookie("cookieTwoName", "notCookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingCookieWithOnlyCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieThreeName"), not("cookieThreeValue"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieThree", "cookieThreeValueOne")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieOneName"), not("cookieOneValue"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("notCookieOneName", "notCookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieOneName"), not("cookieOneValue"))
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue")
            )
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchMatchingCookieWithOnlyCookieForEmptyList() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies(), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieThree", "cookieThreeValueOne")
            )
        ), is(true));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieThree", "cookieThreeValueOne")
        ), true).matches(null,
            new Cookies()
        ), is(false));

        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieThree"), not("cookieThreeValueOne"))
        ), true).matches(null,
            new Cookies()
        ), is(true));
    }

    @Test
    public void shouldNotMatchMatchingCookieWithNotCookieAndNormalCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie(not("cookieTwoName"), not("cookieTwoValue"))),
            true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchMatchingCookieWithOnlyNotCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookie.*"), not("cookie.*"))),
            true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchMatchingCookieWithOnlyNotCookieForBodyWithSingleCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie(not("cookieTwoName"), not("cookieTwoValue"))),
            true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullExpectation() {
        assertThat(new HashMapMatcher(new MockServerLogger(), null, true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), null, true))
            .matches(null,
                new Cookies().withEntries(
                    new Cookie("cookieOneName", "cookieOneValue"),
                    new Cookie("cookieTwoName", "cookieTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldMatchEmptyExpectation() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies(), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchEmptyExpectationWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies(), true))
            .matches(null,
                new Cookies().withEntries(
                    new Cookie("cookieOneName", "cookieOneValue"),
                    new Cookie("cookieTwoName", "cookieTwoValue")
                )
            ), is(false));
    }

    @Test
    public void shouldNotMatchIncorrectCookieName() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("INCORRECTcookieTwoName", "cookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectCookieNameWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("INCORRECTcookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectCookieValue() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "INCORRECTcookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectCookieValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "INCORRECTcookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchIncorrectCookieNameAndValue() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("INCORRECTcookieTwoName", "INCORRECTcookieTwoValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchIncorrectCookieNameAndValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("INCORRECTcookieTwoName", "INCORRECTcookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullCookieValue() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", null)
            )
        ), is(false));
    }

    @Test
    public void shouldMatchNullCookieValueWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", null)
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullCookieValueInExpectation() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue"),
                new Cookie("cookieTwoName", "cookieTwoValue")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchMissingCookie() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchMissingCookieWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies().withEntries(
            new Cookie("cookieOneName", "cookieOneValue"),
            new Cookie("cookieTwoName", "cookieTwoValue")
        ), true)).matches(null,
            new Cookies().withEntries(
                new Cookie("cookieOneName", "cookieOneValue")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies(), true).matches(null,
            new Cookies()
        ), is(true));
    }

    @Test
    public void shouldNotMatchNullTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies(), true)).matches(null,
            new Cookies()
        ), is(false));
    }

    @Test
    public void shouldMatchEmptyTest() {
        assertThat(new HashMapMatcher(new MockServerLogger(), new Cookies(), true).matches(null,
            new Cookies()
        ), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTestWhenNotApplied() {
        assertThat(NotMatcher.notMatcher(new HashMapMatcher(new MockServerLogger(), new Cookies(), true)).matches(null,
            new Cookies()
        ), is(false));
    }

}
