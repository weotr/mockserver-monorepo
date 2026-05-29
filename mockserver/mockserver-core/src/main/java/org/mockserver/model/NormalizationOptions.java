package org.mockserver.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Opt-in normalisation applied to LLM prompt text before
 * {@link ConversationPredicates} substring/regex comparisons are evaluated.
 * <p>
 * Agent prompts are dynamically assembled, so exact-byte matching is brittle.
 * Normalisation makes matching tolerant of cosmetic differences (whitespace,
 * JSON key ordering, volatile ids/timestamps) while remaining fully
 * deterministic — the same input always normalises to the same output.
 * <p>
 * Boolean options are nullable on purpose: an unset (null) flag means "use the
 * default", and that default is applied uniformly wherever the options are
 * consumed (see {@link org.mockserver.llm.PromptNormalizer}). This keeps the
 * behaviour identical whether the options arrive via the REST/JSON API (Jackson
 * leaves absent fields null) or the MCP tool (which sets only the fields present
 * in the request). Defaults: {@code collapseWhitespace} and {@code sortJsonKeys}
 * on; {@code lowercase} and {@code dropBuiltInVolatileFields} off.
 * <p>
 * Follows the same field/{@code withX}/getter convention as the other model
 * classes ({@link Completion}, {@link ConversationPredicates}) so it round-trips
 * through the MockServer JSON API without a bespoke (de)serializer.
 */
public class NormalizationOptions extends ObjectWithJsonToString {

    public static final boolean DEFAULT_COLLAPSE_WHITESPACE = true;
    public static final boolean DEFAULT_LOWERCASE = false;
    public static final boolean DEFAULT_SORT_JSON_KEYS = true;
    public static final boolean DEFAULT_DROP_BUILT_IN_VOLATILE_FIELDS = false;

    private int hashCode;
    private Boolean collapseWhitespace;
    private Boolean lowercase;
    private Boolean sortJsonKeys;
    private Boolean dropBuiltInVolatileFields;
    private List<String> dropVolatileFields;

    /**
     * Returns an options object with all flags unset — i.e. the defaults
     * (whitespace collapsing and JSON key sorting on, lowercasing and
     * volatile-field stripping off) apply when it is consumed.
     */
    public static NormalizationOptions normalizationOptions() {
        return new NormalizationOptions();
    }

    public NormalizationOptions withCollapseWhitespace(boolean collapseWhitespace) {
        this.collapseWhitespace = collapseWhitespace;
        this.hashCode = 0;
        return this;
    }

    public Boolean getCollapseWhitespace() {
        return collapseWhitespace;
    }

    public NormalizationOptions withLowercase(boolean lowercase) {
        this.lowercase = lowercase;
        this.hashCode = 0;
        return this;
    }

    public Boolean getLowercase() {
        return lowercase;
    }

    public NormalizationOptions withSortJsonKeys(boolean sortJsonKeys) {
        this.sortJsonKeys = sortJsonKeys;
        this.hashCode = 0;
        return this;
    }

    public Boolean getSortJsonKeys() {
        return sortJsonKeys;
    }

    /**
     * When true, strip a built-in set of volatile values from the text before
     * matching: ISO-8601 timestamps, UUIDs, and {@code prefix_…} style ids
     * (e.g. {@code req_…}, {@code msg_…}, {@code call_…}).
     */
    public NormalizationOptions withDropBuiltInVolatileFields(boolean dropBuiltInVolatileFields) {
        this.dropBuiltInVolatileFields = dropBuiltInVolatileFields;
        this.hashCode = 0;
        return this;
    }

    public Boolean getDropBuiltInVolatileFields() {
        return dropBuiltInVolatileFields;
    }

    /**
     * Names of JSON fields to drop from the prompt before matching (applied when
     * the prompt text is valid JSON). Use for caller-specific volatile fields not
     * covered by {@link #withDropBuiltInVolatileFields(boolean)}.
     */
    public NormalizationOptions withDropVolatileFields(List<String> dropVolatileFields) {
        this.dropVolatileFields = dropVolatileFields == null ? null : new ArrayList<>(dropVolatileFields);
        this.hashCode = 0;
        return this;
    }

    public List<String> getDropVolatileFields() {
        return dropVolatileFields;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        NormalizationOptions that = (NormalizationOptions) o;
        return Objects.equals(collapseWhitespace, that.collapseWhitespace) &&
            Objects.equals(lowercase, that.lowercase) &&
            Objects.equals(sortJsonKeys, that.sortJsonKeys) &&
            Objects.equals(dropBuiltInVolatileFields, that.dropBuiltInVolatileFields) &&
            Objects.equals(dropVolatileFields, that.dropVolatileFields);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(collapseWhitespace, lowercase, sortJsonKeys, dropBuiltInVolatileFields, dropVolatileFields);
        }
        return hashCode;
    }
}
