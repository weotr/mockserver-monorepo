from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from mockserver.async_client import AsyncMockServerClient
from mockserver.exceptions import (
    MockServerConnectionError,
    MockServerError,
    MockServerVerificationError,
)
from mockserver.fluent import ForwardChainExpectation
from mockserver.models import (
    Expectation,
    HttpForward,
    HttpObjectCallback,
    HttpRequest,
    HttpRequestAndHttpResponse,
    HttpResponse,
    LoadProfile,
    LoadScenario,
    LoadStage,
    LoadStep,
    OpenAPIExpectation,
    Ports,
    TimeToLive,
    Times,
    VerificationTimes,
)


class TestAsyncClientInit:
    def test_http_base_url(self):
        client = AsyncMockServerClient("localhost", 1080)
        assert client._base_url == "http://localhost:1080"

    def test_https_base_url(self):
        client = AsyncMockServerClient("localhost", 1080, secure=True)
        assert client._base_url == "https://localhost:1080"

    def test_with_context_path(self):
        client = AsyncMockServerClient("localhost", 1080, context_path="api")
        assert client._base_url == "http://localhost:1080/api"

    def test_with_context_path_leading_slash(self):
        client = AsyncMockServerClient("localhost", 1080, context_path="/api")
        assert client._base_url == "http://localhost:1080/api"

    def test_stores_connection_params(self):
        client = AsyncMockServerClient(
            "myhost", 9090, context_path="ctx", secure=True
        )
        assert client._host == "myhost"
        assert client._port == 9090
        assert client._context_path == "ctx"
        assert client._secure is True
        assert client._ca_cert_path is None

    def test_stores_ca_cert_path(self):
        client = AsyncMockServerClient(
            "myhost", 9090, secure=False, ca_cert_path="/certs/ca.pem"
        )
        assert client._ca_cert_path == "/certs/ca.pem"

    def test_secure_with_invalid_ca_cert_raises(self):
        import pytest as pt
        with pt.raises(FileNotFoundError):
            AsyncMockServerClient(
                "myhost", 9090, secure=True, ca_cert_path="/nonexistent/ca.pem"
            )

    def test_empty_websocket_clients_list(self):
        client = AsyncMockServerClient("localhost", 1080)
        assert client._websocket_clients == []


class TestAsyncClientContextManager:
    @pytest.mark.asyncio
    async def test_aenter_returns_self(self):
        client = AsyncMockServerClient("localhost", 1080)
        async with client as c:
            assert c is client

    @pytest.mark.asyncio
    async def test_aexit_calls_close(self):
        client = AsyncMockServerClient("localhost", 1080)
        client.close = AsyncMock()
        async with client:
            pass
        client.close.assert_called_once()


class TestWhen:
    def test_returns_forward_chain_expectation(self):
        client = AsyncMockServerClient("localhost", 1080)
        chain = client.when(HttpRequest(method="GET", path="/test"))
        assert isinstance(chain, ForwardChainExpectation)
        assert chain._expectation.http_request.method == "GET"
        assert chain._expectation.http_request.path == "/test"

    def test_with_times_and_ttl(self):
        client = AsyncMockServerClient("localhost", 1080)
        times = Times(remaining_times=3, unlimited=False)
        ttl = TimeToLive(time_unit="SECONDS", time_to_live=60, unlimited=False)
        chain = client.when(
            HttpRequest(path="/test"), times=times, time_to_live=ttl, priority=5
        )
        assert chain._expectation.times is times
        assert chain._expectation.time_to_live is ttl
        assert chain._expectation.priority == 5


