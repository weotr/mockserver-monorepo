package org.mockserver.mock;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

public class CassetteRegistryTest {

    @Test
    public void shouldRegisterAndListCassette() {
        CassetteRegistry registry = new CassetteRegistry(() -> 1000L);

        CassetteRegistry.Entry entry = registry.register("/tmp/a.json", "a.json", 3, "loaded");

        assertThat(entry.path, is("/tmp/a.json"));
        assertThat(entry.filename, is("a.json"));
        assertThat(entry.expectationCount, is(3));
        assertThat(entry.origin, is("loaded"));
        assertThat(entry.lastUsedEpochMillis, is(1000L));
        assertThat(registry.list().size(), is(1));
    }

    @Test
    public void shouldDeriveFilenameFromPathWhenBlank() {
        CassetteRegistry registry = new CassetteRegistry(() -> 0L);

        assertThat(registry.register("/some/dir/cassette.json", null, 1, "recorded").filename, is("cassette.json"));
        assertThat(registry.register("/some/dir/other.json", "  ", 1, "recorded").filename, is("other.json"));
    }

    @Test
    public void shouldCoerceUnknownOriginToLoaded() {
        CassetteRegistry registry = new CassetteRegistry(() -> 0L);

        assertThat(registry.register("/tmp/a.json", "a.json", 1, "weird").origin, is("loaded"));
        assertThat(registry.register("/tmp/b.json", "b.json", 1, "recorded").origin, is("recorded"));
    }

    @Test
    public void shouldUpdateInPlaceForSamePathRatherThanDuplicate() {
        AtomicLong now = new AtomicLong(1000L);
        CassetteRegistry registry = new CassetteRegistry(now::get);

        registry.register("/tmp/a.json", "a.json", 1, "loaded");
        now.set(2000L);
        CassetteRegistry.Entry updated = registry.register("/tmp/a.json", "a.json", 9, "recorded");

        assertThat(registry.list().size(), is(1));
        assertThat(updated.expectationCount, is(9));
        assertThat(updated.origin, is("recorded"));
        assertThat(updated.lastUsedEpochMillis, is(2000L));
    }

    @Test
    public void shouldListMostRecentlyUsedFirst() {
        AtomicLong now = new AtomicLong(1000L);
        CassetteRegistry registry = new CassetteRegistry(now::get);

        registry.register("/tmp/old.json", "old.json", 1, "loaded");
        now.set(5000L);
        registry.register("/tmp/new.json", "new.json", 1, "loaded");

        List<CassetteRegistry.Entry> list = registry.list();
        assertThat(list.stream().map(e -> e.filename).collect(java.util.stream.Collectors.toList()),
            contains("new.json", "old.json"));
    }

    @Test
    public void shouldIgnoreBlankPath() {
        CassetteRegistry registry = new CassetteRegistry(() -> 0L);

        assertThat(registry.register(null, "x", 1, "loaded"), is((CassetteRegistry.Entry) null));
        assertThat(registry.register("   ", "x", 1, "loaded"), is((CassetteRegistry.Entry) null));
        assertThat(registry.list().size(), is(0));
    }

    @Test
    public void shouldRemoveByPathAndReportWhetherPresent() {
        CassetteRegistry registry = new CassetteRegistry(() -> 0L);
        registry.register("/tmp/a.json", "a.json", 1, "loaded");

        assertThat(registry.remove("/tmp/a.json"), is(true));
        assertThat(registry.remove("/tmp/a.json"), is(false));
        assertThat(registry.list().size(), is(0));
    }

    @Test
    public void shouldClearOnReset() {
        CassetteRegistry registry = new CassetteRegistry(() -> 0L);
        registry.register("/tmp/a.json", "a.json", 1, "loaded");
        registry.register("/tmp/b.json", "b.json", 1, "loaded");

        registry.reset();

        assertThat(registry.list().size(), is(0));
    }
}
