package mockserver

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

// TestClient_UploadGrpcDescriptor_WireShape asserts the method, path,
// Content-Type, and that the descriptor bytes are sent verbatim (raw, not
// base64-encoded), including arbitrary non-UTF8 bytes.
func TestClient_UploadGrpcDescriptor_WireShape(t *testing.T) {
	tests := []struct {
		name           string
		descriptor     []byte
		serverStatus   int
		expectErr      bool
		expectBodySent bool
	}{
		{
			name:           "ascii descriptor bytes sent verbatim",
			descriptor:     []byte("descriptor-set-contents"),
			serverStatus:   http.StatusCreated,
			expectBodySent: true,
		},
		{
			name: "non-UTF8 binary bytes sent verbatim",
			// 0xFF 0xFE 0x00 0x80 is not valid UTF-8 and includes a NUL byte,
			// proving the body is raw bytes and not text/base64.
			descriptor:     []byte{0x00, 0xFF, 0xFE, 0x80, 0x0A, 0x7F, 0x42, 0x00},
			serverStatus:   http.StatusCreated,
			expectBodySent: true,
		},
		{
			name:         "server error surfaces as error",
			descriptor:   []byte{0x08, 0x96, 0x01},
			serverStatus: http.StatusBadRequest,
			expectErr:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var (
				gotMethod      string
				gotPath        string
				gotContentType string
				gotBody        []byte
			)
			ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotMethod = r.Method
				gotPath = r.URL.Path
				gotContentType = r.Header.Get("Content-Type")
				gotBody, _ = io.ReadAll(r.Body)
				w.WriteHeader(tt.serverStatus)
			}))
			defer ts.Close()

			client := NewFromURL(ts.URL)
			err := client.UploadGrpcDescriptor(tt.descriptor)

			if tt.expectErr {
				if err == nil {
					t.Fatalf("expected error for status %d, got nil", tt.serverStatus)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if gotMethod != http.MethodPut {
				t.Errorf("method: got %q, want PUT", gotMethod)
			}
			if gotPath != "/mockserver/grpc/descriptors" {
				t.Errorf("path: got %q, want /mockserver/grpc/descriptors", gotPath)
			}
			if gotContentType != "application/octet-stream" {
				t.Errorf("content-type: got %q, want application/octet-stream", gotContentType)
			}
			if !bytes.Equal(gotBody, tt.descriptor) {
				t.Errorf("body not sent verbatim:\n got  %#v\n want %#v", gotBody, tt.descriptor)
			}
		})
	}
}

// TestClient_UploadGrpcDescriptor_EmptyRejectedClientSide asserts the client
// rejects empty input without making an HTTP call.
func TestClient_UploadGrpcDescriptor_EmptyRejectedClientSide(t *testing.T) {
	called := false
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusCreated)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	for _, empty := range [][]byte{nil, {}} {
		if err := client.UploadGrpcDescriptor(empty); err == nil {
			t.Errorf("expected error for empty descriptor %#v, got nil", empty)
		}
	}
	if called {
		t.Error("expected no HTTP call for empty descriptor")
	}
}

// TestClient_RetrieveGrpcServices_WireShapeAndParsing asserts the method/path
// (no body) and that the JSON service array is parsed into typed structs.
func TestClient_RetrieveGrpcServices_WireShapeAndParsing(t *testing.T) {
	tests := []struct {
		name         string
		serverStatus int
		serverBody   string
		want         []GrpcService
		expectErr    bool
	}{
		{
			name:         "parses services and methods",
			serverStatus: http.StatusOK,
			serverBody: `[
				{
					"name": "example.Greeter",
					"methods": [
						{"name":"SayHello","inputType":"example.HelloRequest","outputType":"example.HelloReply","clientStreaming":false,"serverStreaming":false},
						{"name":"Chat","inputType":"example.ChatMsg","outputType":"example.ChatMsg","clientStreaming":true,"serverStreaming":true}
					]
				}
			]`,
			want: []GrpcService{
				{
					Name: "example.Greeter",
					Methods: []GrpcMethod{
						{Name: "SayHello", InputType: "example.HelloRequest", OutputType: "example.HelloReply", ClientStreaming: false, ServerStreaming: false},
						{Name: "Chat", InputType: "example.ChatMsg", OutputType: "example.ChatMsg", ClientStreaming: true, ServerStreaming: true},
					},
				},
			},
		},
		{
			name:         "empty body yields nil slice",
			serverStatus: http.StatusOK,
			serverBody:   "",
			want:         nil,
		},
		{
			name:         "empty json array yields empty slice",
			serverStatus: http.StatusOK,
			serverBody:   "[]",
			want:         []GrpcService{},
		},
		{
			name:         "server error surfaces as error",
			serverStatus: http.StatusBadRequest,
			serverBody:   "boom",
			expectErr:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var (
				gotMethod string
				gotPath   string
				gotBody   []byte
			)
			ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotMethod = r.Method
				gotPath = r.URL.Path
				gotBody, _ = io.ReadAll(r.Body)
				w.WriteHeader(tt.serverStatus)
				_, _ = w.Write([]byte(tt.serverBody))
			}))
			defer ts.Close()

			client := NewFromURL(ts.URL)
			got, err := client.RetrieveGrpcServices()

			if tt.expectErr {
				if err == nil {
					t.Fatalf("expected error for status %d, got nil", tt.serverStatus)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if gotMethod != http.MethodPut {
				t.Errorf("method: got %q, want PUT", gotMethod)
			}
			if gotPath != "/mockserver/grpc/services" {
				t.Errorf("path: got %q, want /mockserver/grpc/services", gotPath)
			}
			if len(gotBody) != 0 {
				t.Errorf("expected no request body, got %q", string(gotBody))
			}
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("services:\n got  %#v\n want %#v", got, tt.want)
			}
		})
	}
}

// TestClient_ClearGrpcDescriptors_WireShape asserts the method/path (no body).
func TestClient_ClearGrpcDescriptors_WireShape(t *testing.T) {
	tests := []struct {
		name         string
		serverStatus int
		expectErr    bool
	}{
		{name: "ok", serverStatus: http.StatusOK},
		{name: "server error surfaces as error", serverStatus: http.StatusInternalServerError, expectErr: true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var (
				gotMethod string
				gotPath   string
				gotBody   []byte
			)
			ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotMethod = r.Method
				gotPath = r.URL.Path
				gotBody, _ = io.ReadAll(r.Body)
				w.WriteHeader(tt.serverStatus)
			}))
			defer ts.Close()

			client := NewFromURL(ts.URL)
			err := client.ClearGrpcDescriptors()

			if tt.expectErr {
				if err == nil {
					t.Fatalf("expected error for status %d, got nil", tt.serverStatus)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if gotMethod != http.MethodPut {
				t.Errorf("method: got %q, want PUT", gotMethod)
			}
			if gotPath != "/mockserver/grpc/clear" {
				t.Errorf("path: got %q, want /mockserver/grpc/clear", gotPath)
			}
			if len(gotBody) != 0 {
				t.Errorf("expected no request body, got %q", string(gotBody))
			}
		})
	}
}
