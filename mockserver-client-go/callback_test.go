package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// --- Class callback serialization tests ---

func TestRespondWithClassCallback_Serialization(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.
		When(Request().Method("GET").Path("/callback")).
		RespondWithClassCallback("com.example.MyResponseCallback")
	if err != nil {
		t.Fatal(err)
	}

	var payload []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &payload); err != nil {
		t.Fatalf("unmarshal payload: %v (body=%s)", err, string(receivedBody))
	}
	if len(payload) != 1 {
		t.Fatalf("expected 1 expectation, got %d", len(payload))
	}
	cb, ok := payload[0]["httpResponseClassCallback"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected httpResponseClassCallback object, got %#v", payload[0]["httpResponseClassCallback"])
	}
	if cb["callbackClass"] != "com.example.MyResponseCallback" {
		t.Errorf("expected callbackClass com.example.MyResponseCallback, got %v", cb["callbackClass"])
	}
	// The forward callback field must not leak in.
	if _, present := payload[0]["httpForwardClassCallback"]; present {
		t.Errorf("did not expect httpForwardClassCallback to be present")
	}
}

func TestForwardWithClassCallback_Serialization(t *testing.T) {
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
		w.Write([]byte("[]"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	_, err := client.
		When(Request().Method("POST").Path("/proxy")).
		ForwardWithClassCallback("com.example.MyForwardCallback")
	if err != nil {
		t.Fatal(err)
	}

	var payload []map[string]interface{}
	if err := json.Unmarshal(receivedBody, &payload); err != nil {
		t.Fatal(err)
	}
	cb, ok := payload[0]["httpForwardClassCallback"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected httpForwardClassCallback object, got %#v", payload[0]["httpForwardClassCallback"])
	}
	if cb["callbackClass"] != "com.example.MyForwardCallback" {
		t.Errorf("expected callbackClass com.example.MyForwardCallback, got %v", cb["callbackClass"])
	}
}

func TestHttpClassCallback_DelayAndPrimary_Serialization(t *testing.T) {
	primary := true
	cb := HttpClassCallback{
		CallbackClass: "com.example.C",
		Delay:         &Delay{TimeUnit: "MILLISECONDS", Value: 250},
		Primary:       &primary,
	}
	raw, err := json.Marshal(cb)
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]interface{}
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got["callbackClass"] != "com.example.C" {
		t.Errorf("callbackClass mismatch: %v", got["callbackClass"])
	}
	if _, ok := got["delay"]; !ok {
		t.Errorf("expected delay to be serialized")
	}
	if got["primary"] != true {
		t.Errorf("expected primary true, got %v", got["primary"])
	}

	// Omitted optional fields must not appear.
	bare, _ := json.Marshal(HttpClassCallback{CallbackClass: "x"})
	if strings.Contains(string(bare), "delay") || strings.Contains(string(bare), "primary") {
		t.Errorf("bare class callback should omit delay/primary, got %s", string(bare))
	}
}

// --- Object callback expectation serialization ---

func TestMockWithCallback_RegistersObjectCallbackExpectation(t *testing.T) {
	wsConnected := make(chan struct{})
	var upsertBody []byte
	upsertReceived := make(chan struct{}, 1)

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/_mockserver_callback_websocket") {
			upgrader := websocket.Upgrader{}
			conn, err := upgrader.Upgrade(w, r, nil)
			if err != nil {
				return
			}
			// Send the client-id handshake.
			env := wsEnvelope{
				Type:  "org.mockserver.serialization.model.WebSocketClientIdDTO",
				Value: `{"clientId":"ws-client-xyz"}`,
			}
			b, _ := json.Marshal(env)
			_ = conn.WriteMessage(websocket.TextMessage, b)
			close(wsConnected)
			// Keep the connection open.
			for {
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
			}
		}
		if strings.HasSuffix(r.URL.Path, "/mockserver/expectation") {
			upsertBody, _ = io.ReadAll(r.Body)
			w.WriteHeader(201)
			w.Write([]byte("[]"))
			upsertReceived <- struct{}{}
			return
		}
		w.WriteHeader(200)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	defer client.CloseBreakpointWebSocket()

	_, err := client.MockWithCallback(
		Request().Method("GET").Path("/dynamic"),
		func(req *HttpRequest) *HttpResponse {
			return Response().StatusCode(200).Body("hello " + req.Path).BuildPtr()
		},
		WithTimes(Once()),
	)
	if err != nil {
		t.Fatal(err)
	}

	select {
	case <-wsConnected:
	case <-time.After(2 * time.Second):
		t.Fatal("websocket never connected")
	}
	select {
	case <-upsertReceived:
	case <-time.After(2 * time.Second):
		t.Fatal("expectation upsert never received")
	}

	var payload []map[string]interface{}
	if err := json.Unmarshal(upsertBody, &payload); err != nil {
		t.Fatalf("unmarshal upsert body: %v (%s)", err, string(upsertBody))
	}
	cb, ok := payload[0]["httpResponseObjectCallback"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected httpResponseObjectCallback object, got %#v", payload[0])
	}
	if cb["clientId"] != "ws-client-xyz" {
		t.Errorf("expected clientId ws-client-xyz, got %v", cb["clientId"])
	}
	if cb["responseCallback"] != true {
		t.Errorf("expected responseCallback true, got %v", cb["responseCallback"])
	}
}

