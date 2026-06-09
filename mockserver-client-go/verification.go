package mockserver

// VerificationTimes controls how many times a request should have been received.
type VerificationTimes struct {
	AtLeast int `json:"atLeast,omitempty"`
	AtMost  int `json:"atMost,omitempty"`
}

// AtLeast returns a VerificationTimes requiring at least n matches.
func AtLeast(n int) *VerificationTimes {
	return &VerificationTimes{AtLeast: n}
}

// AtMost returns a VerificationTimes requiring at most n matches.
func AtMost(n int) *VerificationTimes {
	return &VerificationTimes{AtMost: n}
}

// Between returns a VerificationTimes requiring between min and max matches.
func Between(min, max int) *VerificationTimes {
	return &VerificationTimes{AtLeast: min, AtMost: max}
}

// ExactlyTimes returns a VerificationTimes requiring exactly n matches.
func ExactlyTimes(n int) *VerificationTimes {
	return &VerificationTimes{AtLeast: n, AtMost: n}
}

// verification is the internal representation sent to the verify endpoint.
type verification struct {
	HttpRequest *HttpRequest       `json:"httpRequest"`
	Times       *VerificationTimes `json:"times,omitempty"`
}

// verificationSequence is the internal representation sent to the verifySequence endpoint.
type verificationSequence struct {
	HttpRequests []HttpRequest `json:"httpRequests"`
}
