package org.mockserver.load;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Pure unit tests for {@link LoadFeeder} dataset resolution: inline rows, CSV/JSON parsing of the raw
 * {@code data} form, and the malformed-input error paths. No global state — runs in the parallel phase.
 */
public class LoadFeederTest {

    private static Map<String, String> row(String k1, String v1, String k2, String v2) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put(k1, v1);
        row.put(k2, v2);
        return row;
    }

    @Test
    public void inlineRowsResolveDirectly() {
        LoadFeeder feeder = LoadFeeder.loadFeeder(asList(row("user", "a", "id", "1"), row("user", "b", "id", "2")));
        List<Map<String, String>> rows = feeder.resolvedRows();
        assertThat(rows, hasSize(2));
        assertThat(rows.get(0).get("user"), is("a"));
        assertThat(rows.get(1).get("id"), is("2"));
    }

    @Test
    public void defaultStrategyIsCircular() {
        assertThat(new LoadFeeder().getStrategy(), is(LoadFeeder.Strategy.CIRCULAR));
    }

    @Test
    public void absentDatasetResolvesToEmpty() {
        assertThat(new LoadFeeder().resolvedRows(), is(empty()));
    }

    @Test
    public void csvDataParsesIntoRows() {
        LoadFeeder feeder = new LoadFeeder()
            .withFormat(LoadFeeder.Format.CSV)
            .withData("user,id\nalice,1\nbob,2");
        List<Map<String, String>> rows = feeder.resolvedRows();
        assertThat(rows, hasSize(2));
        assertThat(rows.get(0).get("user"), is("alice"));
        assertThat(rows.get(0).get("id"), is("1"));
        assertThat(rows.get(1).get("user"), is("bob"));
        assertThat(rows.get(1).get("id"), is("2"));
    }

    @Test
    public void csvDataHandlesQuotedEmbeddedCommas() {
        LoadFeeder feeder = new LoadFeeder()
            .withFormat(LoadFeeder.Format.CSV)
            .withData("name,city\n\"Smith, John\",\"London\"");
        List<Map<String, String>> rows = feeder.resolvedRows();
        assertThat(rows, hasSize(1));
        assertThat(rows.get(0).get("name"), is("Smith, John"));
        assertThat(rows.get(0).get("city"), is("London"));
    }

    @Test
    public void jsonDataParsesArrayOfObjects() {
        LoadFeeder feeder = new LoadFeeder()
            .withFormat(LoadFeeder.Format.JSON)
            .withData("[{\"user\":\"alice\",\"id\":1},{\"user\":\"bob\",\"id\":2}]");
        List<Map<String, String>> rows = feeder.resolvedRows();
        assertThat(rows, hasSize(2));
        assertThat(rows.get(0).get("user"), is("alice"));
        // Scalar numbers are stringified.
        assertThat(rows.get(0).get("id"), is("1"));
        assertThat(rows.get(1).get("user"), is("bob"));
    }

    @Test
    public void resolvedRowsAreCachedAndStable() {
        LoadFeeder feeder = new LoadFeeder().withFormat(LoadFeeder.Format.CSV).withData("k\nv");
        assertThat(feeder.resolvedRows(), sameInstance(feeder.resolvedRows()));
    }

    @Test
    public void inlineRowsWinOverData() {
        LoadFeeder feeder = new LoadFeeder()
            .withRows(asList(row("user", "inline", "id", "9")))
            .withFormat(LoadFeeder.Format.CSV)
            .withData("user,id\nfromcsv,1");
        assertThat(feeder.resolvedRows(), hasSize(1));
        assertThat(feeder.resolvedRows().get(0).get("user"), is("inline"));
    }

    @Test
    public void malformedJsonThrowsClearError() {
        LoadFeeder feeder = new LoadFeeder().withFormat(LoadFeeder.Format.JSON).withData("{not an array");
        IllegalArgumentException e = org.junit.Assert.assertThrows(IllegalArgumentException.class, feeder::resolvedRows);
        assertThat(e.getMessage(), containsString("not a valid JSON array"));
    }

    @Test
    public void dataWithoutFormatThrowsClearError() {
        LoadFeeder feeder = new LoadFeeder().withData("a,b\n1,2");
        IllegalArgumentException e = org.junit.Assert.assertThrows(IllegalArgumentException.class, feeder::resolvedRows);
        assertThat(e.getMessage(), containsString("'feeder.format' is required"));
    }
}
