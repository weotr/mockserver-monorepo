package mockserver

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// This file adds the SRE control-plane operations: load scenarios (load
// generation / SLI production), service-scoped HTTP chaos, SLO verdicts,
// preemption (cordon/drain) simulation, and scheduled multi-stage chaos
// experiments. Each operation mirrors the MockServer OpenAPI contract
// (jekyll-www.mock-server.com/mockserver-openapi.yaml).
//
// Several of these features are off by default on the server and return an
// HTTP 403 (or 400 for SLO) until the corresponding configuration flag is
// enabled. Those statuses are surfaced as clear, dedicated errors so callers
// can distinguish "feature disabled" from a malformed request.

// FeatureDisabledError indicates the requested SRE feature is disabled on the
// server (e.g. loadGenerationEnabled=false or sloTrackingEnabled=false). It is
// returned when MockServer responds with the status code it uses to signal a
// disabled feature for that endpoint.
type FeatureDisabledError struct {
	Feature string
	Status  int
	Body    string
}

func (e *FeatureDisabledError) Error() string {
	return fmt.Sprintf("mockserver: %s is disabled on the server (status %d): %s", e.Feature, e.Status, e.Body)
}

// -----------------------------------------------------------------------------
// 1. Load scenario registry
//    Register/list/get/delete:
//      PUT    /mockserver/loadScenario          register (does not run)
//      GET    /mockserver/loadScenario          list all
//      GET    /mockserver/loadScenario/{name}   get one
//      DELETE /mockserver/loadScenario/{name}   remove one
//      DELETE /mockserver/loadScenario          clear all
//    Run control:
//      PUT    /mockserver/loadScenario/start     start one/many (requires loadGenerationEnabled)
//      PUT    /mockserver/loadScenario/stop      stop named / all
//    States: LOADED, PENDING, RUNNING, COMPLETED, STOPPED.
//    schemas LoadScenario, LoadProfile, LoadStep (OpenAPI).
// -----------------------------------------------------------------------------

// LoadStageType is the kind of a LoadStage. Wire values match schema
// LoadStageType (OpenAPI).
type LoadStageType string

const (
	// LoadStageVU is a closed-model stage: hold or ramp the number of concurrent
	// virtual users.
	LoadStageVU LoadStageType = "VU"
	// LoadStageRate is an open-model stage: hold or ramp a target arrival rate in
	// iterations per second.
	LoadStageRate LoadStageType = "RATE"
	// LoadStagePause drives no load for the stage duration.
	LoadStagePause LoadStageType = "PAUSE"
)

// RampCurve is the interpolation curve used to ramp a value across a stage.
// Wire values match schema RampCurve (OpenAPI). Only meaningful for ramp stages.
type RampCurve string

const (
	// RampLinear ramps with a constant slope.
	RampLinear RampCurve = "LINEAR"
	// RampExponential ramps with a steeper ease-in.
	RampExponential RampCurve = "EXPONENTIAL"
	// RampQuadratic ramps with an ease-in (slow then fast).
	RampQuadratic RampCurve = "QUADRATIC"
)

// LoadStage is one stage of a LoadProfile, run in sequence: it holds or ramps a
// setpoint for its DurationMillis. Wire keys match schema LoadStage (OpenAPI).
//
// A stage is one of three types:
//   - VU (closed model): hold Vus, or ramp from StartVus to EndVus along Curve.
//   - RATE (open model): hold Rate, or ramp from StartRate to EndRate
//     (iterations/second) along Curve, optionally capped at MaxVus virtual users.
//   - PAUSE: drive no load for DurationMillis.
//
// Pointer types are used for the optional numeric setpoints so that a meaningful
// zero (e.g. StartVus=0 at the bottom of a ramp, or Rate=0) is still emitted on
// the wire; omitempty drops them only when the pointer itself is nil.
type LoadStage struct {
	// Type is the stage kind: "VU", "RATE" or "PAUSE" (required).
	Type LoadStageType `json:"type,omitempty"`
	// DurationMillis is how long this stage runs in milliseconds (> 0, required).
	DurationMillis int64 `json:"durationMillis,omitempty"`
	// Curve is the ramp interpolation curve ("LINEAR", "EXPONENTIAL" or
	// "QUADRATIC"); only meaningful for ramp stages.
	Curve RampCurve `json:"curve,omitempty"`
	// Vus is the number of virtual users to hold for a VU hold stage.
	Vus *int `json:"vus,omitempty"`
	// StartVus is the virtual users at the start of a VU ramp.
	StartVus *int `json:"startVus,omitempty"`
	// EndVus is the virtual users at the end of a VU ramp.
	EndVus *int `json:"endVus,omitempty"`
	// Rate is the arrival rate (iterations/second) to hold for a RATE hold stage.
	Rate *float64 `json:"rate,omitempty"`
	// StartRate is the arrival rate at the start of a RATE ramp.
	StartRate *float64 `json:"startRate,omitempty"`
	// EndRate is the arrival rate at the end of a RATE ramp.
	EndRate *float64 `json:"endRate,omitempty"`
	// MaxVus optionally caps the auto-scaling VU pool that runs the started
	// iterations of a RATE stage (defaults to the global VU cap).
	MaxVus *int `json:"maxVus,omitempty"`
}

