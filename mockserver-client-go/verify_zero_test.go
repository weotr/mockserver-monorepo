package mockserver

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClient_VerifyZeroInteractions_URLAndBody(t *testing.T) {
	var receivedPath, receivedMethod string
	var receivedBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedPath = r.URL.Path
		receivedMethod = r.Method
		receivedBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(202)
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	if err := client.VerifyZeroInteractions(); err != nil {
		t.Fatal(err)
	}

	if receivedMethod != "PUT" {
		t.Errorf("expected PUT, got %s", receivedMethod)
	}
	if receivedPath != "/mockserver/verify" {
		t.Errorf("expected path /mockserver/verify, got %s", receivedPath)
	}

	var m map[string]interface{}
	if err := json.Unmarshal(receivedBody, &m); err != nil {
		t.Fatalf("body not valid JSON: %v", err)
	}
	times, ok := m["times"].(map[string]interface{})
	if !ok {
		t.Fatalf("expected times object, got %v", m["times"])
	}
	// The crucial assertion: atMost must be present and explicitly 0.
	if v, present := times["atMost"]; !present || v != float64(0) {
		t.Errorf("expected atMost explicitly 0, got present=%v value=%v", present, v)
	}
	if _, present := times["atLeast"]; present {
		t.Errorf("expected atLeast to be omitted, got %v", times["atLeast"])
	}
}

func TestClient_VerifyZeroInteractions_406ReturnsError(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(406)
		w.Write([]byte("request found"))
	}))
	defer ts.Close()

	client := NewFromURL(ts.URL)
	err := client.VerifyZeroInteractions()
	if err == nil {
		t.Fatal("expected verification error for 406, got nil")
	}
	if _, ok := err.(*VerificationError); !ok {
		t.Errorf("expected *VerificationError, got %T", err)
	}
}

// Ensure an explicit AtMost(0) bound also serializes (regression guard for the
// omitempty -> custom MarshalJSON change).
func TestVerificationTimes_AtMostZeroSerializes(t *testing.T) {
	data, err := json.Marshal(AtMost(0))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	if v, present := m["atMost"]; !present || v != float64(0) {
		t.Errorf("expected atMost 0 to serialize, got present=%v value=%v", present, v)
	}
}

func TestVerificationTimes_AtLeastOmitsAtMost(t *testing.T) {
	data, err := json.Marshal(AtLeast(2))
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]interface{}
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	if m["atLeast"] != float64(2) {
		t.Errorf("expected atLeast 2, got %v", m["atLeast"])
	}
	if _, present := m["atMost"]; present {
		t.Errorf("expected atMost to be omitted, got %v", m["atMost"])
	}
}