class MockHandler(BaseHTTPRequestHandler):
    response_status = 200
    response_body = "[]"
    last_request_body = None
    last_request_bytes = None
    last_content_type = None
    last_path = None
    last_method = None

    def do_PUT(self):
        content_length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(content_length) if content_length > 0 else b""
        MockHandler.last_request_bytes = raw
        MockHandler.last_content_type = self.headers.get("Content-Type")
        MockHandler.last_request_body = raw.decode("utf-8", errors="replace")
        MockHandler.last_path = self.path
        MockHandler.last_method = "PUT"

        self.send_response(MockHandler.response_status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(MockHandler.response_body.encode("utf-8"))

    def do_GET(self):
        MockHandler.last_request_body = None
        MockHandler.last_path = self.path
        MockHandler.last_method = "GET"

        self.send_response(MockHandler.response_status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(MockHandler.response_body.encode("utf-8"))

    def do_DELETE(self):
        MockHandler.last_request_body = None
        MockHandler.last_path = self.path
        MockHandler.last_method = "DELETE"

        self.send_response(MockHandler.response_status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(MockHandler.response_body.encode("utf-8"))

    def log_message(self, format, *args):
        pass


@pytest.fixture
def mock_server():
    server = HTTPServer(("127.0.0.1", 0), MockHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever)
    thread.daemon = True
    thread.start()
    MockHandler.response_status = 200
    MockHandler.response_body = "[]"
    MockHandler.last_request_body = None
    MockHandler.last_request_bytes = None
    MockHandler.last_content_type = None
    MockHandler.last_path = None
    MockHandler.last_method = None
    yield port
    server.shutdown()


class TestUpsert:
    @pytest.mark.asyncio
    async def test_upsert_single_expectation(self, mock_server):
        MockHandler.response_body = json.dumps([{
            "id": "exp-1",
            "httpRequest": {"method": "GET", "path": "/test"},
            "httpResponse": {"statusCode": 200},
        }])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        expectation = Expectation(
            http_request=HttpRequest(method="GET", path="/test"),
            http_response=HttpResponse(status_code=200),
        )
        result = await client.upsert(expectation)
        assert len(result) == 1
        assert result[0].id == "exp-1"
        assert MockHandler.last_path == "/mockserver/expectation"
        sent = json.loads(MockHandler.last_request_body)
        assert isinstance(sent, list)
        assert sent[0]["httpRequest"]["method"] == "GET"

    @pytest.mark.asyncio
    async def test_upsert_error(self, mock_server):
        MockHandler.response_status = 400
        MockHandler.response_body = "Bad Request"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Invalid expectation"):
            await client.upsert(Expectation())


class TestClear:
    @pytest.mark.asyncio
    async def test_clear_with_request(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.clear(HttpRequest(path="/test"))
        assert "/mockserver/clear" in MockHandler.last_path
        sent = json.loads(MockHandler.last_request_body)
        assert sent["path"] == "/test"

    @pytest.mark.asyncio
    async def test_clear_with_type(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.clear(clear_type="LOG")
        assert "type=LOG" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_clear_by_id(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.clear_by_id("exp-123")
        sent = json.loads(MockHandler.last_request_body)
        assert sent["id"] == "exp-123"


class TestReset:
    @pytest.mark.asyncio
    async def test_reset(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.reset()
        assert MockHandler.last_path == "/mockserver/reset"


class TestVerify:
    @pytest.mark.asyncio
    async def test_verify_success(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify(HttpRequest(path="/test"))
        assert "/mockserver/verify" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_verify_with_times(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify(
            HttpRequest(path="/test"),
            VerificationTimes(at_least=2, at_most=5),
        )
        sent = json.loads(MockHandler.last_request_body)
        assert sent["times"]["atLeast"] == 2
        assert sent["times"]["atMost"] == 5

    @pytest.mark.asyncio
    async def test_verify_with_response(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify(
            HttpRequest(path="/test"),
            response=HttpResponse(status_code=200),
        )
        sent = json.loads(MockHandler.last_request_body)
        assert sent["httpRequest"]["path"] == "/test"
        assert sent["httpResponse"]["statusCode"] == 200

    @pytest.mark.asyncio
    async def test_verify_response_only(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify(
            response=HttpResponse(status_code=200),
        )
        sent = json.loads(MockHandler.last_request_body)
        assert "httpRequest" not in sent
        assert sent["httpResponse"]["statusCode"] == 200

    @pytest.mark.asyncio
    async def test_verify_failure_raises(self, mock_server):
        MockHandler.response_status = 406
        MockHandler.response_body = "Request not found"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerVerificationError, match="Request not found"):
            await client.verify(HttpRequest(path="/test"))


class TestVerifySequence:
    @pytest.mark.asyncio
    async def test_verify_sequence_success(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify_sequence(
            HttpRequest(path="/first"),
            HttpRequest(path="/second"),
        )
        assert "/mockserver/verifySequence" in MockHandler.last_path
        sent = json.loads(MockHandler.last_request_body)
        assert len(sent["httpRequests"]) == 2
        assert sent["httpRequests"][0]["path"] == "/first"
        assert sent["httpRequests"][1]["path"] == "/second"

    @pytest.mark.asyncio
    async def test_verify_sequence_with_responses(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify_sequence(
            HttpRequest(path="/a"),
            HttpRequest(path="/b"),
            responses=[
                HttpResponse(status_code=200),
                HttpResponse(status_code=201),
            ],
        )
        sent = json.loads(MockHandler.last_request_body)
        assert len(sent["httpRequests"]) == 2
        assert len(sent["httpResponses"]) == 2
        assert sent["httpResponses"][0]["statusCode"] == 200
        assert sent["httpResponses"][1]["statusCode"] == 201

    @pytest.mark.asyncio
    async def test_verify_sequence_failure(self, mock_server):
        MockHandler.response_status = 406
        MockHandler.response_body = "Sequence not found"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerVerificationError, match="Sequence not found"):
            await client.verify_sequence(HttpRequest(path="/a"))


class TestRetrieve:
    @pytest.mark.asyncio
    async def test_retrieve_recorded_requests(self, mock_server):
        MockHandler.response_body = json.dumps([
            {"method": "GET", "path": "/recorded1"},
            {"method": "POST", "path": "/recorded2"},
        ])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_recorded_requests()
        assert len(result) == 2
        assert result[0].method == "GET"
        assert result[0].path == "/recorded1"
        assert "type=REQUESTS" in MockHandler.last_path
        assert "format=JSON" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_retrieve_active_expectations(self, mock_server):
        MockHandler.response_body = json.dumps([
            {"id": "e1", "httpRequest": {"path": "/active"}},
        ])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_active_expectations()
        assert len(result) == 1
        assert result[0].id == "e1"
        assert "type=ACTIVE_EXPECTATIONS" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_retrieve_recorded_expectations(self, mock_server):
        MockHandler.response_body = json.dumps([
            {"id": "re1", "httpRequest": {"path": "/rec"}},
        ])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_recorded_expectations()
        assert len(result) == 1
        assert "type=RECORDED_EXPECTATIONS" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_retrieve_expectations_as_code(self, mock_server):
        MockHandler.response_body = (
            'new MockServerClient("localhost", 1080).when(request().withPath("/code"));'
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        code = await client.retrieve_expectations_as_code("java")
        assert isinstance(code, str)
        assert "/code" in code
        assert "type=ACTIVE_EXPECTATIONS" in MockHandler.last_path
        assert "format=JAVA" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_retrieve_recorded_expectations_as_code(self, mock_server):
        MockHandler.response_body = "# generated python\n"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        code = await client.retrieve_recorded_expectations_as_code("python")
        assert isinstance(code, str)
        assert "type=RECORDED_EXPECTATIONS" in MockHandler.last_path
        assert "format=PYTHON" in MockHandler.last_path

    @pytest.mark.asyncio
    async def test_retrieve_recorded_requests_and_responses(self, mock_server):
        MockHandler.response_body = json.dumps([
            {
                "httpRequest": {"method": "GET", "path": "/rr"},
                "httpResponse": {"statusCode": 200},
            }
        ])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_recorded_requests_and_responses()
        assert len(result) == 1
        assert result[0].http_request.path == "/rr"
        assert result[0].http_response.status_code == 200

    @pytest.mark.asyncio
    async def test_retrieve_log_messages(self, mock_server):
        MockHandler.response_body = json.dumps(["log line 1", "log line 2"])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_log_messages()
        assert len(result) == 2
        assert result[0] == "log line 1"

    @pytest.mark.asyncio
    async def test_retrieve_empty_response(self, mock_server):
        MockHandler.response_body = ""
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.retrieve_recorded_requests()
        assert result == []


class TestBind:
    @pytest.mark.asyncio
    async def test_bind_ports(self, mock_server):
        MockHandler.response_body = json.dumps({"ports": [1080, 1081]})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.bind(1080, 1081)
        assert result == [1080, 1081]
        sent = json.loads(MockHandler.last_request_body)
        assert sent["ports"] == [1080, 1081]


class TestHasStarted:
    @pytest.mark.asyncio
    async def test_has_started_returns_true(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.has_started(attempts=1)
        assert result is True

    @pytest.mark.asyncio
    async def test_has_started_returns_false_on_connection_error(self):
        client = AsyncMockServerClient("127.0.0.1", 19999)
        result = await client.has_started(attempts=1, timeout=0.01)
        assert result is False


class TestStop:
    @pytest.mark.asyncio
    async def test_stop(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.stop()
        assert MockHandler.last_path == "/mockserver/stop"

    @pytest.mark.asyncio
    async def test_stop_ignores_connection_error(self):
        client = AsyncMockServerClient("127.0.0.1", 19999)
        await client.stop()


class TestOpenApiExpectation:
    @pytest.mark.asyncio
    async def test_open_api_expectation(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.open_api_expectation(
            OpenAPIExpectation(spec_url_or_payload="https://example.com/openapi.json")
        )
        assert "/mockserver/openapi" in MockHandler.last_path
        sent = json.loads(MockHandler.last_request_body)
        assert sent["specUrlOrPayload"] == "https://example.com/openapi.json"


class TestVerifyZeroInteractions:
    @pytest.mark.asyncio
    async def test_verify_zero_interactions(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.verify_zero_interactions()
        sent = json.loads(MockHandler.last_request_body)
        assert sent["times"]["atMost"] == 0


class TestRegisterWebSocketCallback:
    @pytest.mark.asyncio
    async def test_register_websocket_callback(self):
        client = AsyncMockServerClient("localhost", 1080)
        with patch("mockserver.async_client.MockServerWebSocketClient") as MockWSClient:
            mock_ws = MagicMock()
            mock_ws.connect = AsyncMock(return_value="test-ws-id")
            mock_ws.listen = AsyncMock()
            MockWSClient.return_value = mock_ws

            callback = lambda req: HttpResponse(status_code=200)
            client_id = await client._register_websocket_callback("response", callback)

            assert client_id == "test-ws-id"
            mock_ws.connect.assert_called_once_with(
                "localhost", 1080, "", False, None, tls_verify=True
            )
            mock_ws.register_response_callback.assert_called_once_with(callback)
            assert mock_ws in client._websocket_clients

    @pytest.mark.asyncio
    async def test_register_forward_callback(self):
        client = AsyncMockServerClient("localhost", 1080)
        with patch("mockserver.async_client.MockServerWebSocketClient") as MockWSClient:
            mock_ws = MagicMock()
            mock_ws.connect = AsyncMock(return_value="fwd-ws-id")
            mock_ws.listen = AsyncMock()
            MockWSClient.return_value = mock_ws

            fwd_fn = lambda req: req
            resp_fn = lambda req, resp: resp
            client_id = await client._register_websocket_callback(
                "forward", fwd_fn, resp_fn
            )

            assert client_id == "fwd-ws-id"
            mock_ws.register_forward_callback.assert_called_once_with(fwd_fn, resp_fn)


class TestMockWithCallback:
    @pytest.mark.asyncio
    async def test_mock_with_callback_registers_object_callback(self):
        client = AsyncMockServerClient("localhost", 1080)
        client._register_websocket_callback = AsyncMock(return_value="obj-cb-id")
        captured: list[Expectation] = []

        async def fake_upsert(*expectations):
            captured.extend(expectations)
            return list(expectations)

        client.upsert = fake_upsert  # type: ignore[assignment]

        handler = lambda req: HttpResponse(status_code=200, body="dynamic")
        request = HttpRequest(method="GET", path="/callback")
        await client.mock_with_callback(request, handler)

        client._register_websocket_callback.assert_called_once_with(
            "response", handler
        )
        assert len(captured) == 1
        expectation = captured[0]
        assert expectation.http_response_object_callback == HttpObjectCallback(
            client_id="obj-cb-id"
        )
        # the wire payload references the WS clientId, not the closure itself
        wire = expectation.to_dict()
        assert wire["httpResponseObjectCallback"] == {"clientId": "obj-cb-id"}
        assert "httpResponse" not in wire

    @pytest.mark.asyncio
    async def test_mock_with_forward_callback_sets_response_flag(self):
        client = AsyncMockServerClient("localhost", 1080)
        client._register_websocket_callback = AsyncMock(return_value="fwd-cb-id")
        captured: list[Expectation] = []

        async def fake_upsert(*expectations):
            captured.extend(expectations)
            return list(expectations)

        client.upsert = fake_upsert  # type: ignore[assignment]

        fwd = lambda req: req
        resp = lambda req, resp: resp
        request = HttpRequest(method="GET", path="/forward-callback")
        await client.mock_with_forward_callback(request, fwd, resp)

        client._register_websocket_callback.assert_called_once_with(
            "forward", fwd, resp
        )
        expectation = captured[0]
        cb = expectation.http_forward_object_callback
        assert cb.client_id == "fwd-cb-id"
        assert cb.response_callback is True


class TestClose:
    @pytest.mark.asyncio
    async def test_close_cleans_up_websockets(self):
        client = AsyncMockServerClient("localhost", 1080)
        ws1 = AsyncMock()
        ws2 = AsyncMock()
        client._websocket_clients = [ws1, ws2]
        await client.close()
        ws1.close.assert_called_once()
        ws2.close.assert_called_once()
        assert client._websocket_clients == []


class TestGrpcDescriptors:
    @pytest.mark.asyncio
    async def test_upload_grpc_descriptor_sends_raw_bytes(self, mock_server):
        MockHandler.response_status = 201
        MockHandler.response_body = '{"status":"loaded"}'
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        # Arbitrary non-UTF-8 bytes to prove the body is sent verbatim.
        descriptor = bytes([0x0a, 0x07, 0x74, 0x65, 0x73, 0x74, 0xff, 0x00, 0x80])
        await client.upload_grpc_descriptor(descriptor)
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/grpc/descriptors"
        assert MockHandler.last_request_bytes == descriptor
        assert MockHandler.last_content_type == "application/octet-stream"

    @pytest.mark.asyncio
    async def test_upload_grpc_descriptor_empty_raises(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="must not be empty"):
            await client.upload_grpc_descriptor(b"")

    @pytest.mark.asyncio
    async def test_upload_grpc_descriptor_error_raises(self, mock_server):
        MockHandler.response_status = 400
        MockHandler.response_body = "descriptor set body is empty"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Failed to upload gRPC descriptor"):
            await client.upload_grpc_descriptor(b"\x01\x02")

    @pytest.mark.asyncio
    async def test_retrieve_grpc_services(self, mock_server):
        MockHandler.response_body = json.dumps([
            {
                "name": "example.Greeter",
                "methods": [
                    {
                        "name": "SayHello",
                        "inputType": "example.HelloRequest",
                        "outputType": "example.HelloReply",
                        "clientStreaming": False,
                        "serverStreaming": False,
                    }
                ],
            }
        ])
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        services = await client.retrieve_grpc_services()
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/grpc/services"
        assert len(services) == 1
        assert services[0]["name"] == "example.Greeter"
        assert services[0]["methods"][0]["name"] == "SayHello"

    @pytest.mark.asyncio
    async def test_retrieve_grpc_services_empty(self, mock_server):
        MockHandler.response_body = ""
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        services = await client.retrieve_grpc_services()
        assert services == []

    @pytest.mark.asyncio
    async def test_retrieve_grpc_services_error_raises(self, mock_server):
        MockHandler.response_status = 400
        MockHandler.response_body = "boom"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Failed to retrieve gRPC services"):
            await client.retrieve_grpc_services()

    @pytest.mark.asyncio
    async def test_clear_grpc_descriptors(self, mock_server):
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.clear_grpc_descriptors()
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/grpc/clear"

    @pytest.mark.asyncio
    async def test_clear_grpc_descriptors_error_raises(self, mock_server):
        MockHandler.response_status = 500
        MockHandler.response_body = "boom"
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Failed to clear gRPC descriptors"):
            await client.clear_grpc_descriptors()


class TestAsyncLoadScenario:
    def _scenario(self) -> LoadScenario:
        return LoadScenario(
            name="checkout-flow",
            start_delay_millis=2000,
            profile=LoadProfile(stages=[LoadStage.vu_stage(30000, vus=5)]),
            steps=[
                LoadStep(request=HttpRequest(method="GET", path="/health")),
            ],
        )

    @pytest.mark.asyncio
    async def test_load_scenario_registers(self, mock_server):
        MockHandler.response_body = json.dumps({"name": "checkout-flow", "state": "LOADED"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.load_scenario(self._scenario())
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/loadScenario"
        sent = json.loads(MockHandler.last_request_body)
        assert sent["name"] == "checkout-flow"
        assert sent["startDelayMillis"] == 2000
        assert sent["profile"]["stages"][0]["vus"] == 5
        assert result == {"name": "checkout-flow", "state": "LOADED"}

    @pytest.mark.asyncio
    async def test_load_scenario_register_allowed_when_disabled(self, mock_server):
        # registration does NOT require loadGenerationEnabled
        MockHandler.response_body = json.dumps({"name": "checkout-flow", "state": "LOADED"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.load_scenario(self._scenario())
        assert result["state"] == "LOADED"

    @pytest.mark.asyncio
    async def test_load_scenarios_list(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"scenarios": [{"name": "checkout-flow", "state": "RUNNING"}]}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.load_scenarios()
        assert MockHandler.last_method == "GET"
        assert MockHandler.last_path == "/mockserver/loadScenario"
        assert result["scenarios"][0]["name"] == "checkout-flow"

    @pytest.mark.asyncio
    async def test_get_load_scenario(self, mock_server):
        MockHandler.response_body = json.dumps({"name": "checkout-flow", "state": "LOADED"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.get_load_scenario("checkout-flow")
        assert MockHandler.last_method == "GET"
        assert MockHandler.last_path == "/mockserver/loadScenario/checkout-flow"
        assert result["state"] == "LOADED"

    @pytest.mark.asyncio
    async def test_get_load_scenario_not_found(self, mock_server):
        MockHandler.response_status = 404
        MockHandler.response_body = '{"error": "no such scenario"}'
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="not found"):
            await client.get_load_scenario("missing")

    @pytest.mark.asyncio
    async def test_delete_load_scenario(self, mock_server):
        MockHandler.response_body = json.dumps({"name": "checkout-flow", "state": "STOPPED"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.delete_load_scenario("checkout-flow")
        assert MockHandler.last_method == "DELETE"
        assert MockHandler.last_path == "/mockserver/loadScenario/checkout-flow"
        assert result["state"] == "STOPPED"

    @pytest.mark.asyncio
    async def test_clear_load_scenarios(self, mock_server):
        MockHandler.response_body = json.dumps({"cleared": True})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.clear_load_scenarios()
        assert MockHandler.last_method == "DELETE"
        assert MockHandler.last_path == "/mockserver/loadScenario"
        assert result["cleared"] is True

    @pytest.mark.asyncio
    async def test_start_load_scenarios_single_name(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"started": [{"name": "checkout-flow", "state": "RUNNING"}], "status": "ok"}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.start_load_scenarios("checkout-flow")
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/loadScenario/start"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"names": ["checkout-flow"]}
        assert result["started"][0]["state"] == "RUNNING"

    @pytest.mark.asyncio
    async def test_start_load_scenarios_list(self, mock_server):
        MockHandler.response_body = json.dumps({"started": [], "status": "ok"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.start_load_scenarios(["a", "b"])
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"names": ["a", "b"]}

    @pytest.mark.asyncio
    async def test_start_load_scenarios_forbidden_when_disabled(self, mock_server):
        MockHandler.response_status = 403
        MockHandler.response_body = '{"error": "disabled"}'
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="load generation is disabled"):
            await client.start_load_scenarios("checkout-flow")

    @pytest.mark.asyncio
    async def test_start_load_scenarios_unknown_name(self, mock_server):
        MockHandler.response_status = 404
        MockHandler.response_body = '{"error": "unknown"}'
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Unknown load scenario"):
            await client.start_load_scenarios("missing")

    @pytest.mark.asyncio
    async def test_stop_load_scenarios_named(self, mock_server):
        MockHandler.response_body = json.dumps({"stopped": ["a"], "status": "ok"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.stop_load_scenarios(["a"])
        assert MockHandler.last_method == "PUT"
        assert MockHandler.last_path == "/mockserver/loadScenario/stop"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"names": ["a"]}

    @pytest.mark.asyncio
    async def test_stop_load_scenarios_single_string(self, mock_server):
        MockHandler.response_body = json.dumps({"stopped": ["a"], "status": "ok"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.stop_load_scenarios("a")
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"names": ["a"]}

    @pytest.mark.asyncio
    async def test_stop_load_scenarios_all(self, mock_server):
        MockHandler.response_body = json.dumps({"stopped": ["a", "b"], "status": "ok"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.stop_load_scenarios()
        assert MockHandler.last_path == "/mockserver/loadScenario/stop"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {}

    @pytest.mark.asyncio
    async def test_run_load_scenario_registers_then_starts(self, mock_server):
        # the mock handler returns the same body for both the register PUT and
        # the start PUT; assert the final call is the start endpoint
        MockHandler.response_body = json.dumps({"name": "checkout-flow", "state": "LOADED"})
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.run_load_scenario(self._scenario())
        assert MockHandler.last_path == "/mockserver/loadScenario/start"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"names": ["checkout-flow"]}


class TestAsyncScenario:
    @pytest.mark.asyncio
    async def test_scenario_state(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"scenarioName": "Deploy", "currentState": "Deploying"}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        state = await client.scenario("Deploy").state()
        assert MockHandler.last_path == "/mockserver/scenario/Deploy"
        assert MockHandler.last_method == "GET"
        assert state == "Deploying"

    @pytest.mark.asyncio
    async def test_scenario_set_timed(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"scenarioName": "Deploy", "currentState": "Deploying"}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        await client.scenario("Deploy").set(
            "Deploying", transition_after_ms=5000, next_state="Deployed"
        )
        assert MockHandler.last_path == "/mockserver/scenario/Deploy"
        assert MockHandler.last_method == "PUT"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {
            "state": "Deploying",
            "transitionAfterMs": 5000,
            "nextState": "Deployed",
        }

    @pytest.mark.asyncio
    async def test_scenario_trigger(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"scenarioName": "Deploy", "currentState": "Failed"}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        result = await client.scenario("Deploy").trigger("Failed")
        assert MockHandler.last_path == "/mockserver/scenario/Deploy/trigger"
        assert MockHandler.last_method == "PUT"
        sent = json.loads(MockHandler.last_request_body)
        assert sent == {"newState": "Failed"}
        assert result["currentState"] == "Failed"

    @pytest.mark.asyncio
    async def test_scenarios_list(self, mock_server):
        MockHandler.response_body = json.dumps(
            {"scenarios": [{"scenarioName": "Deploy", "currentState": "Deploying"}]}
        )
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        scenarios = await client.scenarios()
        assert MockHandler.last_path == "/mockserver/scenario"
        assert MockHandler.last_method == "GET"
        assert scenarios == [{"scenarioName": "Deploy", "currentState": "Deploying"}]

    @pytest.mark.asyncio
    async def test_scenario_error(self, mock_server):
        MockHandler.response_status = 400
        MockHandler.response_body = '{"error": "bad"}'
        client = AsyncMockServerClient("127.0.0.1", mock_server)
        with pytest.raises(MockServerError, match="Scenario request failed"):
            await client.scenario("Deploy").set("Deploying")
