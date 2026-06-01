from __future__ import annotations

import asyncio
import threading
from typing import Callable

from mockserver.async_client import AsyncMockServerClient
from mockserver.models import (
    Delay,
    Expectation,
    HttpChaosProfile,
    HttpError,
    HttpForward,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpRequestAndHttpResponse,
    HttpResponse,
    HttpTemplate,
    OpenAPIExpectation,
    TimeToLive,
    Times,
    VerificationTimes,
)


class SyncForwardChainExpectation:
    def __init__(self, async_chain, run_fn: Callable) -> None:
        self._async_chain = async_chain
        self._run = run_fn

    def with_id(self, id: str) -> SyncForwardChainExpectation:
        self._async_chain.with_id(id)
        return self

    def with_priority(self, priority: int) -> SyncForwardChainExpectation:
        self._async_chain.with_priority(priority)
        return self

    def respond(self, response_or_callback) -> list[Expectation]:
        return self._run(self._async_chain.respond(response_or_callback))

    def respond_with_delay(
        self, response: HttpResponse, delay: Delay
    ) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_delay(response, delay))

    def forward(
        self, forward_or_callback, response_callback: Callable | None = None
    ) -> list[Expectation]:
        return self._run(
            self._async_chain.forward(forward_or_callback, response_callback)
        )

    def forward_with_delay(
        self, forward: HttpForward, delay: Delay
    ) -> list[Expectation]:
        return self._run(self._async_chain.forward_with_delay(forward, delay))

    def respond_with_sse(self, sse_response) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_sse(sse_response))

    def respond_with_websocket(self, websocket_response) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_websocket(websocket_response))

    def respond_with_grpc_stream(self, grpc_stream_response) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_grpc_stream(grpc_stream_response))

    def respond_with_binary(self, binary_response) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_binary(binary_response))

    def respond_with_dns(self, dns_response) -> list[Expectation]:
        return self._run(self._async_chain.respond_with_dns(dns_response))

    def forward_with_template(self, template) -> list[Expectation]:
        return self._run(self._async_chain.forward_with_template(template))

    def forward_with_class_callback(self, class_callback) -> list[Expectation]:
        return self._run(self._async_chain.forward_with_class_callback(class_callback))

    def error(self, error: HttpError) -> list[Expectation]:
        return self._run(self._async_chain.error(error))


