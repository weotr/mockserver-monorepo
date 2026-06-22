from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from mockserver.fluent import ForwardChainExpectation
from mockserver.models import (
    BinaryResponse,
    Delay,
    DnsRecord,
    DnsResponse,
    Expectation,
    GrpcBidiResponse,
    GrpcBidiRule,
    GrpcStreamMessage,
    GrpcStreamResponse,
    HttpClassCallback,
    HttpError,
    HttpForward,
    HttpObjectCallback,
    HttpOverrideForwardedRequest,
    HttpRequest,
    HttpResponse,
    HttpSseResponse,
    HttpTemplate,
    HttpWebSocketResponse,
    Times,
)


@pytest.fixture
def mock_client():
    client = AsyncMock()
    client.upsert = AsyncMock(return_value=[Expectation(id="test-id")])
    client._register_websocket_callback = AsyncMock(return_value="ws-client-id")
    return client


@pytest.fixture
def chain(mock_client):
    expectation = Expectation(
        http_request=HttpRequest(method="GET", path="/test"),
        times=Times(remaining_times=5, unlimited=False),
    )
    return ForwardChainExpectation(mock_client, expectation)


class TestWithId:
    def test_sets_id(self, chain):
        result = chain.with_id("my-id")
        assert result is chain
        assert chain._expectation.id == "my-id"


class TestWithPriority:
    def test_sets_priority(self, chain):
        result = chain.with_priority(10)
        assert result is chain
        assert chain._expectation.priority == 10


class TestRespond:
    @pytest.mark.asyncio
    async def test_with_http_response(self, chain, mock_client):
        response = HttpResponse(status_code=200, body="hello")
        result = await chain.respond(response)
        assert chain._expectation.http_response is response
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_http_template(self, chain, mock_client):
        template = HttpTemplate(template_type="JAVASCRIPT", template="return {};")
        result = await chain.respond(template)
        assert chain._expectation.http_response_template is template
        mock_client.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_callable(self, chain, mock_client):
        callback = lambda req: HttpResponse(status_code=200)
        result = await chain.respond(callback)
        mock_client._register_websocket_callback.assert_called_once_with(
            "response", callback
        )
        assert chain._expectation.http_response_object_callback.client_id == "ws-client-id"
        mock_client.upsert.assert_called_once()


class TestRespondWithDelay:
    @pytest.mark.asyncio
    async def test_sets_delay_on_response(self, chain, mock_client):
        response = HttpResponse(status_code=200)
        delay = Delay(time_unit="SECONDS", value=5)
        result = await chain.respond_with_delay(response, delay)
        assert response.delay is delay
        assert chain._expectation.http_response is response
        mock_client.upsert.assert_called_once()


class TestForward:
    @pytest.mark.asyncio
    async def test_with_http_forward(self, chain, mock_client):
        forward = HttpForward(host="example.com", port=8080)
        result = await chain.forward(forward)
        assert chain._expectation.http_forward is forward
        mock_client.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_http_override(self, chain, mock_client):
        override = HttpOverrideForwardedRequest(
            http_request=HttpRequest(path="/override")
        )
        result = await chain.forward(override)
        assert chain._expectation.http_override_forwarded_request is override
        mock_client.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_http_template(self, chain, mock_client):
        template = HttpTemplate(template_type="VELOCITY", template="$req.path")
        result = await chain.forward(template)
        assert chain._expectation.http_forward_template is template
        mock_client.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_callable(self, chain, mock_client):
        callback = lambda req: req
        result = await chain.forward(callback)
        mock_client._register_websocket_callback.assert_called_once_with(
            "forward", callback, None
        )
        assert chain._expectation.http_forward_object_callback.client_id == "ws-client-id"
        assert chain._expectation.http_forward_object_callback.response_callback is None
        mock_client.upsert.assert_called_once()

    @pytest.mark.asyncio
    async def test_with_callable_and_response_callback(self, chain, mock_client):
        fwd_callback = lambda req: req
        resp_callback = lambda req, resp: resp
        result = await chain.forward(fwd_callback, resp_callback)
        mock_client._register_websocket_callback.assert_called_once_with(
            "forward", fwd_callback, resp_callback
        )
        assert chain._expectation.http_forward_object_callback.response_callback is True