// LoadProfile is an ordered list of stages run in sequence, describing the load
// over time. Wire keys match schema LoadProfile (OpenAPI).
type LoadProfile struct {
	// Stages are the ordered stages run one after another (required).
	Stages []LoadStage `json:"stages"`
}

// ConstantVusStage builds a VU hold stage holding vus virtual users for
// durationMillis.
func ConstantVusStage(vus int, durationMillis int64) LoadStage {
	return LoadStage{Type: LoadStageVU, Vus: &vus, DurationMillis: durationMillis}
}

// RampVusStage builds a VU ramp stage ramping from startVus to endVus over
// durationMillis along curve.
func RampVusStage(startVus, endVus int, durationMillis int64, curve RampCurve) LoadStage {
	return LoadStage{Type: LoadStageVU, StartVus: &startVus, EndVus: &endVus, DurationMillis: durationMillis, Curve: curve}
}

// ConstantRateStage builds a RATE hold stage holding rate iterations/second for
// durationMillis.
func ConstantRateStage(rate float64, durationMillis int64) LoadStage {
	return LoadStage{Type: LoadStageRate, Rate: &rate, DurationMillis: durationMillis}
}

// RampRateStage builds a RATE ramp stage ramping from startRate to endRate
// (iterations/second) over durationMillis along curve.
func RampRateStage(startRate, endRate float64, durationMillis int64, curve RampCurve) LoadStage {
	return LoadStage{Type: LoadStageRate, StartRate: &startRate, EndRate: &endRate, DurationMillis: durationMillis, Curve: curve}
}

// PauseStage builds a PAUSE stage driving no load for durationMillis.
func PauseStage(durationMillis int64) LoadStage {
	return LoadStage{Type: LoadStagePause, DurationMillis: durationMillis}
}

// LoadStep is a single templated request step in a load scenario.
// Wire keys match schema LoadStep (OpenAPI line 4402). The step Request reuses
// the client's existing HttpRequest type (the schema $refs HttpRequest).
type LoadStep struct {
	// Request is the templated request fired for this step (required).
	Request *HttpRequest `json:"request,omitempty"`
	// ThinkTime is an optional inter-step pause (a Delay).
	ThinkTime *Delay `json:"thinkTime,omitempty"`
	// Name is an optional human label for the step (used as the metric label).
	Name string `json:"name,omitempty"`
	// Labels are optional step-level annotation labels, merged over the
	// scenario labels (step keys win).
	Labels map[string]string `json:"labels,omitempty"`
}

// LoadScenario is an API-driven load scenario: ordered templated steps driven
// at a target concurrency. Wire keys match schema LoadScenario (OpenAPI line
// 4427). Name, Profile and Steps are required by the server.
type LoadScenario struct {
	// Name is the human-readable scenario name (required).
	Name string `json:"name,omitempty"`
	// TemplateType is the template engine: "VELOCITY" (default) or "MUSTACHE".
	TemplateType string `json:"templateType,omitempty"`
	// MaxRequests is an optional hard cap on total requests dispatched.
	MaxRequests int `json:"maxRequests,omitempty"`
	// StartDelayMillis is an optional delay (milliseconds) applied before the
	// scenario starts driving load once started.
	StartDelayMillis int64 `json:"startDelayMillis,omitempty"`
	// Labels are optional scenario-level annotation labels.
	Labels map[string]string `json:"labels,omitempty"`
	// Profile is the ramp profile (required).
	Profile *LoadProfile `json:"profile,omitempty"`
	// Steps is the ordered list of request steps fired each iteration (required).
	Steps []LoadStep `json:"steps,omitempty"`
}

