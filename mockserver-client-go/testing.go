package mockserver

import "testing"

// TestingT is the subset of *testing.T (and *testing.B) used by the test
// helpers below. Accepting an interface keeps the helpers usable from tests,
// benchmarks, and custom harnesses without importing the testing package's
// concrete types everywhere.
type TestingT interface {
	Cleanup(func())
	Helper()
	Errorf(format string, args ...interface{})
}

// MockServerT wraps an existing *Client and registers a t.Cleanup that calls
// Reset when the test (and any subtests) finish, so recorded requests,
// expectations and logs do not leak between tests.
//
// Typical use:
//
//	func TestSomething(t *testing.T) {
//	    client := mockserver.MockServerT(t, mockserver.New("localhost", 1080))
//	    // ... register expectations, make requests ...
//	    // client.Reset() runs automatically when the test ends.
//	}
//
// The returned value is the same *Client that was passed in, so it can be used
// fluently. If Reset fails during cleanup the error is reported via t.Errorf
// rather than ignored.
func MockServerT(t TestingT, client *Client) *Client {
	t.Helper()
	t.Cleanup(func() {
		if err := client.Reset(); err != nil {
			t.Errorf("mockserver: cleanup reset failed: %v", err)
		}
	})
	return client
}

// NewMockServerT constructs a new *Client for host:port and registers the same
// automatic Reset cleanup as MockServerT. It is a convenience wrapper for the
// common case where a fresh client is created per test.
//
//	func TestSomething(t *testing.T) {
//	    client := mockserver.NewMockServerT(t, "localhost", 1080)
//	    // ...
//	}
func NewMockServerT(t TestingT, host string, port int, opts ...Option) *Client {
	t.Helper()
	return MockServerT(t, New(host, port, opts...))
}

// compile-time assertion that *testing.T satisfies TestingT.
var _ TestingT = (*testing.T)(nil)