class MockServerClient:
    def __init__(
        self,
        host: str,
        port: int,
        context_path: str = "",
        secure: bool = False,
        ca_cert_path: str | None = None,
        tls_verify: bool = True,
    ) -> None:
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(
            target=self._loop.run_forever, daemon=True
        )
        self._thread.start()
        self._async_client = AsyncMockServerClient(
            host, port, context_path, secure, ca_cert_path, tls_verify=tls_verify
        )

    def _run(self, coro):
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return future.result()

    def upsert(self, *expectations: Expectation) -> list[Expectation]:
        return self._run(self._async_client.upsert(*expectations))

    def open_api_expectation(self, expectation: OpenAPIExpectation) -> None:
        return self._run(self._async_client.open_api_expectation(expectation))

    def clear(
        self,
        request: HttpRequest | None = None,
        clear_type: str | None = None,
    ) -> None:
        return self._run(self._async_client.clear(request, clear_type))

    def clear_by_id(
        self,
        expectation_id: str,
        clear_type: str | None = None,
    ) -> None:
        return self._run(self._async_client.clear_by_id(expectation_id, clear_type))

    def reset(self) -> None:
        return self._run(self._async_client.reset())

    def freeze_clock(self, instant: str | None = None) -> dict:
        """Freeze the server clock at the given ISO-8601 instant.

        If *instant* is ``None``, the clock freezes at the current real time.
        """
        return self._run(self._async_client.freeze_clock(instant))

    def advance_clock(self, duration_millis: int) -> dict:
        """Advance the frozen clock by *duration_millis* milliseconds."""
        return self._run(self._async_client.advance_clock(duration_millis))

    def reset_clock(self) -> dict:
        """Reset the server clock to real wall-clock time."""
        return self._run(self._async_client.reset_clock())

    def clock_status(self) -> dict:
        """Query the current clock status."""
        return self._run(self._async_client.clock_status())

    def set_service_chaos(
        self, host: str, chaos: HttpChaosProfile, ttl_millis: int | None = None
    ) -> dict:
        """Register a service-scoped HTTP chaos profile for an upstream host.

        If *ttl_millis* is set, the chaos auto-reverts after that many milliseconds.
        """
        return self._run(self._async_client.set_service_chaos(host, chaos, ttl_millis))

    def remove_service_chaos(self, host: str) -> dict:
        """Remove the service-scoped chaos profile registered for *host*."""
        return self._run(self._async_client.remove_service_chaos(host))

    def clear_service_chaos(self) -> dict:
        """Clear all service-scoped chaos profiles."""
        return self._run(self._async_client.clear_service_chaos())

    def service_chaos_status(self) -> dict:
        """Query the current service-scoped chaos registrations."""
        return self._run(self._async_client.service_chaos_status())

    def verify(
        self,
        request: HttpRequest,
        times: VerificationTimes | None = None,
    ) -> None:
        return self._run(self._async_client.verify(request, times))

    def verify_sequence(self, *requests: HttpRequest) -> None:
        return self._run(self._async_client.verify_sequence(*requests))

    def verify_zero_interactions(self) -> None:
        return self._run(self._async_client.verify_zero_interactions())

    def retrieve_recorded_requests(
        self, request: HttpRequest | None = None
    ) -> list[HttpRequest]:
        return self._run(self._async_client.retrieve_recorded_requests(request))

    def retrieve_active_expectations(
        self, request: HttpRequest | None = None
    ) -> list[Expectation]:
        return self._run(self._async_client.retrieve_active_expectations(request))

    def retrieve_recorded_expectations(
        self, request: HttpRequest | None = None
    ) -> list[Expectation]:
        return self._run(self._async_client.retrieve_recorded_expectations(request))

    def retrieve_recorded_requests_and_responses(
        self, request: HttpRequest | None = None
    ) -> list[HttpRequestAndHttpResponse]:
        return self._run(
            self._async_client.retrieve_recorded_requests_and_responses(request)
        )

    def retrieve_log_messages(
        self, request: HttpRequest | None = None
    ) -> list[str]:
        return self._run(self._async_client.retrieve_log_messages(request))

    def bind(self, *ports: int) -> list[int]:
        return self._run(self._async_client.bind(*ports))

    def stop(self) -> None:
        return self._run(self._async_client.stop())

    def has_started(self, attempts: int = 10, timeout: float = 0.5) -> bool:
        return self._run(self._async_client.has_started(attempts, timeout))

    def when(
        self,
        request: HttpRequest,
        times: Times | None = None,
        time_to_live: TimeToLive | None = None,
        priority: int | None = None,
    ) -> SyncForwardChainExpectation:
        async_chain = self._async_client.when(request, times, time_to_live, priority)
        return SyncForwardChainExpectation(async_chain, self._run)

    def mock_with_callback(
        self,
        request: HttpRequest,
        callback: Callable,
        times: Times | None = None,
        time_to_live: TimeToLive | None = None,
    ) -> list[Expectation]:
        return self._run(
            self._async_client.mock_with_callback(request, callback, times, time_to_live)
        )

    def mock_with_forward_callback(
        self,
        request: HttpRequest,
        forward_callback: Callable,
        response_callback: Callable | None = None,
        times: Times | None = None,
        time_to_live: TimeToLive | None = None,
    ) -> list[Expectation]:
        return self._run(
            self._async_client.mock_with_forward_callback(
                request, forward_callback, response_callback, times, time_to_live
            )
        )

    def close(self) -> None:
        self._run(self._async_client.close())
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(timeout=5)

    def __enter__(self) -> MockServerClient:
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()