// LoadScenarioState is the lifecycle state of a registered load scenario.
// One of LOADED, PENDING, RUNNING, COMPLETED, STOPPED.
type LoadScenarioState = string

const (
	// LoadScenarioStateLoaded is a registered scenario not yet started.
	LoadScenarioStateLoaded LoadScenarioState = "LOADED"
	// LoadScenarioStatePending is a started scenario waiting on its start delay.
	LoadScenarioStatePending LoadScenarioState = "PENDING"
	// LoadScenarioStateRunning is a scenario actively driving load.
	LoadScenarioStateRunning LoadScenarioState = "RUNNING"
	// LoadScenarioStateCompleted is a scenario that ran its profile to completion.
	LoadScenarioStateCompleted LoadScenarioState = "COMPLETED"
	// LoadScenarioStateStopped is a scenario stopped before completion.
	LoadScenarioStateStopped LoadScenarioState = "STOPPED"
)

// LoadScenarioRef is a {name,state} pair returned when registering or starting
// a scenario.
type LoadScenarioRef struct {
	Name  string `json:"name,omitempty"`
	State string `json:"state,omitempty"`
}

// LoadScenarioStatus is the live status of a running (or most recently run)
// scenario. The server emits these fields FLAT on the listing entry (siblings
// of name/state/definition), present only once the scenario has run, so it is
// embedded into LoadScenarioEntry and carries no name/state of its own.
type LoadScenarioStatus struct {
	ElapsedMillis int64             `json:"elapsedMillis,omitempty"`
	CurrentVus    int               `json:"currentVus,omitempty"`
	StageIndex    int               `json:"stageIndex,omitempty"`
	StageType     string            `json:"stageType,omitempty"`
	CurrentTarget float64           `json:"currentTarget,omitempty"`
	RequestsSent  int64             `json:"requestsSent,omitempty"`
	Succeeded     int64             `json:"succeeded,omitempty"`
	Failed        int64             `json:"failed,omitempty"`
	P50Millis     float64           `json:"p50Millis,omitempty"`
	P95Millis     float64           `json:"p95Millis,omitempty"`
	P99Millis     float64           `json:"p99Millis,omitempty"`
	RunID         string            `json:"runId,omitempty"`
	StartedAt     int64             `json:"startedAt,omitempty"`
	EndedAt       int64             `json:"endedAt,omitempty"`
	Labels        map[string]string `json:"labels,omitempty"`
}

// LoadScenarioEntry is one entry of the registry listing: the scenario name,
// its current lifecycle state, the registered definition, and — when the
// scenario has run — its live status fields, which the server emits FLAT on the
// entry. The embedded LoadScenarioStatus promotes those flat fields onto the
// entry; the entry's own Name/State (depth 0) win over the embedded type.
type LoadScenarioEntry struct {
	Name             string        `json:"name,omitempty"`
	State            string        `json:"state,omitempty"`
	StartDelayMillis int64         `json:"startDelayMillis,omitempty"`
	Definition       *LoadScenario `json:"definition,omitempty"`
	LoadScenarioStatus
}

// LoadScenarioList is the response of GET /mockserver/loadScenario.
type LoadScenarioList struct {
	Scenarios []LoadScenarioEntry `json:"scenarios,omitempty"`
}

// LoadScenarioStartResult is the response of PUT /mockserver/loadScenario/start.
type LoadScenarioStartResult struct {
	Started []LoadScenarioRef `json:"started,omitempty"`
	Status  string            `json:"status,omitempty"`
}

// LoadScenarioStopResult is the response of PUT /mockserver/loadScenario/stop.
type LoadScenarioStopResult struct {
	Stopped []LoadScenarioRef `json:"stopped,omitempty"`
	Status  string            `json:"status,omitempty"`
}

// loadScenarioNames is the wire body shared by start (names) and stop (names/all).
type loadScenarioNames struct {
	Names []string `json:"names,omitempty"`
	All   bool     `json:"all,omitempty"`
}

