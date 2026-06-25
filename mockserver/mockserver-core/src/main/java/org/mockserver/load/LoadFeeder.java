package org.mockserver.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.model.ObjectWithJsonToString;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.templates.engine.helpers.CsvTemplateHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameterized test data ("data feeder") for a {@link LoadScenario}. Per iteration, the orchestrator
 * selects one row from the feeder's dataset and exposes it to that iteration's templated request
 * fields (path, body and headers) via {@link IterationContext#getData()} — referenced as
 * {@code $iteration.data.<column>} (Velocity) / {@code {{iteration.data.<column>}}} (Mustache),
 * exactly the way {@link LoadCapture cross-step captures} are referenced through
 * {@code $iteration.captured.<name>}.
 *
 * <p><b>Dataset, two equivalent forms (source of truth):</b>
 * <ul>
 *   <li>{@link #getRows() rows} — an inline list of column-to-value maps (the PRIMARY mechanism;
 *       JSON-native, round-trips byte-for-byte, no parsing, no I/O surface).</li>
 *   <li>{@link #getData() data} + {@link #getFormat() format} — an OPTIONAL raw inline dataset
 *       ({@link Format#CSV CSV} or {@link Format#JSON JSON}) parsed into rows on demand. The raw
 *       text is the stored source of truth: it is serialized back verbatim (the derived rows are
 *       NOT re-serialized), so a {@code data}/{@code format} feeder round-trips without double-parsing.
 *       CSV parsing reuses {@link CsvTemplateHelper} (RFC 4180-ish: handles embedded commas, doubled
 *       quotes and newlines; first line = headers). JSON parsing expects an array of objects.</li>
 * </ul>
 * Exactly one form should be supplied. When both are present {@code rows} wins (the inline rows are
 * authoritative and {@code data} is ignored).
 *
 * <p><b>External sources are intentionally out of scope</b> for this version: a feeder cannot fetch
 * from a URL or read an arbitrary file path (that would add an SSRF / arbitrary-file-read surface to a
 * self-load feature). The dataset is always inline in the scenario body. Loading from an external
 * source is a possible future enhancement.
 *
 * <p><b>Selection strategy</b> ({@link Strategy}):
 * <ul>
 *   <li>{@link Strategy#CIRCULAR CIRCULAR} (default) — {@code rows[globalIteration % size]}; never
 *       exhausts, so sustained load cycles the dataset.</li>
 *   <li>{@link Strategy#RANDOM RANDOM} — a uniformly random row each iteration.</li>
 *   <li>{@link Strategy#SEQUENTIAL SEQUENTIAL} — {@code rows[globalIteration]} used exactly once each,
 *       in order; the run COMPLETES once the dataset is exhausted (data-driven "replay this dataset
 *       once").</li>
 * </ul>
 *
 * @see IterationContext#getData()
 * @see LoadScenario#getFeeder()
 */
public class LoadFeeder extends ObjectWithJsonToString {

    /** The raw-dataset format of {@link #getData()}. */
    public enum Format {
        /** Comma-separated; first line is the header row. Parsed via {@link CsvTemplateHelper}. */
        CSV,
        /** A JSON array of flat objects, e.g. {@code [{"user":"a"},{"user":"b"}]}. */
        JSON
    }

    /** How a row is chosen for each iteration from the resolved dataset. */
    public enum Strategy {
        /** {@code rows[globalIteration % size]} — cycles forever (default). */
        CIRCULAR,
        /** A uniformly random row each iteration. */
        RANDOM,
        /** {@code rows[globalIteration]} once each, in order; the run completes when exhausted. */
        SEQUENTIAL
    }

    private List<Map<String, String>> rows;
    private String data;
    private Format format;
    private Strategy strategy = Strategy.CIRCULAR;

    /** Lazily-parsed cache of the rows derived from {@link #data}/{@link #format}, never re-serialized. */
    private transient volatile List<Map<String, String>> parsedRows;

    public static LoadFeeder loadFeeder() {
        return new LoadFeeder();
    }

    /** Convenience: an inline-rows feeder with the default CIRCULAR strategy. */
    public static LoadFeeder loadFeeder(List<Map<String, String>> rows) {
        return new LoadFeeder().withRows(rows);
    }

    /**
     * The inline dataset (may be null when {@link #getData()}/{@link #getFormat()} is used instead).
     * The PRIMARY mechanism: each entry is one row of column-name to value.
     */
    public List<Map<String, String>> getRows() {
        return rows;
    }

    public LoadFeeder withRows(List<Map<String, String>> rows) {
        this.rows = rows;
        this.parsedRows = null;
        return this;
    }

    public LoadFeeder withRow(Map<String, String> row) {
        if (this.rows == null) {
            this.rows = new ArrayList<>();
        }
        this.rows.add(row);
        this.parsedRows = null;
        return this;
    }

    /**
     * The raw inline dataset parsed per {@link #getFormat()} (may be null when {@link #getRows()} is
     * used). The stored source of truth — serialized back verbatim, never re-derived from rows.
     */
    public String getData() {
        return data;
    }

    public LoadFeeder withData(String data) {
        this.data = data;
        this.parsedRows = null;
        return this;
    }

    /** The format of {@link #getData()} (may be null when {@link #getRows()} is used). */
    public Format getFormat() {
        return format;
    }

    public LoadFeeder withFormat(Format format) {
        this.format = format;
        this.parsedRows = null;
        return this;
    }

    /** The selection strategy (defaults to {@link Strategy#CIRCULAR}). */
    public Strategy getStrategy() {
        return strategy;
    }

    public LoadFeeder withStrategy(Strategy strategy) {
        this.strategy = strategy;
        return this;
    }

    /**
     * The effective dataset: the inline {@link #getRows() rows} when present, otherwise the rows parsed
     * from {@link #getData() data}/{@link #getFormat() format} (parsed once and cached). Never null —
     * returns an empty list when neither form yields any row. Each returned map is unmodifiable.
     *
     * @throws IllegalArgumentException if {@code data} is set but malformed for its {@code format}, or
     *                                  {@code data} is set without a {@code format}
     */
    public List<Map<String, String>> resolvedRows() {
        if (rows != null) {
            // Wrap each inline row unmodifiable so the per-iteration map exposed to templates honours the
            // read-only contract uniformly with the parsed CSV/JSON paths (the row list is shared across
            // all iterations/VUs, so a mutable map would leak across iterations).
            List<Map<String, String>> wrapped = new java.util.ArrayList<>(rows.size());
            for (Map<String, String> row : rows) {
                wrapped.add(row != null ? Collections.unmodifiableMap(row) : Collections.emptyMap());
            }
            return wrapped;
        }
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, String>> cached = parsedRows;
        if (cached == null) {
            cached = parseData();
            parsedRows = cached;
        }
        return cached;
    }

    private List<Map<String, String>> parseData() {
        if (format == null) {
            throw new IllegalArgumentException("'feeder.format' is required when 'feeder.data' is set (CSV or JSON)");
        }
        switch (format) {
            case CSV:
                return parseCsv(data);
            case JSON:
                return parseJson(data);
            default:
                throw new IllegalArgumentException("unsupported feeder format " + format);
        }
    }

    /**
     * Parse CSV (first line = header row) into a list of column-to-value maps, reusing the project's
     * {@link CsvTemplateHelper} (RFC 4180-ish: embedded commas, doubled quotes and newlines handled).
     * A row with fewer fields than the header leaves the missing columns unset; extra fields beyond the
     * header are ignored.
     */
    private static List<Map<String, String>> parseCsv(String csv) {
        List<List<String>> records = new CsvTemplateHelper().parse(csv);
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> headers = records.get(0);
        List<Map<String, String>> result = new ArrayList<>(records.size() - 1);
        for (int r = 1; r < records.size(); r++) {
            List<String> fields = records.get(r);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < fields.size(); c++) {
                row.put(headers.get(c), fields.get(c));
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return result;
    }

    /** Parse a JSON array of flat objects via the standard project mapper; scalar values are stringified. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseJson(String json) {
        ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
        List<Map<String, Object>> raw;
        try {
            raw = mapper.readValue(json, mapper.getTypeFactory()
                .constructCollectionType(List.class, mapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class)));
        } catch (Exception e) {
            throw new IllegalArgumentException("'feeder.data' is not a valid JSON array of objects: " + e.getMessage(), e);
        }
        if (raw == null) {
            return Collections.emptyList();
        }
        List<Map<String, String>> result = new ArrayList<>(raw.size());
        for (Map<String, Object> object : raw) {
            Map<String, String> row = new LinkedHashMap<>();
            if (object != null) {
                for (Map.Entry<String, Object> entry : object.entrySet()) {
                    row.put(entry.getKey(), entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                }
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return result;
    }
}
