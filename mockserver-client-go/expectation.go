package mockserver

// Expectation represents a MockServer expectation (request matcher + action).
type Expectation struct {
	ID           string       `json:"id,omitempty"`
	Priority     int          `json:"priority,omitempty"`
	HttpRequest  *HttpRequest `json:"httpRequest,omitempty"`
	HttpResponse *HttpResponse `json:"httpResponse,omitempty"`
	HttpForward  *HttpForward  `json:"httpForward,omitempty"`
	HttpError    *HttpError    `json:"httpError,omitempty"`
	Times        *Times        `json:"times,omitempty"`
	TimeToLive   *TimeToLive   `json:"timeToLive,omitempty"`
}

// Times controls how many times an expectation can be matched.
type Times struct {
	RemainingTimes int  `json:"remainingTimes,omitempty"`
	Unlimited      bool `json:"unlimited"`
}

// TimeToLive controls how long an expectation remains active.
type TimeToLive struct {
	TimeUnit   string `json:"timeUnit,omitempty"`
	TimeToLive int    `json:"timeToLive,omitempty"`
	Unlimited  bool   `json:"unlimited"`
}

// Once returns a Times that matches exactly once.
func Once() *Times {
	return &Times{RemainingTimes: 1, Unlimited: false}
}

// Exactly returns a Times that matches exactly n times.
func Exactly(n int) *Times {
	return &Times{RemainingTimes: n, Unlimited: false}
}

// Unlimited returns a Times that matches unlimited times.
func Unlimited() *Times {
	return &Times{Unlimited: true}
}

// TTL returns a TimeToLive with the given duration.
func TTL(timeUnit string, value int) *TimeToLive {
	return &TimeToLive{TimeUnit: timeUnit, TimeToLive: value, Unlimited: false}
}

// UnlimitedTTL returns an unlimited TimeToLive.
func UnlimitedTTL() *TimeToLive {
	return &TimeToLive{Unlimited: true}
}
