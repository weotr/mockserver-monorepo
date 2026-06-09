package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class ScenarioManagerCompositeKeyTest {

    private ScenarioManager scenarioManager;

    @Before
    public void setUp() {
        scenarioManager = new ScenarioManager();
    }

    // --- Backward compatibility: null isolation matches legacy behaviour ---

    @Test
    public void shouldReturnStartedForNullIsolation() {
        assertThat(scenarioManager.getState("myScenario", null), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldSetAndGetStateWithNullIsolation() {
        scenarioManager.setState("myScenario", null, "Step1");
        assertThat(scenarioManager.getState("myScenario", null), is("Step1"));
    }

    @Test
    public void shouldMatchStateWithNullIsolation() {
        assertThat(scenarioManager.matchesState("myScenario", null, "Started"), is(true));
    }

    @Test
    public void shouldMatchAndTransitionWithNullIsolation() {
        assertThat(scenarioManager.matchesAndTransition("myScenario", null, "Started", "Step1"), is(true));
        assertThat(scenarioManager.getState("myScenario", null), is("Step1"));
    }

    @Test
    public void shouldLegacySingleArgDelegateToComposite() {
        scenarioManager.setState("myScenario", "Step1");
        assertThat(scenarioManager.getState("myScenario"), is("Step1"));
        assertThat(scenarioManager.getState("myScenario", null), is("Step1"));
    }

    // --- Composite-key isolation ---

    @Test
    public void shouldIsolateSameScenarioWithDifferentIsolationValues() {
        // given
        scenarioManager.setState("conv1", "session-A", "turn_1");
        scenarioManager.setState("conv1", "session-B", "turn_2");

        // then
        assertThat(scenarioManager.getState("conv1", "session-A"), is("turn_1"));
        assertThat(scenarioManager.getState("conv1", "session-B"), is("turn_2"));
    }

    @Test
    public void shouldNotLeakStateBetweenIsolationValues() {
        scenarioManager.setState("conv1", "agent-1", "Step1");

        assertThat(scenarioManager.getState("conv1", "agent-2"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldMatchAndTransitionIndependentlyPerIsolation() {
        // First isolation succeeds
        assertThat(scenarioManager.matchesAndTransition("conv1", "s1", "Started", "turn_1"), is(true));
        assertThat(scenarioManager.getState("conv1", "s1"), is("turn_1"));

        // Second isolation is independent - still in Started state
        assertThat(scenarioManager.matchesAndTransition("conv1", "s2", "Started", "turn_1"), is(true));
        assertThat(scenarioManager.getState("conv1", "s2"), is("turn_1"));

        // First isolation can advance to turn_2
        assertThat(scenarioManager.matchesAndTransition("conv1", "s1", "turn_1", "turn_2"), is(true));
        assertThat(scenarioManager.getState("conv1", "s1"), is("turn_2"));

        // Second isolation is still at turn_1
        assertThat(scenarioManager.getState("conv1", "s2"), is("turn_1"));
    }

    @Test
    public void shouldMatchAndTransitionFailWhenStateMismatch() {
        scenarioManager.setState("conv1", "s1", "turn_1");

        assertThat(scenarioManager.matchesAndTransition("conv1", "s1", "Started", "turn_1"), is(false));
        assertThat(scenarioManager.getState("conv1", "s1"), is("turn_1"));
    }

    // --- clear(scenarioName) clears ALL isolation variants ---

    @Test
    public void shouldClearAllIsolationVariants() {
        scenarioManager.setState("conv1", null, "turn_1");
        scenarioManager.setState("conv1", "s1", "turn_2");
        scenarioManager.setState("conv1", "s2", "turn_3");

        scenarioManager.clear("conv1");

        assertThat(scenarioManager.getState("conv1", null), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv1", "s1"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv1", "s2"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldNotClearOtherScenarios() {
        scenarioManager.setState("conv1", "s1", "turn_1");
        scenarioManager.setState("conv2", "s1", "turn_2");

        scenarioManager.clear("conv1");

        assertThat(scenarioManager.getState("conv1", "s1"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv2", "s1"), is("turn_2"));
    }

    // --- reset() clears all composite state ---

    @Test
    public void shouldResetAllCompositeState() {
        scenarioManager.setState("conv1", "s1", "turn_1");
        scenarioManager.setState("conv2", "s2", "turn_2");
        scenarioManager.setState("conv3", null, "turn_3");

        scenarioManager.reset();

        assertThat(scenarioManager.getState("conv1", "s1"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv2", "s2"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv3", null), is(ScenarioManager.STARTED));
    }

    // --- getAllStates returns composite keys ---

    @Test
    public void shouldReturnAllStatesIncludingComposite() {
        scenarioManager.setState("conv1", null, "turn_1");
        scenarioManager.setState("conv1", "s1", "turn_2");

        Map<String, String> states = scenarioManager.getAllStates();

        assertThat(states.get("conv1"), is("turn_1"));
        assertThat(states.get("conv1[s1]"), is("turn_2"));
    }

    @Test
    public void shouldReturnAllStatesStructured() {
        scenarioManager.setState("conv1", null, "turn_1");
        scenarioManager.setState("conv1", "s1", "turn_2");

        Map<ScenarioManager.ScenarioKey, String> structured = scenarioManager.getAllStatesStructured();

        assertThat(structured.size(), is(2));
        // Verify programmatic access via key fields
        for (Map.Entry<ScenarioManager.ScenarioKey, String> entry : structured.entrySet()) {
            if (entry.getKey().getIsolation() == null) {
                assertThat(entry.getKey().getScenarioName(), is("conv1"));
                assertThat(entry.getValue(), is("turn_1"));
            } else {
                assertThat(entry.getKey().getScenarioName(), is("conv1"));
                assertThat(entry.getKey().getIsolation(), is("s1"));
                assertThat(entry.getValue(), is("turn_2"));
            }
        }
    }

    // --- Concurrent stress test ---

    @Test
    public void shouldHandleConcurrentMultiKeyTransitions() throws Exception {
        int keyCount = 50;
        int transitionsPerKey = 100;
        int threadCount = keyCount;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        Thread[] threads = new Thread[threadCount];

        for (int k = 0; k < keyCount; k++) {
            final String isolation = "session-" + k;
            threads[k] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int t = 0; t < transitionsPerKey; t++) {
                        String expectedState = t == 0 ? ScenarioManager.STARTED : "state_" + (t - 1);
                        String newState = "state_" + t;
                        boolean matched = scenarioManager.matchesAndTransition("stressTest", isolation, expectedState, newState);
                        if (!matched) {
                            errors.incrementAndGet();
                            return;
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            threads[k].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat("No errors during concurrent transitions", errors.get(), is(0));

        // Verify each isolation key reached its final state
        for (int k = 0; k < keyCount; k++) {
            assertThat(scenarioManager.getState("stressTest", "session-" + k),
                is("state_" + (transitionsPerKey - 1)));
        }
    }

    // --- Concurrent clear + matchesAndTransition stress test ---

    @Test
    public void shouldHandleConcurrentClearAndTransitions() throws Exception {
        int iterations = 200;
        int writerCount = 4;
        int clearerCount = 2;
        int totalThreads = writerCount + clearerCount;

        CyclicBarrier barrier = new CyclicBarrier(totalThreads);
        AtomicInteger errors = new AtomicInteger(0);
        Thread[] threads = new Thread[totalThreads];

        // Writers: continuously transition a scenario under various isolation keys
        for (int w = 0; w < writerCount; w++) {
            final String isolation = "writer-" + w;
            threads[w] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        // Try to transition; may fail if clear resets us, that's OK
                        String current = scenarioManager.getState("clearTest", isolation);
                        String next = "state_" + i;
                        scenarioManager.matchesAndTransition("clearTest", isolation, current, next);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            threads[w].start();
        }

        // Clearers: continuously clear the scenario
        for (int c = 0; c < clearerCount; c++) {
            threads[writerCount + c] = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        scenarioManager.clear("clearTest");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            threads[writerCount + c].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // No ConcurrentModificationException or other errors
        assertThat("No errors during concurrent clear + transitions", errors.get(), is(0));
    }

    // --- Key encoding round-trip and edge cases ---

    @Test
    public void shouldRoundTripKeyWithBrackets() {
        // Name containing brackets should not collide with isolation encoding
        ScenarioManager.ScenarioKey key = new ScenarioManager.ScenarioKey("test[1]", null);
        String encoded = key.toString();
        ScenarioManager.ScenarioKey parsed = ScenarioManager.parseKey(encoded);
        assertThat(parsed.getScenarioName(), is("test[1]"));
        assertThat(parsed.getIsolation(), is((String) null));
    }

    @Test
    public void shouldRoundTripKeyWithBracketsAndIsolation() {
        ScenarioManager.ScenarioKey key = new ScenarioManager.ScenarioKey("test[1]", "iso[2]");
        String encoded = key.toString();
        ScenarioManager.ScenarioKey parsed = ScenarioManager.parseKey(encoded);
        assertThat(parsed.getScenarioName(), is("test[1]"));
        assertThat(parsed.getIsolation(), is("iso[2]"));
    }

    @Test
    public void shouldRoundTripKeyWithColons() {
        ScenarioManager.ScenarioKey key = new ScenarioManager.ScenarioKey("name:with:colons", "iso:value");
        String encoded = key.toString();
        ScenarioManager.ScenarioKey parsed = ScenarioManager.parseKey(encoded);
        assertThat(parsed.getScenarioName(), is("name:with:colons"));
        assertThat(parsed.getIsolation(), is("iso:value"));
    }

    @Test
    public void shouldRoundTripKeyWithDelimiterChars() {
        // Name and isolation containing the delimiter characters used internally
        ScenarioManager.ScenarioKey key = new ScenarioManager.ScenarioKey("3:name:N", "V2:val");
        String encoded = key.toString();
        ScenarioManager.ScenarioKey parsed = ScenarioManager.parseKey(encoded);
        assertThat(parsed.getScenarioName(), is("3:name:N"));
        assertThat(parsed.getIsolation(), is("V2:val"));
    }

    @Test
    public void shouldRoundTripEmptyName() {
        ScenarioManager.ScenarioKey key = new ScenarioManager.ScenarioKey("", null);
        String encoded = key.toString();
        ScenarioManager.ScenarioKey parsed = ScenarioManager.parseKey(encoded);
        assertThat(parsed.getScenarioName(), is(""));
        assertThat(parsed.getIsolation(), is((String) null));
    }

    @Test
    public void shouldRoundTripEmptyIsolation() {
        // Empty isolation (non-null but empty string) vs null isolation
        ScenarioManager.ScenarioKey keyEmpty = new ScenarioManager.ScenarioKey("test", "");
        ScenarioManager.ScenarioKey keyNull = new ScenarioManager.ScenarioKey("test", null);

        String encodedEmpty = keyEmpty.toString();
        String encodedNull = keyNull.toString();

        // They should encode differently
        assertThat("null vs empty isolation must encode differently", encodedEmpty, is(org.hamcrest.Matchers.not(encodedNull)));

        ScenarioManager.ScenarioKey parsedEmpty = ScenarioManager.parseKey(encodedEmpty);
        ScenarioManager.ScenarioKey parsedNull = ScenarioManager.parseKey(encodedNull);

        assertThat(parsedEmpty.getIsolation(), is(""));
        assertThat(parsedNull.getIsolation(), is((String) null));
    }

    @Test
    public void shouldNotCollideNamesWithBrackets() {
        // "test[1]" with null isolation vs "test" with isolation "1]"
        // In the old encoding both would produce "test[1]" — in the new
        // encoding they must be distinct.
        ScenarioManager.ScenarioKey key1 = new ScenarioManager.ScenarioKey("test[1]", null);
        ScenarioManager.ScenarioKey key2 = new ScenarioManager.ScenarioKey("test", "1]");

        String encoded1 = key1.toString();
        String encoded2 = key2.toString();

        assertThat("keys must not collide", encoded1, is(org.hamcrest.Matchers.not(encoded2)));

        // And both must round-trip correctly
        ScenarioManager.ScenarioKey parsed1 = ScenarioManager.parseKey(encoded1);
        ScenarioManager.ScenarioKey parsed2 = ScenarioManager.parseKey(encoded2);

        assertThat(parsed1.getScenarioName(), is("test[1]"));
        assertThat(parsed1.getIsolation(), is((String) null));
        assertThat(parsed2.getScenarioName(), is("test"));
        assertThat(parsed2.getIsolation(), is("1]"));
    }

    @Test
    public void shouldClearNotMatchPrefixScenario() {
        // "conv1" is a prefix of "conv1-extended" — clear("conv1") must NOT clear "conv1-extended"
        scenarioManager.setState("conv1", null, "state1");
        scenarioManager.setState("conv1-extended", null, "state2");

        scenarioManager.clear("conv1");

        assertThat(scenarioManager.getState("conv1", null), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("conv1-extended", null), is("state2"));
    }

    @Test
    public void shouldKeyBelongsToScenarioExactMatch() {
        // Encode two keys with different names, one a prefix of the other
        String key1 = new ScenarioManager.ScenarioKey("test", "iso").toString();
        String key2 = new ScenarioManager.ScenarioKey("test-extended", "iso").toString();

        assertThat(ScenarioManager.keyBelongsToScenario(key1, "test"), is(true));
        assertThat(ScenarioManager.keyBelongsToScenario(key1, "test-extended"), is(false));
        assertThat(ScenarioManager.keyBelongsToScenario(key2, "test"), is(false));
        assertThat(ScenarioManager.keyBelongsToScenario(key2, "test-extended"), is(true));
    }

    @Test
    public void shouldWorkEndToEndWithBracketNames() {
        // End-to-end: scenario name containing brackets
        scenarioManager.setState("test[1]", null, "Step1");
        scenarioManager.setState("test[1]", "isoA", "Step2");
        scenarioManager.setState("test", "1]", "Step3");

        assertThat(scenarioManager.getState("test[1]", null), is("Step1"));
        assertThat(scenarioManager.getState("test[1]", "isoA"), is("Step2"));
        assertThat(scenarioManager.getState("test", "1]"), is("Step3"));

        // Clear only "test[1]" — should NOT clear "test" with isolation "1]"
        scenarioManager.clear("test[1]");

        assertThat(scenarioManager.getState("test[1]", null), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("test[1]", "isoA"), is(ScenarioManager.STARTED));
        assertThat(scenarioManager.getState("test", "1]"), is("Step3"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleNullScenarioName() {
        assertThat(scenarioManager.getState(null, "s1"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldIgnoreSetWithNullScenarioName() {
        scenarioManager.setState(null, "s1", "Step1");
        assertThat(scenarioManager.getAllStates().isEmpty(), is(true));
    }

    @Test
    public void shouldIgnoreSetWithNullState() {
        scenarioManager.setState("conv1", "s1", null);
        assertThat(scenarioManager.getState("conv1", "s1"), is(ScenarioManager.STARTED));
    }

    @Test
    public void shouldTransitionWithIsolation() {
        scenarioManager.transitionState("conv1", "s1", "turn_1");
        assertThat(scenarioManager.getState("conv1", "s1"), is("turn_1"));
    }
}