class TestForwardWithDelay:
    @pytest.mark.asyncio
    async def test_sets_delay_on_forward(self, chain, mock_client):
        forward = HttpForward(host="example.com")
        delay = Delay(time_unit="MILLISECONDS", value=500)
        result = await chain.forward_with_delay(forward, delay)
        assert forward.delay is delay
        assert chain._expectation.http_forward is forward
        mock_client.upsert.assert_called_once()


class TestError:
    @pytest.mark.asyncio
    async def test_sets_error(self, chain, mock_client):
        error = HttpError(drop_connection=True)
        result = await chain.error(error)
        assert chain._expectation.http_error is error
        mock_client.upsert.assert_called_once()


class TestRespondTypeError:
    @pytest.mark.asyncio
    async def test_respond_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpResponse"):
            await chain.respond("not a response")

    @pytest.mark.asyncio
    async def test_respond_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpResponse"):
            await chain.respond({"status": 200})


class TestForwardTypeError:
    @pytest.mark.asyncio
    async def test_forward_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpForward"):
            await chain.forward("not a forward")

    @pytest.mark.asyncio
    async def test_forward_with_int_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpForward"):
            await chain.forward(42)


class TestRespondWithSse:
    @pytest.mark.asyncio
    async def test_with_http_sse_response(self, chain, mock_client):
        sse_response = HttpSseResponse(status_code=200)
        result = await chain.respond_with_sse(sse_response)
        assert chain._expectation.http_sse_response is sse_response
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpSseResponse"):
            await chain.respond_with_sse("not an sse response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpSseResponse"):
            await chain.respond_with_sse({"status_code": 200})


class TestRespondWithWebSocket:
    @pytest.mark.asyncio
    async def test_with_http_websocket_response(self, chain, mock_client):
        ws_response = HttpWebSocketResponse(subprotocol="graphql-ws")
        result = await chain.respond_with_websocket(ws_response)
        assert chain._expectation.http_websocket_response is ws_response
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpWebSocketResponse"):
            await chain.respond_with_websocket("not a ws response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpWebSocketResponse"):
            await chain.respond_with_websocket({"subprotocol": "test"})


class TestRespondWithGrpcStream:
    @pytest.mark.asyncio
    async def test_with_grpc_stream_response(self, chain, mock_client):
        grpc_resp = GrpcStreamResponse(
            status_name="OK",
            messages=[GrpcStreamMessage(json='{"id": 1}')],
        )
        result = await chain.respond_with_grpc_stream(grpc_resp)
        assert chain._expectation.grpc_stream_response is grpc_resp
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected GrpcStreamResponse"):
            await chain.respond_with_grpc_stream("not a grpc response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected GrpcStreamResponse"):
            await chain.respond_with_grpc_stream({"statusName": "OK"})


class TestRespondWithGrpcBidi:
    @pytest.mark.asyncio
    async def test_with_grpc_bidi_response(self, chain, mock_client):
        bidi_resp = GrpcBidiResponse(
            status_name="OK",
            messages=[GrpcStreamMessage(json='{"id": 1}')],
            rules=[GrpcBidiRule(match_json='{"name": "test"}', responses=[GrpcStreamMessage(json='{"reply": "ok"}')])],
        )
        result = await chain.respond_with_grpc_bidi(bidi_resp)
        assert chain._expectation.grpc_bidi_response is bidi_resp
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected GrpcBidiResponse"):
            await chain.respond_with_grpc_bidi("not a grpc bidi response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected GrpcBidiResponse"):
            await chain.respond_with_grpc_bidi({"statusName": "OK"})


class TestRespondWithBinary:
    @pytest.mark.asyncio
    async def test_with_binary_response(self, chain, mock_client):
        bin_resp = BinaryResponse(binary_data="AQID")
        result = await chain.respond_with_binary(bin_resp)
        assert chain._expectation.binary_response is bin_resp
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected BinaryResponse"):
            await chain.respond_with_binary("not a binary response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected BinaryResponse"):
            await chain.respond_with_binary({"binaryData": "AQID"})