// LoadScenario registers (loads) a scenario in the registry without running it
// (PUT /mockserver/loadScenario). Each scenario is keyed by its unique Name.
// Registration is allowed even when load generation is disabled on the server.
// Returns the registered scenario reference ({name,state}).
func (c *Client) LoadScenario(scenario LoadScenario) (*LoadScenarioRef, error) {
	body, err := json.Marshal(scenario)
	if err != nil {
		return nil, fmt.Errorf("mockserver: marshal load scenario: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/loadScenario", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: register load scenario failed (status %d): %s", statusCode, string(respBody))
	}
	var ref LoadScenarioRef
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &ref); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal load scenario ref: %w", err)
		}
	}
	return &ref, nil
}

// LoadScenarios lists all registered scenarios with their state, definition and
// optional live status (GET /mockserver/loadScenario).
func (c *Client) LoadScenarios() (*LoadScenarioList, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/loadScenario", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: list load scenarios failed (status %d): %s", statusCode, string(respBody))
	}
	var list LoadScenarioList
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &list); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal load scenario list: %w", err)
		}
	}
	return &list, nil
}

// GetLoadScenario retrieves a single registered scenario by name
// (GET /mockserver/loadScenario/{name}). Returns an error if no scenario with
// that name is registered (HTTP 404).
func (c *Client) GetLoadScenario(name string) (*LoadScenarioEntry, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/loadScenario/"+url.PathEscape(name), nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode == 404 {
		return nil, fmt.Errorf("mockserver: load scenario %q not found", name)
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: get load scenario failed (status %d): %s", statusCode, string(respBody))
	}
	var entry LoadScenarioEntry
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &entry); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal load scenario: %w", err)
		}
	}
	return &entry, nil
}

// DeleteLoadScenario removes a single registered scenario by name
// (DELETE /mockserver/loadScenario/{name}).
func (c *Client) DeleteLoadScenario(name string) error {
	respBody, statusCode, err := c.doRequest("DELETE", "/mockserver/loadScenario/"+url.PathEscape(name), nil, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: delete load scenario failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// ClearLoadScenarios removes all registered scenarios
// (DELETE /mockserver/loadScenario). Idempotent.
func (c *Client) ClearLoadScenarios() error {
	respBody, statusCode, err := c.doRequest("DELETE", "/mockserver/loadScenario", nil, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear load scenarios failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// StartLoadScenarios starts one or more registered scenarios by name
// (PUT /mockserver/loadScenario/start). Requires load generation to be enabled
// on the server — returns a FeatureDisabledError on HTTP 403
// (loadGenerationEnabled=false). Returns an error if a named scenario is not
// registered (HTTP 404). Honours each scenario's StartDelayMillis.
func (c *Client) StartLoadScenarios(names ...string) (*LoadScenarioStartResult, error) {
	body, err := json.Marshal(loadScenarioNames{Names: names})
	if err != nil {
		return nil, fmt.Errorf("mockserver: marshal start request: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/loadScenario/start", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode == 403 {
		return nil, &FeatureDisabledError{Feature: "load generation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: start load scenarios failed (status %d): %s", statusCode, string(respBody))
	}
	var result LoadScenarioStartResult
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &result); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal start result: %w", err)
		}
	}
	return &result, nil
}

// StopLoadScenarios stops the named running scenarios
// (PUT /mockserver/loadScenario/stop). With no names it stops all running
// scenarios (sends an empty body). Idempotent.
func (c *Client) StopLoadScenarios(names ...string) (*LoadScenarioStopResult, error) {
	var body []byte
	if len(names) > 0 {
		var err error
		body, err = json.Marshal(loadScenarioNames{Names: names})
		if err != nil {
			return nil, fmt.Errorf("mockserver: marshal stop request: %w", err)
		}
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/loadScenario/stop", body, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: stop load scenarios failed (status %d): %s", statusCode, string(respBody))
	}
	var result LoadScenarioStopResult
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &result); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal stop result: %w", err)
		}
	}
	return &result, nil
}

// RunLoadScenario is a convenience that registers a scenario and immediately
// starts it. It is equivalent to LoadScenario followed by StartLoadScenarios
// for the scenario's name. Starting requires load generation to be enabled
// (FeatureDisabledError on HTTP 403).
func (c *Client) RunLoadScenario(scenario LoadScenario) (*LoadScenarioStartResult, error) {
	if _, err := c.LoadScenario(scenario); err != nil {
		return nil, err
	}
	return c.StartLoadScenarios(scenario.Name)
}

