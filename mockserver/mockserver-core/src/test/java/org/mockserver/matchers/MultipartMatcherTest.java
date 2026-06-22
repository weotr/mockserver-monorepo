package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.MultipartBody;
import org.mockserver.model.Parameter;
import org.mockserver.model.Parameters;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.NottableString.not;

/**
 * Behavioural tests for {@link MultipartMatcher} — multipart/form-data
 * field-level matching.
 * <p>
 * Each test builds a real {@code multipart/form-data} payload (boundary +
 * parts) and asserts the observable match outcome, mirroring the form
 * parameter matching UX (field name to value/filename patterns, with
 * regular expressions and negation).
 */
public class MultipartMatcherTest {

    private static final String BOUNDARY = "----MockServerBoundary7d91b2";
    private static final String CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;

    /**
     * Builds a payload with one text field ("description") and one file part
     * ("file") whose filename and part content-type are set.
     */
    private MultipartMatcher.MultipartInput textAndFileRequest(String description, String filename, String fileContentType, String fileBytes) {
        String body =
            "--" + BOUNDARY + "\r\n" +
                "Content-Disposition: form-data; name=\"description\"\r\n" +
                "\r\n" +
                description + "\r\n" +
                "--" + BOUNDARY + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
                "Content-Type: " + fileContentType + "\r\n" +
                "\r\n" +
                fileBytes + "\r\n" +
                "--" + BOUNDARY + "--\r\n";
        return new MultipartMatcher.MultipartInput(CONTENT_TYPE, body.getBytes(StandardCharsets.UTF_8));
    }

    private MultipartMatcher matcher(MultipartBody multipartBody) {
        return new MultipartMatcher(new MockServerLogger(), multipartBody, false);
    }

    @Test
    public void shouldMatchOnTextFieldValue() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an important upload")
        ));

        assertThat(matcher.matches(null, textAndFileRequest("an important upload", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldNotMatchOnDifferentTextFieldValue() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an important upload")
        ));

        assertThat(matcher.matches(null, textAndFileRequest("something else entirely", "report.pdf", "application/pdf", "PDF-BYTES")), is(false));
    }

    @Test
    public void shouldMatchTextFieldValueByRegex() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an .* upload")
        ));

        assertThat(matcher.matches(null, textAndFileRequest("an important upload", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldMatchOnFilenamePattern() {
        MultipartMatcher matcher = matcher(new MultipartBody(new Parameter("file"))
            .withFilenames(new Parameter("file", ".*\\.pdf")));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldNotMatchWhenFilenameDiffers() {
        MultipartMatcher matcher = matcher(new MultipartBody(new Parameter("file"))
            .withFilenames(new Parameter("file", ".*\\.pdf")));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.png", "image/png", "PNG-BYTES")), is(false));
    }

    @Test
    public void shouldMatchOnPartContentType() {
        MultipartMatcher matcher = matcher(new MultipartBody(new Parameter("file"))
            .withPartContentTypes(new Parameter("file", "application/pdf")));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldMatchOnPartPresence() {
        // requiring the "file" field with any value asserts the part is present
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("file", ".*")
        ));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldNotMatchWhenRequiredPartAbsent() {
        // request only contains "description" and "file" — require an absent "avatar" field
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("avatar", ".*")
        ));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(false));
    }

    @Test
    public void shouldMatchWhenFieldRequiredAbsentUsingNottedName() {
        // negated key asserts the "avatar" part is NOT present (it isn't), so this matches
        MultipartMatcher matcher = matcher(new MultipartBody(new Parameters(
            new Parameter(not("avatar"), ".*")
        )));

        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldMatchOnMultipleFieldsTogether() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an important upload"),
            new Parameter("file", ".*"))
            .withFilenames(new Parameter("file", ".*\\.pdf")));

        assertThat(matcher.matches(null, textAndFileRequest("an important upload", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldNotMatchWhenOneOfMultipleFieldsFails() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an important upload"),
            new Parameter("file", ".*"))
            .withFilenames(new Parameter("file", ".*\\.pdf")));

        // filename is .png so the filenames matcher fails even though the text field matches
        assertThat(matcher.matches(null, textAndFileRequest("an important upload", "report.png", "image/png", "PNG-BYTES")), is(false));
    }

    @Test
    public void shouldMatchAnyMultipartBodyWhenMatcherBlank() {
        // backward compatibility: an empty multipart matcher matches any multipart body
        MultipartMatcher matcher = matcher(new MultipartBody(new Parameters()));

        assertThat(matcher.isBlank(), is(true));
        assertThat(matcher.matches(null, textAndFileRequest("desc", "report.pdf", "application/pdf", "PDF-BYTES")), is(true));
    }

    @Test
    public void shouldNotMatchNonMultipartBody() {
        MultipartMatcher matcher = matcher(new MultipartBody(
            new Parameter("description", "an important upload")
        ));

        assertThat(matcher.matches(null, new MultipartMatcher.MultipartInput("application/json", "{\"description\":\"an important upload\"}".getBytes(StandardCharsets.UTF_8))), is(false));
    }
}