class TestRespondWithDns:
    @pytest.mark.asyncio
    async def test_with_dns_response(self, chain, mock_client):
        dns_resp = DnsResponse(
            response_code="NOERROR",
            answer_records=[DnsRecord.a_record("example.com", "1.2.3.4")],
        )
        result = await chain.respond_with_dns(dns_resp)
        assert chain._expectation.dns_response is dns_resp
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected DnsResponse"):
            await chain.respond_with_dns("not a dns response")

    @pytest.mark.asyncio
    async def test_with_dict_raises(self, chain):
        with pytest.raises(TypeError, match="Expected DnsResponse"):
            await chain.respond_with_dns({"responseCode": "NOERROR"})


class TestForwardWithTemplate:
    @pytest.mark.asyncio
    async def test_with_http_template(self, chain, mock_client):
        template = HttpTemplate(template_type="VELOCITY", template="$req.path")
        result = await chain.forward_with_template(template)
        assert chain._expectation.http_forward_template is template
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpTemplate"):
            await chain.forward_with_template("not a template")


class TestRespondWithClassCallback:
    @pytest.mark.asyncio
    async def test_with_class_callback(self, chain, mock_client):
        callback = HttpClassCallback(callback_class="com.example.MyResponseCallback")
        result = await chain.respond_with_class_callback(callback)
        assert chain._expectation.http_response_class_callback is callback
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_class_name_string_shorthand(self, chain, mock_client):
        await chain.respond_with_class_callback("com.example.MyResponseCallback")
        cb = chain._expectation.http_response_class_callback
        assert isinstance(cb, HttpClassCallback)
        assert cb.callback_class == "com.example.MyResponseCallback"
        # the expectation serializes the wire key httpResponseClassCallback
        assert chain._expectation.to_dict()["httpResponseClassCallback"] == {
            "callbackClass": "com.example.MyResponseCallback"
        }

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpClassCallback or str"):
            await chain.respond_with_class_callback(123)


class TestForwardWithClassCallback:
    @pytest.mark.asyncio
    async def test_with_class_callback(self, chain, mock_client):
        callback = HttpClassCallback(callback_class="com.example.MyForwardCallback")
        result = await chain.forward_with_class_callback(callback)
        assert chain._expectation.http_forward_class_callback is callback
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_class_name_string_shorthand(self, chain, mock_client):
        await chain.forward_with_class_callback("com.example.MyForwardCallback")
        cb = chain._expectation.http_forward_class_callback
        assert isinstance(cb, HttpClassCallback)
        assert cb.callback_class == "com.example.MyForwardCallback"
        assert chain._expectation.to_dict()["httpForwardClassCallback"] == {
            "callbackClass": "com.example.MyForwardCallback"
        }

    @pytest.mark.asyncio
    async def test_with_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected HttpClassCallback or str"):
            await chain.forward_with_class_callback(123)


class TestWithSteps:
    @pytest.mark.asyncio
    async def test_with_steps(self, chain, mock_client):
        from mockserver.models import ExpectationStep
        steps = [
            ExpectationStep(
                http_request=HttpRequest(method="POST", path="/webhook"),
                blocking=True,
                timeout=Delay(time_unit="SECONDS", value=5),
                failure_policy="FAIL_FAST",
            ),
            ExpectationStep(
                http_response=HttpResponse(status_code=200, body="ok"),
                responder=True,
            ),
        ]
        result = await chain.with_steps(steps)
        assert chain._expectation.steps is steps
        assert len(chain._expectation.steps) == 2
        assert chain._expectation.steps[0].http_request.path == "/webhook"
        assert chain._expectation.steps[1].responder is True
        mock_client.upsert.assert_called_once_with(chain._expectation)

    @pytest.mark.asyncio
    async def test_with_steps_invalid_type_raises(self, chain):
        with pytest.raises(TypeError, match="Expected a list of ExpectationStep"):
            await chain.with_steps("not a list")

    @pytest.mark.asyncio
    async def test_with_steps_invalid_items_raises(self, chain):
        with pytest.raises(TypeError, match="Expected a list of ExpectationStep"):
            await chain.with_steps([{"httpResponse": {"statusCode": 200}}])


class TestChaining:
    @pytest.mark.asyncio
    async def test_chained_with_id_and_priority(self, chain, mock_client):
        result = await (
            chain.with_id("chained-id")
            .with_priority(5)
            .respond(HttpResponse(status_code=200))
        )
        assert chain._expectation.id == "chained-id"
        assert chain._expectation.priority == 5
        mock_client.upsert.assert_called_once()
