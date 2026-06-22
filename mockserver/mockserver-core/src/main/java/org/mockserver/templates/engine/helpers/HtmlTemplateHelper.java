package org.mockserver.templates.engine.helpers;

import org.apache.commons.text.StringEscapeUtils;

/**
 * HTML entity escaping helpers for templates: escape a string so it can be
 * safely embedded in HTML, and reverse the operation. Backed by Apache
 * commons-text (already on the classpath), so the full HTML4 entity set is
 * supported, with {@code & < > "} and named/numeric entities handled.
 */
public class HtmlTemplateHelper {

    /**
     * Escapes the characters in the input using HTML entities (HTML 4.0).
     * Returns an empty string for {@code null} input.
     */
    public String escape(String value) {
        if (value == null) {
            return "";
        }
        return StringEscapeUtils.escapeHtml4(value);
    }

    /**
     * Unescapes a string containing HTML 4.0 entity escapes back to the
     * original characters. Returns an empty string for {@code null} input.
     */
    public String unescape(String value) {
        if (value == null) {
            return "";
        }
        return StringEscapeUtils.unescapeHtml4(value);
    }

    @Override
    public String toString() {
        return "HtmlTemplateHelper";
    }
}
