package mockserver

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// fakeT records cleanup functions and errors so the helper's behaviour can be
// asserted without a real *testing.T.
type fakeT struct {
	cleanups []func()
	errored  bool
}

func (f *fakeT) Cleanup(fn func())             { f.cleanups = append(f.cleanups, fn) }
func (f *fakeT) Helper()                       {}
func (f *fakeT) Errorf(string, ...interface{}) { f.errored = true }
func (f *fakeT) runCleanups() {
	// run in LIFO order, matching testing.T semantics
	for i := len(f.cleanups) - 1; i >= 0; i-- {
		f.cleanups[i]()
	}
}

func TestMockServerT_ResetsOnCleanup(t *testing.T) {
	var resetPath string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		resetPath = r.URL.Path
		w.WriteHeader(200)
	}))
	defer ts.Close()

	ft := &fakeT{}
	client := MockServerT(ft, NewFromURL(ts.URL))
	if client == nil {
		t.Fatal("expected MockServerT to return the client")
	}
	if resetPath != "" {
		t.Fatalf("expected no reset before cleanup, got %q", resetPath)
	}

	ft.runCleanups()

	if resetPath != "/mockserver/reset" {
		t.Errorf("expected reset path /mockserver/reset, got %q", resetPath)
	}
	if ft.errored {
		t.Error("expected no error reported on successful reset")
	}
}

func TestMockServerT_ReportsResetFailure(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
	}))
	defer ts.Close()

	ft := &fakeT{}
	MockServerT(ft, NewFromURL(ts.URL))
	ft.runCleanups()

	if !ft.errored {
		t.Error("expected error reported when reset fails")
	}
}