// -----------------------------------------------------------------------------
// 2. Service chaos  (PUT /mockserver/serviceChaos)
//    OpenAPI: path ./mockserver/serviceChaos (lines 644-684),
//    schemas ServiceChaosRequest (4290), HttpChaosProfile (4312).
// -----------------------------------------------------------------------------

// ChaosLatency models the HttpChaosProfile.latency object (OpenAPI line 4323):
// an injected latency expressed as a value plus a time unit.
type ChaosLatency struct {
	Value    int    `json:"value,omitempty"`
	TimeUnit string `json:"timeUnit,omitempty"`
}

// ServiceChaosProfile is the HTTP chaos / fault-injection profile registered
// for a downstream host. Wire keys match schema HttpChaosProfile (OpenAPI line
// 4312).
type ServiceChaosProfile struct {
	// ErrorStatus is the HTTP status to return instead of the real response.
	ErrorStatus int `json:"errorStatus,omitempty"`
	// ErrorProbability is the probability (0.0-1.0) a request triggers the error.
	ErrorProbability float64 `json:"errorProbability,omitempty"`
	// Latency is the injected latency, if any.
	Latency *ChaosLatency `json:"latency,omitempty"`
	// ConnectionDrop, when true, drops the TCP connection without responding.
	ConnectionDrop *bool `json:"connectionDrop,omitempty"`
	// Seed is a fixed seed for deterministic probabilistic outcomes.
	Seed *int `json:"seed,omitempty"`
}

// serviceChaosRequest is the wire body for PUT /mockserver/serviceChaos. Wire
// keys match schema ServiceChaosRequest (OpenAPI line 4290).
type serviceChaosRequest struct {
	Host      string               `json:"host,omitempty"`
	Chaos     *ServiceChaosProfile `json:"chaos,omitempty"`
	Remove    bool                 `json:"remove,omitempty"`
	Clear     bool                 `json:"clear,omitempty"`
	TTLMillis int64                `json:"ttlMillis,omitempty"`
}

