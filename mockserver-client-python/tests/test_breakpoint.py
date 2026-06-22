"""Tests for breakpoint matcher management and WS message routing."""
from __future__ import annotations

import asyncio
import base64
import json
from unittest.mock import AsyncMock

import pytest

from mockserver.models import (
    HttpRequest,
    HttpRequestAndHttpResponse,
    HttpResponse,
    KeyToMultiValue,
)
from mockserver.websocket_client import (
    BREAKPOINT_ID_HEADER_NAME,
    TYPE_HTTP_REQUEST,
    TYPE_HTTP_REQUEST_AND_RESPONSE,
    TYPE_HTTP_RESPONSE,
    TYPE_PAUSED_STREAM_FRAME_DTO,
    TYPE_STREAM_FRAME_DECISION_DTO,
    WEB_SOCKET_CORRELATION_ID_HEADER_NAME,
    MockServerWebSocketClient,
    _extract_breakpoint_id,
)


# -------------------------------------------------------------------
# _extract_breakpoint_id tests
# -------------------------------------------------------------------


class TestExtractBreakpointId:
    def test_extracts_from_headers(self):
        request = HttpRequest(
            headers=[
                KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-1"]),
            ]
        )
        assert _extract_breakpoint_id(request) == "bp-1"

    def test_returns_none_when_no_headers(self):
        request = HttpRequest()
        assert _extract_breakpoint_id(request) is None

    def test_returns_none_when_header_not_found(self):
        request = HttpRequest(
            headers=[KeyToMultiValue(name="Other", values=["val"])]
        )
        assert _extract_breakpoint_id(request) is None


# -------------------------------------------------------------------
# Breakpoint handler registration tests
# -------------------------------------------------------------------


class TestBreakpointHandlerRegistration:
    def test_set_and_remove_request_handler(self):
        client = MockServerWebSocketClient()
        handler = lambda req: req
        client.set_breakpoint_request_handler("bp-1", handler)
        assert "bp-1" in client._breakpoint_request_handlers
        client.remove_breakpoint_handlers("bp-1")
        assert "bp-1" not in client._breakpoint_request_handlers

    def test_set_and_remove_response_handler(self):
        client = MockServerWebSocketClient()
        handler = lambda req, resp: resp
        client.set_breakpoint_response_handler("bp-1", handler)
        assert "bp-1" in client._breakpoint_response_handlers
        client.remove_breakpoint_handlers("bp-1")
        assert "bp-1" not in client._breakpoint_response_handlers

    def test_set_and_remove_stream_frame_handler(self):
        client = MockServerWebSocketClient()
        handler = lambda frame: {"action": "CONTINUE"}
        client.set_breakpoint_stream_frame_handler("bp-1", handler)
        assert "bp-1" in client._breakpoint_stream_frame_handlers
        client.remove_breakpoint_handlers("bp-1")
        assert "bp-1" not in client._breakpoint_stream_frame_handlers

    def test_clear_breakpoint_handlers(self):
        client = MockServerWebSocketClient()
        client.set_breakpoint_request_handler("bp-1", lambda r: r)
        client.set_breakpoint_response_handler("bp-2", lambda r, s: s)
        client.set_breakpoint_stream_frame_handler("bp-3", lambda f: f)
        client.clear_breakpoint_handlers()
        assert len(client._breakpoint_request_handlers) == 0
        assert len(client._breakpoint_response_handlers) == 0
        assert len(client._breakpoint_stream_frame_handlers) == 0

    def test_ignores_none_breakpoint_id(self):
        client = MockServerWebSocketClient()
        client.set_breakpoint_request_handler(None, lambda r: r)
        assert len(client._breakpoint_request_handlers) == 0

    def test_ignores_none_handler(self):
        client = MockServerWebSocketClient()
        client.set_breakpoint_request_handler("bp-1", None)
        assert len(client._breakpoint_request_handlers) == 0


# -------------------------------------------------------------------
# Breakpoint message routing tests
# -------------------------------------------------------------------


