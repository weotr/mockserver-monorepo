package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestOpenAPIBuilder_JSON(t *testing.T) {
	exp := OpenAPI("https://example.com/openapi.json").
		OperationResponse("listPets", "200").
		OperationResponse("showPetById", "404").
		Build()

	data, err := json.Marshal(exp)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}

	if m["specUrlOrPayload"] != "https://example.com/openapi.json" {
		t.Errorf("expected specUrlOrPayload, got %v", m["specUrlOrPayload"])
	}
	ops, ok := m["operationsAndResponses"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected operationsAndResponses map, got %v", m["operationsAndResponses"])
	}
	if ops["listPets"] != "200" || ops["showPetById"] != "404" {
		t.Errorf("unexpected operationsAndResponses: %v", ops)
	}
}

func TestOpenAPIBuilder_OmitsEmptyOperations(t *testing.T) {
	exp := OpenAPI(`{"openapi":"3.0.0"}`).Build()
	data, err := json.Marshal(exp)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	if _, present := m["operationsAndResponses"]; present {
		t.Errorf("expected operationsAndResponses to be omitted, got %v", m["operationsAndResponses"])
	}
	if m["specUrlOrPayload"] != `{"openapi":"3.0.0"}` {
		t.Errorf("unexpected specUrlOrPayload: %v", m["specUrlOrPayload"])
	}
}

func TestClient_UpsertOpenAPI_URLAndBody(t *testing.T) {
	var receivedPath, receivedMethod string
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(201)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.UpsertOpenAPI(OpenAPI("https://example.com/openapi.json").OperationResponse("listPets", "200"))
	if err != nil {
		t.Fatal(err)
	}

	if receivedMethod != "PUT" {
		t.Errorf("expected PUT, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/openapi" {
		t.Errorf("expected path /mockserver/openapi, got %s", receivedPath)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(receivedBody, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	if m["specUrlOrPayload"] != "https://example.com/openapi.json" {
		t.Errorf("unexpected body: %v", m)
	}
}

func TestClient_UpsertOpenAPI_ErrorStatus(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(400)
		w.Write([]byte("invalid spec"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.UpsertOpenAPI(OpenAPI("not-a-spec"))
	if err == nil {
		t.Fatal("expected error for 400 status, got nil")
	}
}
