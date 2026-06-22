package mockserver

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// ScenarioState is the current state of a single named scenario state machine,
// as returned by the GET /mockserver/scenario endpoints.
type ScenarioState struct {
	ScenarioName string `json:"scenarioName"`
	CurrentState string `json:"currentState"`
	// NextState and TransitionAfterMs are populated for timed transitions
	// (a pending automatic transition scheduled via SetTimed).
	NextState         string `json:"nextState,omitempty"`
	TransitionAfterMs int    `json:"transitionAfterMs,omitempty"`
}

// scenariosResponse is the envelope returned by GET /mockserver/scenario.
type scenariosResponse struct {
	Scenarios []ScenarioState `json:"scenarios"`
}

// ScenarioHandle is a typed handle to a single named scenario state machine.
// Obtain one via Client.Scenario(name).
type ScenarioHandle struct {
	client *Client
	name   string
}

// Scenario returns a handle to the named scenario state machine, used to read
// and drive scenario state on the running MockServer instance.
func (c *Client) Scenario(name string) *ScenarioHandle {
	return &ScenarioHandle{client: c, name: name}
}

// path returns the control-plane path for this scenario with the name
// percent-encoded, matching the other MockServer clients.
func (h *ScenarioHandle) path() string {
	return "/mockserver/scenario/" + url.PathEscape(h.name)
}

// State returns the current state of this scenario
// (GET /mockserver/scenario/{name}).
func (h *ScenarioHandle) State() (string, error) {
	respBody, statusCode, err := h.client.doRequest("GET", h.path(), nil, nil)
	if err != nil {
		return "", err
	}
	if statusCode >= 400 {
		return "", fmt.Errorf("mockserver: get scenario state failed (status %d): %s", statusCode, string(respBody))
	}
	var s ScenarioState
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &s); err != nil {
			return "", fmt.Errorf("mockserver: unmarshal scenario state: %w", err)
		}
	}
	return s.CurrentState, nil
}

// Set sets this scenario to the given state (PUT /mockserver/scenario/{name}).
func (h *ScenarioHandle) Set(state string) error {
	return h.set(map[string]interface{}{"state": state})
}

// SetTimed sets this scenario to the given state and schedules an automatic
// transition to nextState after transitionAfterMs milliseconds
// (PUT /mockserver/scenario/{name}).
func (h *ScenarioHandle) SetTimed(state string, transitionAfterMs int, nextState string) error {
	return h.set(map[string]interface{}{
		"state":             state,
		"transitionAfterMs": transitionAfterMs,
		"nextState":         nextState,
	})
}

func (h *ScenarioHandle) set(payload map[string]interface{}) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("mockserver: marshal scenario state: %w", err)
	}
	respBody, statusCode, err := h.client.doRequest("PUT", h.path(), body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: set scenario state failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// Trigger transitions this scenario to newState
// (PUT /mockserver/scenario/{name}/trigger).
func (h *ScenarioHandle) Trigger(newState string) error {
	body, err := json.Marshal(map[string]string{"newState": newState})
	if err != nil {
		return fmt.Errorf("mockserver: marshal scenario trigger: %w", err)
	}
	respBody, statusCode, err := h.client.doRequest("PUT", h.path()+"/trigger", body, nil)
	if err != nil {
		return err
	}
	if statusCode >= 400 {
		return fmt.Errorf("mockserver: trigger scenario failed (status %d): %s", statusCode, string(respBody))
	}
	return nil
}

// Scenarios lists all known scenario state machines and their current state
// (GET /mockserver/scenario).
func (c *Client) Scenarios() ([]ScenarioState, error) {
	respBody, statusCode, err := c.doRequest("GET", "/mockserver/scenario", nil, nil)
	if err != nil {
		return nil, err
	}
	if statusCode >= 400 {
		return nil, fmt.Errorf("mockserver: list scenarios failed (status %d): %s", statusCode, string(respBody))
	}
	var result scenariosResponse
	if len(respBody) > 0 {
		if err := json.Unmarshal(respBody, &result); err != nil {
			return nil, fmt.Errorf("mockserver: unmarshal scenarios: %w", err)
		}
	}
	return result.Scenarios, nil
}
