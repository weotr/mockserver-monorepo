from __future__ import annotations

import ssl
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest.mock import MagicMock, patch

import pytest

from mockserver.async_client import AsyncMockServerClient
from mockserver.client import MockServerClient


class AuthCaptureHandler(BaseHTTPRequestHandler):
    last_authorization = None
    response_body = b"[]"

    def _record_and_respond(self):
        AuthCaptureHandler.last_authorization = self.headers.get("Authorization")
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length > 0:
            self.rfile.read(content_length)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(AuthCaptureHandler.response_body)

    def do_PUT(self):
        self._record_and_respond()

    def do_GET(self):
        self._record_and_respond()

    def log_message(self, fmt, *args):
        pass


@pytest.fixture
def auth_server():
    server = HTTPServer(("127.0.0.1", 0), AuthCaptureHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever)
    thread.daemon = True
    thread.start()
    AuthCaptureHandler.last_authorization = None
    AuthCaptureHandler.response_body = b"[]"
    yield port
    server.shutdown()


# ---------------------------------------------------------------------------
# Control-plane bearer token
# ---------------------------------------------------------------------------


class TestControlPlaneBearerToken:
    @pytest.mark.asyncio
    async def test_static_bearer_token_header_attached(self, auth_server):
        client = AsyncMockServerClient(
            "127.0.0.1", auth_server, control_plane_bearer_token="my-token"
        )
        await client.reset()
        assert AuthCaptureHandler.last_authorization == "Bearer my-token"

    @pytest.mark.asyncio
    async def test_no_bearer_header_when_not_configured(self, auth_server):
        client = AsyncMockServerClient("127.0.0.1", auth_server)
        await client.reset()
        assert AuthCaptureHandler.last_authorization is None

    @pytest.mark.asyncio
    async def test_bearer_supplier_evaluated_per_request(self, auth_server):
        tokens = iter(["token-1", "token-2"])
        client = AsyncMockServerClient(
            "127.0.0.1",
            auth_server,
            control_plane_bearer_token_supplier=lambda: next(tokens),
        )

        await client.reset()
        assert AuthCaptureHandler.last_authorization == "Bearer token-1"

        await client.reset()
        assert AuthCaptureHandler.last_authorization == "Bearer token-2"

    @pytest.mark.asyncio
    async def test_supplier_takes_precedence_over_static_token(self, auth_server):
        client = AsyncMockServerClient(
            "127.0.0.1",
            auth_server,
            control_plane_bearer_token="static",
            control_plane_bearer_token_supplier=lambda: "dynamic",
        )
        await client.reset()
        assert AuthCaptureHandler.last_authorization == "Bearer dynamic"

    @pytest.mark.asyncio
    async def test_bearer_attached_on_scenario_control_plane_request(self, auth_server):
        AuthCaptureHandler.response_body = b'{"scenarios": []}'
        client = AsyncMockServerClient(
            "127.0.0.1", auth_server, control_plane_bearer_token="scenario-token"
        )
        await client.scenarios()
        assert AuthCaptureHandler.last_authorization == "Bearer scenario-token"

    def test_sync_client_attaches_bearer_token(self, auth_server):
        client = MockServerClient(
            "127.0.0.1", auth_server, control_plane_bearer_token="sync-token"
        )
        try:
            client.reset()
            assert AuthCaptureHandler.last_authorization == "Bearer sync-token"
        finally:
            client.close()


# ---------------------------------------------------------------------------
# mTLS client certificate / key
# ---------------------------------------------------------------------------


class TestClientCertMtls:
    def test_client_cert_kwargs_stored(self):
        client = AsyncMockServerClient(
            "localhost",
            1080,
            secure=False,
            client_cert_path="/certs/client.pem",
            client_key_path="/certs/client.key",
        )
        assert client._client_cert_path == "/certs/client.pem"
        assert client._client_key_path == "/certs/client.key"

    def test_client_cert_loaded_into_ssl_context_when_secure(self):
        fake_ctx = MagicMock(spec=ssl.SSLContext)
        with patch("ssl.create_default_context", return_value=fake_ctx):
            AsyncMockServerClient(
                "localhost",
                1080,
                secure=True,
                client_cert_path="/certs/client.pem",
                client_key_path="/certs/client.key",
            )
        fake_ctx.load_cert_chain.assert_called_once_with(
            certfile="/certs/client.pem", keyfile="/certs/client.key"
        )

    def test_client_cert_loaded_alongside_ca_cert(self):
        fake_ctx = MagicMock(spec=ssl.SSLContext)
        with patch("ssl.create_default_context", return_value=fake_ctx):
            AsyncMockServerClient(
                "localhost",
                1080,
                secure=True,
                ca_cert_path="/certs/ca.pem",
                client_cert_path="/certs/client.pem",
                client_key_path="/certs/client.key",
            )
        fake_ctx.load_verify_locations.assert_called_once_with("/certs/ca.pem")
        fake_ctx.load_cert_chain.assert_called_once_with(
            certfile="/certs/client.pem", keyfile="/certs/client.key"
        )

    def test_no_cert_chain_loaded_when_not_configured(self):
        fake_ctx = MagicMock(spec=ssl.SSLContext)
        with patch("ssl.create_default_context", return_value=fake_ctx):
            AsyncMockServerClient("localhost", 1080, secure=True)
        fake_ctx.load_cert_chain.assert_not_called()

    def test_no_ssl_context_built_for_plain_http(self):
        client = AsyncMockServerClient(
            "localhost",
            1080,
            secure=False,
            client_cert_path="/certs/client.pem",
            client_key_path="/certs/client.key",
        )
        # No TLS => no SSLContext, even though cert paths are provided.
        assert client._ssl_context is None

    def test_sync_client_forwards_client_cert_kwargs(self):
        with patch("mockserver.client.AsyncMockServerClient") as mock_async:
            MockServerClient(
                "localhost",
                1080,
                secure=True,
                client_cert_path="/certs/client.pem",
                client_key_path="/certs/client.key",
            )
        _, kwargs = mock_async.call_args
        assert kwargs["client_cert_path"] == "/certs/client.pem"
        assert kwargs["client_key_path"] == "/certs/client.key"

    def test_sync_client_forwards_bearer_kwargs(self):
        supplier = lambda: "tok"
        with patch("mockserver.client.AsyncMockServerClient") as mock_async:
            MockServerClient(
                "localhost",
                1080,
                control_plane_bearer_token="static",
                control_plane_bearer_token_supplier=supplier,
            )
        _, kwargs = mock_async.call_args
        assert kwargs["control_plane_bearer_token"] == "static"
        assert kwargs["control_plane_bearer_token_supplier"] is supplier
