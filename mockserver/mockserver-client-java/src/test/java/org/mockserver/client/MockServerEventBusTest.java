package org.mockserver.client;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.client.MockServerEventBus.EventType.RESET;
import static org.mockserver.client.MockServerEventBus.EventType.STOP;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.client.MockServerEventBus.SubscriberHandler;

/**
 * @author albans
 */
public class MockServerEventBusTest {
	@Mock
	private SubscriberHandler subscriber;

	@Mock
	private SubscriberHandler secondSubscriber;

	private MockServerEventBus bus;

	@Before
	public void setupTestFixture() {
		bus = new MockServerEventBus();

		openMocks(this);
	}

	@Test
	public void shouldPublishStopEventWhenNoRegisterSubscriber() {
		// given no subscriber registered yet

		// when
		bus.publish(STOP);

		// then nothing, no exception
	}

	@Test
	public void shouldPublishStopEventToOneRegisteredSubscriber() {
		// given
		bus.subscribe(subscriber, STOP);

		// when
		bus.publish(STOP);

		// then
		verify(subscriber).handle();
	}

	@Test
	public void shouldPublishResetEventToTwoSubscribers() {
		// given
		bus.subscribe(subscriber, RESET, STOP);
		bus.subscribe(subscriber, RESET, STOP);

		// when
		bus.publish(RESET);

		// then
		verify(subscriber, times(2)).handle();
	}

	@Test
	public void shouldPublishEventToCorrectConsumer() {
		// given
		bus.subscribe(subscriber, RESET);
		bus.subscribe(secondSubscriber, STOP);

		// when
		bus.publish(STOP);

		// then
		verify(subscriber, never()).handle();
		verify(secondSubscriber).handle();
	}

	@Test
	public void shouldClearSubscribersAfterPublish() {
		// given
		bus.subscribe(subscriber, RESET, STOP);

		// when
		bus.publish(RESET);

		// then
		verify(subscriber, times(1)).handle();

		// when - publish again
		bus.publish(RESET);

		// then - subscriber should not be called again (was cleared)
		verify(subscriber, times(1)).handle();
	}

	@Test
	public void shouldOnlyRemoveSubscribersForPublishedEventType() {
		// given - two subscribers for different event types (e.g. two MockServerClient
		// instances sharing the same per-port event bus)
		bus.subscribe(subscriber, RESET);
		bus.subscribe(secondSubscriber, STOP);

		// when - one client publishes RESET
		bus.publish(RESET);

		// then - only the RESET subscriber is notified, the STOP subscriber is untouched
		verify(subscriber, times(1)).handle();
		verify(secondSubscriber, never()).handle();

		// when - another client later publishes STOP
		bus.publish(STOP);

		// then - the STOP subscriber is still registered and now notified, and the RESET
		// subscriber is not notified again (it was only removed for its own event type)
		verify(secondSubscriber, times(1)).handle();
		verify(subscriber, times(1)).handle();
	}

	@Test
	public void shouldNotRemoveOtherEventTypeSubscriptionForSameSubscriber() {
		// given - a single subscriber registered for both RESET and STOP
		bus.subscribe(subscriber, RESET, STOP);

		// when - RESET is published
		bus.publish(RESET);

		// then - the subscriber is notified once for RESET
		verify(subscriber, times(1)).handle();

		// when - STOP is published afterwards
		bus.publish(STOP);

		// then - the subscriber's STOP registration survived the RESET publish and is now notified
		verify(subscriber, times(2)).handle();
	}
}
