package org.mockserver.templates.engine.helpers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regular expression helpers for templates: test for a match, replace all
 * matches, and extract a capture group from the first match.
 */
public class RegexTemplateHelper {

    /**
     * Returns {@code true} if the pattern is found anywhere within the input.
     */
    public boolean matches(String input, String pattern) {
        if (input == null || pattern == null) {
            return false;
        }
        return Pattern.compile(pattern).matcher(input).find();
    }

    /**
     * Replaces every match of the pattern in the input with the replacement.
     */
    public String replaceAll(String input, String pattern, String replacement) {
        if (input == null || pattern == null) {
            return input != null ? input : "";
        }
        return Pattern.compile(pattern).matcher(input).replaceAll(replacement != null ? replacement : "");
    }

    /**
     * Returns the given capture group from the first match of the pattern in the
     * input, or an empty string if there is no match or the group is absent.
     * Group {@code 0} is the entire match.
     */
    public String group(String input, String pattern, int group) {
        if (input == null || pattern == null || group < 0) {
            return "";
        }
        Matcher matcher = Pattern.compile(pattern).matcher(input);
        if (matcher.find() && group <= matcher.groupCount()) {
            String result = matcher.group(group);
            return result != null ? result : "";
        }
        return "";
    }

    @Override
    public String toString() {
        return "RegexTemplateHelper";
    }
}