class TestBreakpointRequestRouting:
    @pytest.fixture
    def client(self):
        c = MockServerWebSocketClient()
        c._ws = AsyncMock()
        c._ws.open = True
        return c

    @pytest.mark.asyncio
    async def test_routes_request_to_breakpoint_handler(self, client):
        """REQUEST phase: handler receives request, returns modified request."""
        def handler(request):
            modified = HttpRequest(method="PUT", path="/modified")
            return modified

        client.set_breakpoint_request_handler("bp-1", handler)

        request_dict = HttpRequest(
            method="GET",
            path="/api/test",
            headers=[
                KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-1"]),
                KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-1"]),
            ],
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST,
            "value": json.dumps(request_dict),
        })
        await client._handle_message(msg)

        client._ws.send.assert_called_once()
        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_REQUEST
        inner = json.loads(sent["value"])
        assert inner["method"] == "PUT"
        assert inner["path"] == "/modified"
        # Correlation id echoed
        found_corr = False
        for h in inner.get("headers", []):
            if h["name"] == WEB_SOCKET_CORRELATION_ID_HEADER_NAME:
                assert h["values"] == ["corr-1"]
                found_corr = True
        assert found_corr

    @pytest.mark.asyncio
    async def test_request_handler_returns_response_for_abort(self, client):
        """REQUEST phase: handler returns HttpResponse => abort."""
        def handler(request):
            return HttpResponse(status_code=403, body="forbidden")

        client.set_breakpoint_request_handler("bp-1", handler)

        request_dict = HttpRequest(
            headers=[
                KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-2"]),
                KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-1"]),
            ],
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST,
            "value": json.dumps(request_dict),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_RESPONSE
        inner = json.loads(sent["value"])
        assert inner["statusCode"] == 403

    @pytest.mark.asyncio
    async def test_request_handler_error_auto_continues(self, client):
        """REQUEST phase: handler throws => auto-continue with original request."""
        def handler(request):
            raise RuntimeError("oops")

        client.set_breakpoint_request_handler("bp-1", handler)

        request_dict = HttpRequest(
            method="GET",
            path="/original",
            headers=[
                KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-3"]),
                KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-1"]),
            ],
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST,
            "value": json.dumps(request_dict),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_REQUEST
        inner = json.loads(sent["value"])
        assert inner["path"] == "/original"

    @pytest.mark.asyncio
    async def test_request_without_breakpoint_id_uses_existing_callback(self, client):
        """No breakpoint id => falls through to existing requestHandler."""
        def forward_callback(request):
            return HttpRequest(method="POST", path="/forwarded")

        client.register_forward_callback(forward_callback)

        request_dict = HttpRequest(
            method="GET",
            path="/test",
            headers=[
                KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-4"]),
            ],
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST,
            "value": json.dumps(request_dict),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_REQUEST
        inner = json.loads(sent["value"])
        assert inner["path"] == "/forwarded"


class TestBreakpointResponseRouting:
    @pytest.fixture
    def client(self):
        c = MockServerWebSocketClient()
        c._ws = AsyncMock()
        c._ws.open = True
        return c

    @pytest.mark.asyncio
    async def test_routes_response_to_breakpoint_handler(self, client):
        """RESPONSE phase: handler receives request+response, returns modified response."""
        def handler(request, response):
            return HttpResponse(status_code=202, body="modified")

        client.set_breakpoint_response_handler("bp-2", handler)

        req_and_resp = HttpRequestAndHttpResponse(
            http_request=HttpRequest(
                method="GET",
                path="/test",
                headers=[
                    KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-5"]),
                    KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-2"]),
                ],
            ),
            http_response=HttpResponse(status_code=200),
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST_AND_RESPONSE,
            "value": json.dumps(req_and_resp),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_RESPONSE
        inner = json.loads(sent["value"])
        assert inner["statusCode"] == 202

    @pytest.mark.asyncio
    async def test_response_handler_error_auto_continues(self, client):
        """RESPONSE phase: handler throws => auto-continue with original response."""
        def handler(request, response):
            raise RuntimeError("boom")

        client.set_breakpoint_response_handler("bp-2", handler)

        req_and_resp = HttpRequestAndHttpResponse(
            http_request=HttpRequest(
                headers=[
                    KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["corr-6"]),
                    KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=["bp-2"]),
                ],
            ),
            http_response=HttpResponse(status_code=200),
        ).to_dict()

        msg = json.dumps({
            "type": TYPE_HTTP_REQUEST_AND_RESPONSE,
            "value": json.dumps(req_and_resp),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_HTTP_RESPONSE
        inner = json.loads(sent["value"])
        assert inner["statusCode"] == 200


class TestBreakpointStreamFrameRouting:
    @pytest.fixture
    def client(self):
        c = MockServerWebSocketClient()
        c._ws = AsyncMock()
        c._ws.open = True
        return c

    @pytest.mark.asyncio
    async def test_routes_stream_frame_to_handler(self, client):
        """Stream frame: handler receives paused frame, returns MODIFY decision."""
        def handler(paused_frame):
            return {
                "correlationId": paused_frame["correlationId"],
                "action": "MODIFY",
                "body": base64.b64encode(b"modified-data").decode("ascii"),
            }

        client.set_breakpoint_stream_frame_handler("bp-3", handler)

        paused_frame = {
            "correlationId": "frame-corr-1",
            "streamId": "stream-1",
            "sequenceNumber": 0,
            "direction": "OUTBOUND",
            "phase": "RESPONSE_STREAM",
            "body": base64.b64encode(b"original-data").decode("ascii"),
            "requestMethod": "GET",
            "requestPath": "/sse",
            "breakpointId": "bp-3",
        }

        msg = json.dumps({
            "type": TYPE_PAUSED_STREAM_FRAME_DTO,
            "value": json.dumps(paused_frame),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        assert sent["type"] == TYPE_STREAM_FRAME_DECISION_DTO
        inner = json.loads(sent["value"])
        assert inner["correlationId"] == "frame-corr-1"
        assert inner["action"] == "MODIFY"
        assert base64.b64decode(inner["body"]) == b"modified-data"

    @pytest.mark.asyncio
    async def test_stream_frame_drop_action(self, client):
        """Stream frame: handler returns DROP decision."""
        def handler(paused_frame):
            return {"action": "DROP"}

        client.set_breakpoint_stream_frame_handler("bp-3", handler)

        paused_frame = {
            "correlationId": "frame-corr-2",
            "breakpointId": "bp-3",
        }

        msg = json.dumps({
            "type": TYPE_PAUSED_STREAM_FRAME_DTO,
            "value": json.dumps(paused_frame),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        inner = json.loads(sent["value"])
        assert inner["action"] == "DROP"
        assert inner["correlationId"] == "frame-corr-2"

    @pytest.mark.asyncio
    async def test_stream_frame_unknown_breakpoint_auto_continues(self, client):
        """Stream frame with unknown breakpoint id => auto-continue."""
        paused_frame = {
            "correlationId": "frame-corr-3",
            "breakpointId": "unknown-bp",
        }

        msg = json.dumps({
            "type": TYPE_PAUSED_STREAM_FRAME_DTO,
            "value": json.dumps(paused_frame),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        inner = json.loads(sent["value"])
        assert inner["action"] == "CONTINUE"
        assert inner["correlationId"] == "frame-corr-3"

    @pytest.mark.asyncio
    async def test_stream_frame_handler_error_auto_continues(self, client):
        """Stream frame: handler throws => auto-continue."""
        def handler(paused_frame):
            raise RuntimeError("handler error")

        client.set_breakpoint_stream_frame_handler("bp-3", handler)

        paused_frame = {
            "correlationId": "frame-corr-4",
            "breakpointId": "bp-3",
        }

        msg = json.dumps({
            "type": TYPE_PAUSED_STREAM_FRAME_DTO,
            "value": json.dumps(paused_frame),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        inner = json.loads(sent["value"])
        assert inner["action"] == "CONTINUE"
        assert inner["correlationId"] == "frame-corr-4"

    @pytest.mark.asyncio
    async def test_stream_frame_no_breakpoint_id_auto_continues(self, client):
        """Stream frame with no breakpoint id => auto-continue."""
        paused_frame = {
            "correlationId": "frame-corr-5",
        }

        msg = json.dumps({
            "type": TYPE_PAUSED_STREAM_FRAME_DTO,
            "value": json.dumps(paused_frame),
        })
        await client._handle_message(msg)

        sent = json.loads(client._ws.send.call_args[0][0])
        inner = json.loads(sent["value"])
        assert inner["action"] == "CONTINUE"


class TestPerBreakpointIdRouting:
    """Test that multiple breakpoints route to their respective handlers."""

    @pytest.fixture
    def client(self):
        c = MockServerWebSocketClient()
        c._ws = AsyncMock()
        c._ws.open = True
        return c

    @pytest.mark.asyncio
    async def test_two_breakpoints_route_independently(self, client):
        """Two breakpoints with different ids route to their own handlers."""
        calls = {"bp-A": [], "bp-B": []}

        def handler_a(request):
            calls["bp-A"].append(request.path)
            return HttpRequest(method="GET", path="/from-A")

        def handler_b(request):
            calls["bp-B"].append(request.path)
            return HttpRequest(method="GET", path="/from-B")

        client.set_breakpoint_request_handler("bp-A", handler_a)
        client.set_breakpoint_request_handler("bp-B", handler_b)

        for bp_id, expected_path in [("bp-A", "/from-A"), ("bp-B", "/from-B")]:
            client._ws.send.reset_mock()
            request_dict = HttpRequest(
                method="GET",
                path=f"/test-{bp_id}",
                headers=[
                    KeyToMultiValue(name=WEB_SOCKET_CORRELATION_ID_HEADER_NAME, values=["c"]),
                    KeyToMultiValue(name=BREAKPOINT_ID_HEADER_NAME, values=[bp_id]),
                ],
            ).to_dict()
            msg = json.dumps({"type": TYPE_HTTP_REQUEST, "value": json.dumps(request_dict)})
            await client._handle_message(msg)
            sent = json.loads(client._ws.send.call_args[0][0])
            inner = json.loads(sent["value"])
            assert inner["path"] == expected_path

        assert calls["bp-A"] == ["/test-bp-A"]
        assert calls["bp-B"] == ["/test-bp-B"]
