package mockserver

import "encoding/json"

// VerificationTimes controls how many times a request should have been received.
//
// The atLeastSet/atMostSet flags distinguish an explicit bound of 0 (which must
// be serialized) from an unset bound (which must be omitted). This matters for
// VerifyZeroInteractions, where atMost is explicitly 0.
type VerificationTimes struct {
	AtLeast int `json:"-"`
	AtMost  int `json:"-"`

	atLeastSet bool
	atMostSet  bool
}

// MarshalJSON serializes only the bounds that were explicitly set, so that an
// explicit zero bound is preserved while an unset bound is omitted.
func (v VerificationTimes) MarshalJSON() ([]byte, error) {
	m := make(map[string]int, 2)
	if v.atLeastSet {
		m["atLeast"] = v.AtLeast
	}
	if v.atMostSet {
		m["atMost"] = v.AtMost
	}
	return json.Marshal(m)
}

// AtLeast returns a VerificationTimes requiring at least n matches.
func AtLeast(n int) *VerificationTimes {
	return &VerificationTimes{AtLeast: n, atLeastSet: true}
}

// AtMost returns a VerificationTimes requiring at most n matches.
func AtMost(n int) *VerificationTimes {
	return &VerificationTimes{AtMost: n, atMostSet: true}
}

// Between returns a VerificationTimes requiring between min and max matches.
func Between(min, max int) *VerificationTimes {
	return &VerificationTimes{AtLeast: min, AtMost: max, atLeastSet: true, atMostSet: true}
}

// ExactlyTimes returns a VerificationTimes requiring exactly n matches.
func ExactlyTimes(n int) *VerificationTimes {
	return &VerificationTimes{AtLeast: n, AtMost: n, atLeastSet: true, atMostSet: true}
}

// verification is the internal representation sent to the verify endpoint.
type verification struct {
	HttpRequest  *HttpRequest       `json:"httpRequest,omitempty"`
	HttpResponse *HttpResponse      `json:"httpResponse,omitempty"`
	Times        *VerificationTimes `json:"times,omitempty"`
}

// verificationSequence is the internal representation sent to the verifySequence endpoint.
type verificationSequence struct {
	HttpRequests  []HttpRequest  `json:"httpRequests,omitempty"`
	HttpResponses []HttpResponse `json:"httpResponses,omitempty"`
}