// --- Object callback reply-envelope tests (stub the WS) ---

// captureWS sets up a stub WebSocket server, dials it, attaches the connection to
// ws, and returns a channel of messages the client sends back to the server.
func captureWS(t *testing.T, ws *breakpointWSClient) (chan string, func()) {
	t.Helper()
	sent := make(chan string, 10)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upgrader := websocket.Upgrader{}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			sent <- string(msg)
		}
	}))
	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		srv.Close()
		t.Fatal(err)
	}
	ws.conn = conn
	cleanup := func() {
		conn.Close()
		srv.Close()
	}
	return sent, cleanup
}

func TestObjectResponseCallback_ReplyEnvelope(t *testing.T) {
	ws := newBreakpointWSClient()
	ws.objectResponseHandler = func(req *HttpRequest) *HttpResponse {
		// Derive the response from the request path.
		return Response().StatusCode(201).Body("path=" + req.Path).BuildPtr()
	}

	sent, cleanup := captureWS(t, ws)
	defer cleanup()

	// Object-callback request frame: has a WebSocketCorrelationId but NO
	// X-MockServer-BreakpointId.
	request := map[string]interface{}{
		"method": "GET",
		"path":   "/dynamic",
		"headers": map[string]interface{}{
			"WebSocketCorrelationId": []interface{}{"corr-obj-1"},
		},
	}
	requestJSON, _ := json.Marshal(request)
	ws.handleRequest(string(requestJSON))

	select {
	case msg := <-sent:
		var env wsEnvelope
		if err := json.Unmarshal([]byte(msg), &env); err != nil {
			t.Fatal(err)
		}
		if env.Type != "org.mockserver.model.HttpResponse" {
			t.Errorf("expected HttpResponse envelope type, got %s", env.Type)
		}
		var resp map[string]interface{}
		if err := json.Unmarshal([]byte(env.Value), &resp); err != nil {
			t.Fatal(err)
		}
		if resp["statusCode"] != float64(201) {
			t.Errorf("expected statusCode 201, got %v", resp["statusCode"])
		}
		if resp["body"] != "path=/dynamic" {
			t.Errorf("expected derived body 'path=/dynamic', got %v", resp["body"])
		}
		// The correlation id MUST be echoed so the server can route the reply.
		if corr := extractHeader(resp, "WebSocketCorrelationId"); corr != "corr-obj-1" {
			t.Errorf("expected correlation id corr-obj-1 echoed, got %q", corr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for object-callback reply")
	}
}

func TestObjectResponseCallback_NilHandlerResult(t *testing.T) {
	ws := newBreakpointWSClient()
	ws.objectResponseHandler = func(req *HttpRequest) *HttpResponse { return nil }

	sent, cleanup := captureWS(t, ws)
	defer cleanup()

	request := map[string]interface{}{
		"path": "/x",
		"headers": map[string]interface{}{
			"WebSocketCorrelationId": []interface{}{"corr-nil"},
		},
	}
	requestJSON, _ := json.Marshal(request)
	ws.handleRequest(string(requestJSON))

	select {
	case msg := <-sent:
		var env wsEnvelope
		json.Unmarshal([]byte(msg), &env)
		if env.Type != "org.mockserver.model.HttpResponse" {
			t.Errorf("expected HttpResponse envelope, got %s", env.Type)
		}
		var resp map[string]interface{}
		json.Unmarshal([]byte(env.Value), &resp)
		if corr := extractHeader(resp, "WebSocketCorrelationId"); corr != "corr-nil" {
			t.Errorf("expected correlation id echoed even for nil result, got %q", corr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout")
	}
}

func TestObjectForwardCallback_ReplyEnvelope(t *testing.T) {
	ws := newBreakpointWSClient()
	ws.objectForwardHandler = func(req *HttpRequest) *HttpRequest {
		return Request().Method("GET").Path("/forwarded").BuildPtr()
	}

	sent, cleanup := captureWS(t, ws)
	defer cleanup()

	request := map[string]interface{}{
		"method": "GET",
		"path":   "/orig",
		"headers": map[string]interface{}{
			"WebSocketCorrelationId": []interface{}{"corr-fwd"},
		},
	}
	requestJSON, _ := json.Marshal(request)
	ws.handleRequest(string(requestJSON))

	select {
	case msg := <-sent:
		var env wsEnvelope
		json.Unmarshal([]byte(msg), &env)
		if env.Type != "org.mockserver.model.HttpRequest" {
			t.Errorf("expected HttpRequest envelope type for forward callback, got %s", env.Type)
		}
		var fwd map[string]interface{}
		json.Unmarshal([]byte(env.Value), &fwd)
		if fwd["path"] != "/forwarded" {
			t.Errorf("expected forwarded path /forwarded, got %v", fwd["path"])
		}
		if corr := extractHeader(fwd, "WebSocketCorrelationId"); corr != "corr-fwd" {
			t.Errorf("expected correlation id corr-fwd echoed, got %q", corr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timeout")
	}
}

// A breakpoint frame (one carrying X-MockServer-BreakpointId) must NOT be routed
// to the object-callback handler even if one is registered.
func TestObjectCallback_DoesNotHijackBreakpointFrames(t *testing.T) {
	ws := newBreakpointWSClient()
	objectCalled := false
	ws.objectResponseHandler = func(req *HttpRequest) *HttpResponse {
		objectCalled = true
		return nil
	}
	bpCalled := false
	ws.requestHandlers["bp-7"] = func(req map[string]interface{}) interface{} {
		bpCalled = true
		return req
	}

	sent, cleanup := captureWS(t, ws)
	defer cleanup()

	request := map[string]interface{}{
		"path": "/test",
		"headers": map[string]interface{}{
			"WebSocketCorrelationId":    []interface{}{"corr-bp"},
			"X-MockServer-BreakpointId": []interface{}{"bp-7"},
		},
	}
	requestJSON, _ := json.Marshal(request)
	ws.handleRequest(string(requestJSON))

	select {
	case <-sent:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout")
	}
	if objectCalled {
		t.Error("object-callback handler must not be invoked for breakpoint frames")
	}
	if !bpCalled {
		t.Error("breakpoint request handler should have been invoked")
	}
}

func TestMockWithCallback_NilArgs(t *testing.T) {
	client := NewFromURL("http://localhost:1")
	if _, err := client.MockWithCallback(nil, func(*HttpRequest) *HttpResponse { return nil }); err == nil {
		t.Error("expected error for nil request matcher")
	}
	if _, err := client.MockWithCallback(Request().Path("/x"), nil); err == nil {
		t.Error("expected error for nil handler")
	}
}
