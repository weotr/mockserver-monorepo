package org.mockserver.matchers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.codec.MultipartFormDataDecoder;
import org.mockserver.codec.MultipartFormDataDecoder.DecodedMultipart;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.MultipartBody;
import org.mockserver.model.Parameters;

/**
 * Matches a {@code multipart/form-data} request body at the field level.
 * <p>
 * The body matcher decodes the actual request's multipart body into per-field
 * {@link Parameters} maps (values, filenames and part content types) and matches
 * each against the corresponding map declared on the {@link MultipartBody},
 * reusing {@link MultiValueMapMatcher} so the matching semantics are identical
 * to form-parameter matching (regular expressions, negation, sub-set keys).
 *
 * @author jamesdbloom
 */
public class MultipartMatcher extends BodyMatcher<MultipartMatcher.MultipartInput> {

    private static final String[] EXCLUDED_FIELDS = {"mockServerLogger", "decoder"};
    private final MultipartFormDataDecoder decoder = new MultipartFormDataDecoder();
    private final MultiValueMapMatcher fieldsMatcher;
    private final MultiValueMapMatcher filenamesMatcher;
    private final MultiValueMapMatcher partContentTypesMatcher;
    private final boolean blank;

    public MultipartMatcher(MockServerLogger mockServerLogger, MultipartBody multipartBody, boolean controlPlaneMatcher) {
        Parameters fields = multipartBody.getFields();
        Parameters filenames = multipartBody.getFilenames();
        Parameters partContentTypes = multipartBody.getPartContentTypes();
        this.fieldsMatcher = new MultiValueMapMatcher(mockServerLogger, fields, controlPlaneMatcher);
        this.filenamesMatcher = new MultiValueMapMatcher(mockServerLogger, filenames, controlPlaneMatcher);
        this.partContentTypesMatcher = new MultiValueMapMatcher(mockServerLogger, partContentTypes, controlPlaneMatcher);
        this.blank = (fields == null || fields.isEmpty())
            && (filenames == null || filenames.isEmpty())
            && (partContentTypes == null || partContentTypes.isEmpty());
    }

    public boolean matches(final MatchDifference context, MultipartInput matched) {
        boolean result;
        if (blank) {
            result = true;
        } else if (matched == null) {
            result = false;
        } else {
            DecodedMultipart decoded = decoder.decode(matched.contentTypeHeader, matched.body);
            if (decoded == null) {
                result = false;
            } else {
                result = fieldsMatcher.matches(context, decoded.getFields())
                    && filenamesMatcher.matches(context, decoded.getFilenames())
                    && partContentTypesMatcher.matches(context, decoded.getPartContentTypes());
            }
        }
        return not != result;
    }

    public boolean isBlank() {
        return blank;
    }

    @Override
    @JsonIgnore
    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }

    /**
     * The pair of values needed to decode an actual multipart request: the raw
     * body bytes and the request {@code Content-Type} header (carrying the boundary).
     */
    public static class MultipartInput {
        private final String contentTypeHeader;
        private final byte[] body;

        public MultipartInput(String contentTypeHeader, byte[] body) {
            this.contentTypeHeader = contentTypeHeader;
            this.body = body;
        }
    }
}