// SetServiceChaos registers an HTTP chaos profile for a downstream host
// (PUT /mockserver/serviceChaos). ttlMillis is an optional auto-revert window;
// pass 0 to omit it.
func (c *Client) SetServiceChaos(host string, profile ServiceChaosProfile, ttlMillis int64) error {
	reqBody := serviceChaosRequest{
		Host:      host,
		Chaos:     &profile,
		TTLMillis: ttlMillis,
	}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "service chaos", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: set service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// RemoveServiceChaos removes the chaos profile registered for a single host
// (PUT /mockserver/serviceChaos with remove:true).
func (c *Client) RemoveServiceChaos(host string) error {
	reqBody := serviceChaosRequest{Host: host, Remove: true}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos removal: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: remove service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// ClearServiceChaos clears all service-scoped chaos profiles
// (PUT /mockserver/serviceChaos with clear:true).
func (c *Client) ClearServiceChaos() error {
	reqBody := serviceChaosRequest{Clear: true}
	body, err := json.Marshal(reqBody)
	if err != nil {
		return fmt.Errorf("mockserver: marshal service chaos clear: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/serviceChaos", body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear service chaos failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// -----------------------------------------------------------------------------
// 3. SLO verdict  (PUT /mockserver/verifySLO)
//    OpenAPI: path ./mockserver/verifySLO (lines 2836-2891),
//    schemas SloCriteria (4484), SloObjective (4459), SloVerdict (4548),
//    SloObjectiveResult (4527).
// -----------------------------------------------------------------------------

// SloObjective is a single service-level objective over the recorded SLI
// samples. Wire keys match schema SloObjective (OpenAPI line 4459).
type SloObjective struct {
	// Sli is the indicator: LATENCY_P50, LATENCY_P95, LATENCY_P99 or ERROR_RATE.
	Sli string `json:"sli,omitempty"`
	// Comparator: LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL.
	Comparator string `json:"comparator,omitempty"`
	// Threshold is the objective threshold (ms for latency, 0.0-1.0 for ERROR_RATE).
	Threshold float64 `json:"threshold,omitempty"`
	// Scope: FORWARD (default) or INBOUND.
	Scope string `json:"scope,omitempty"`
}

// SloWindow is the time window an SloCriteria is evaluated over. Wire keys
// match the inline window object of schema SloCriteria (OpenAPI line 4494).
type SloWindow struct {
	// Type: LOOKBACK (default) or EXPLICIT.
	Type string `json:"type,omitempty"`
	// LookbackMillis is the LOOKBACK window length ending now.
	LookbackMillis int64 `json:"lookbackMillis,omitempty"`
	// FromEpochMillis is the EXPLICIT window start in epoch milliseconds.
	FromEpochMillis int64 `json:"fromEpochMillis,omitempty"`
	// ToEpochMillis is the EXPLICIT window end in epoch milliseconds.
	ToEpochMillis int64 `json:"toEpochMillis,omitempty"`
}

// SloCriteria is a named set of service-level objectives over a time window.
// Wire keys match schema SloCriteria (OpenAPI line 4484). Objectives is required.
type SloCriteria struct {
	Name               string         `json:"name,omitempty"`
	Window             *SloWindow     `json:"window,omitempty"`
	MinimumSampleCount int            `json:"minimumSampleCount,omitempty"`
	UpstreamHosts      []string       `json:"upstreamHosts,omitempty"`
	Objectives         []SloObjective `json:"objectives,omitempty"`
}

// SloObjectiveResult is the evaluated result of a single objective. Wire keys
// match schema SloObjectiveResult (OpenAPI line 4527).
type SloObjectiveResult struct {
	Sli           string   `json:"sli,omitempty"`
	Comparator    string   `json:"comparator,omitempty"`
	Threshold     float64  `json:"threshold,omitempty"`
	ObservedValue *float64 `json:"observedValue,omitempty"`
	Result        string   `json:"result,omitempty"`
	Detail        string   `json:"detail,omitempty"`
}

// SloVerdict is the overall verdict of an SLO evaluation (the AND of all
// objective results). Wire keys match schema SloVerdict (OpenAPI line 4548).
// Result is one of PASS, FAIL, INCONCLUSIVE.
type SloVerdict struct {
	Name                  string               `json:"name,omitempty"`
	Result                string               `json:"result,omitempty"`
	WindowFromEpochMillis int64                `json:"windowFromEpochMillis,omitempty"`
	WindowToEpochMillis   int64                `json:"windowToEpochMillis,omitempty"`
	SampleCount           int                  `json:"sampleCount,omitempty"`
	ObjectiveResults      []SloObjectiveResult `json:"objectiveResults,omitempty"`
}

// VerifySLO evaluates a set of service-level objectives over a window
// (PUT /mockserver/verifySLO). The server encodes the verdict in the HTTP
// status: 200 for PASS or INCONCLUSIVE, 406 for FAIL, 400 for a malformed
// criteria or when SLO tracking is disabled (sloTrackingEnabled=false).
//
// The decoded SloVerdict is returned for both PASS/INCONCLUSIVE (200) and FAIL
// (406) so callers can inspect the per-objective results; the returned error is
// non-nil only on FAIL or a real error. A 400 disabled response is surfaced as
// a FeatureDisabledError.
func (c *Client) VerifySLO(criteria SloCriteria) (SloVerdict, error) {
	var verdict SloVerdict

	body, err := json.Marshal(criteria)
	if err != nil {
		return verdict, fmt.Errorf("mockserver: marshal slo criteria: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/verifySLO", body, nil)
	if err != nil {
		return verdict, err
	}

	if statusCode == 400 {
		return verdict, &FeatureDisabledError{Feature: "SLO tracking", Status: statusCode, Body: string(respBody)}
	}
	if statusCode != 200 && statusCode != 406 {
		return verdict, fmt.Errorf("mockserver: verify SLO failed (status %d): %s", statusCode, string(respBody))
	}

	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &verdict); err != nil {
			return verdict, fmt.Errorf("mockserver: unmarshal slo verdict: %w", err)
		}
	}

	if statusCode == 406 {
		return verdict, &VerificationError{Message: fmt.Sprintf("SLO verdict FAIL: %s", string(respBody))}
	}
	return verdict, nil
}

// -----------------------------------------------------------------------------
// 4. Preemption  (PUT/GET/DELETE /mockserver/preemption)
//    OpenAPI: path ./mockserver/preemption (lines 2893-2964),
//    schemas PreemptionRequest (4570), PreemptionStatus (4592).
// -----------------------------------------------------------------------------

// PreemptionRequest holds preemption simulation parameters (all optional).
// Wire keys match schema PreemptionRequest (OpenAPI line 4570).
type PreemptionRequest struct {
	// Mode: reject503, goaway or both (default both).
	Mode string `json:"mode,omitempty"`
	// DrainMillis is how long in-flight requests are allowed to drain.
	DrainMillis int64 `json:"drainMillis,omitempty"`
	// TTLMillis auto-uncordons after this many milliseconds (0 = no auto-uncordon).
	TTLMillis int64 `json:"ttlMillis,omitempty"`
	// LastStreamID is the HTTP/2 GOAWAY last_stream_id to advertise (-1 = server chooses).
	LastStreamID *int64 `json:"lastStreamId,omitempty"`
}

// PreemptionStatus is the current cordon/drain status of the server. Wire keys
// match schema PreemptionStatus (OpenAPI line 4592).
type PreemptionStatus struct {
	// State: inactive, draining or drained.
	State string `json:"state,omitempty"`
	// InFlight is the number of requests currently in flight.
	InFlight int `json:"inFlight,omitempty"`
	// DrainRemainingMillis is the milliseconds left in the drain window.
	DrainRemainingMillis int64 `json:"drainRemainingMillis,omitempty"`
	// Mode is the active signalling mode (omitted when inactive).
	Mode string `json:"mode,omitempty"`
}

// SetPreemption cordons and drains the server, simulating a preemption
// (PUT /mockserver/preemption). The returned status reflects the started (or
// replaced) simulation.
func (c *Client) SetPreemption(req PreemptionRequest) error {
	body, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("mockserver: marshal preemption request: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/preemption", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: set preemption failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// PreemptionStatus retrieves the current cordon/drain status
// (GET /mockserver/preemption).
func (c *Client) PreemptionStatus() (*PreemptionStatus, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/preemption", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode == 403 {
		return nil, &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: preemption status failed (status %d): %s", statusCode, string(respBody))
	}
	var status PreemptionStatus
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &status); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal preemption status: %w", err)
		}
	}
	return &status, nil
}

// ClearPreemption uncordons the server, clearing any active preemption
// simulation (DELETE /mockserver/preemption). Idempotent.
func (c *Client) ClearPreemption() error {
	respBody, statusCode, err := c.doRequest("DELETE", "/mockserver/preemption", nil, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "preemption simulation", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: clear preemption failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// -----------------------------------------------------------------------------
// 5. Chaos experiment  (PUT /mockserver/chaosExperiment)
//    OpenAPI: path ./mockserver/chaosExperiment (lines 2130-2208),
//    schema ChaosExperiment (4611), HttpChaosProfile (4312).
// -----------------------------------------------------------------------------

// ChaosExperimentStage is a single stage of a chaos experiment: a set of
// host -> chaos profile mappings applied for a fixed duration. Wire keys match
// the inline stage object of schema ChaosExperiment (OpenAPI line 4624).
type ChaosExperimentStage struct {
	// DurationMillis is how long the stage runs before advancing.
	DurationMillis int64 `json:"durationMillis,omitempty"`
	// Profiles maps a downstream host to the chaos profile applied during the stage.
	Profiles map[string]ServiceChaosProfile `json:"profiles,omitempty"`
}

// ChaosExperiment is a scheduled multi-stage chaos experiment definition. Wire
// keys match schema ChaosExperiment (OpenAPI line 4611). Stages is required.
type ChaosExperiment struct {
	// Name is the human-readable experiment name.
	Name string `json:"name,omitempty"`
	// Loop, when true, loops back to stage 0 after the last stage completes.
	Loop bool `json:"loop,omitempty"`
	// Stages is the ordered sequence of stages (required).
	Stages []ChaosExperimentStage `json:"stages,omitempty"`
}

// StartChaosExperiment starts a scheduled multi-stage chaos experiment
// (PUT /mockserver/chaosExperiment). Only one experiment may be active at a
// time; starting a new one stops the previous one.
func (c *Client) StartChaosExperiment(experiment ChaosExperiment) error {
	body, err := json.Marshal(experiment)
	if err != nil {
		return fmt.Errorf("mockserver: marshal chaos experiment: %w", err)
	}

	respBody, statusCode, err := c.doRequest("PUT", "/mockserver/chaosExperiment", body, nil)
	if err != nil {
		return err
	}
	if statusCode == 403 {
		return &FeatureDisabledError{Feature: "chaos experiment", Status: statusCode, Body: string(respBody)}
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: start chaos experiment failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}
