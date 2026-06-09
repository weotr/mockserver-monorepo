//! Domain model types for the MockServer control-plane API.
//!
//! All types implement `Serialize`/`Deserialize` and use builder methods that
//! take `self` and return `Self`, enabling fluent construction.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ---------------------------------------------------------------------------
// HttpRequest
// ---------------------------------------------------------------------------

/// Matcher for an HTTP request. Uses builder methods for fluent construction.
///
/// # Example
/// ```
/// use mockserver_client::HttpRequest;
///
/// let request = HttpRequest::new()
///     .method("POST")
///     .path("/api/users")
///     .header("Content-Type", "application/json")
///     .query_param("page", "1")
///     .body("{}");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub query_string_parameters: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<Body>,
}

impl HttpRequest {
    /// Create a new empty request matcher.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP method to match.
    pub fn method(mut self, method: impl Into<String>) -> Self {
        self.method = Some(method.into());
        self
    }

    /// Set the path to match.
    pub fn path(mut self, path: impl Into<String>) -> Self {
        self.path = Some(path.into());
        self
    }

    /// Add a query string parameter (multiple values per key supported).
    pub fn query_param(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let params = self.query_string_parameters.get_or_insert_with(HashMap::new);
        params
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Add a header (multiple values per key supported).
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Set a plain string body matcher.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(Body::Plain(body.into()));
        self
    }

    /// Set a typed JSON body matcher.
    pub fn json_body(mut self, json: serde_json::Value) -> Self {
        self.body = Some(Body::Typed {
            body_type: "JSON".to_string(),
            json: json.to_string(),
        });
        self
    }
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

/// Request/response body — either a plain string or a typed object.
#[derive(Debug, Clone, PartialEq)]
pub enum Body {
    /// A plain string body.
    Plain(String),
    /// A typed body (e.g., JSON).
    Typed { body_type: String, json: String },
}

impl Serialize for Body {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        match self {
            Body::Plain(s) => serializer.serialize_str(s),
            Body::Typed { body_type, json } => {
                use serde::ser::SerializeMap;
                let mut map = serializer.serialize_map(Some(2))?;
                map.serialize_entry("type", body_type)?;
                map.serialize_entry("json", json)?;
                map.end()
            }
        }
    }
}

impl<'de> Deserialize<'de> for Body {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        use serde_json::Value;
        let v = Value::deserialize(deserializer)?;
        match v {
            Value::String(s) => Ok(Body::Plain(s)),
            Value::Object(map) => {
                let body_type = map
                    .get("type")
                    .and_then(|v| v.as_str())
                    .unwrap_or("JSON")
                    .to_string();
                let json = map
                    .get("json")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                Ok(Body::Typed { body_type, json })
            }
            _ => Ok(Body::Plain(v.to_string())),
        }
    }
}

// ---------------------------------------------------------------------------
// HttpResponse
// ---------------------------------------------------------------------------

/// Builder for an HTTP response action.
///
/// # Example
/// ```
/// use mockserver_client::HttpResponse;
///
/// let response = HttpResponse::new()
///     .status_code(201)
///     .header("Location", "/api/users/42")
///     .body("{\"id\": 42}");
/// ```
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpResponse {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub headers: Option<HashMap<String, Vec<String>>>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub delay: Option<Delay>,
}

impl HttpResponse {
    /// Create a new empty response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the HTTP status code.
    pub fn status_code(mut self, code: u16) -> Self {
        self.status_code = Some(code);
        self
    }

    /// Add a response header.
    pub fn header(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        let headers = self.headers.get_or_insert_with(HashMap::new);
        headers
            .entry(key.into())
            .or_default()
            .push(value.into());
        self
    }

    /// Set the response body as a string.
    pub fn body(mut self, body: impl Into<String>) -> Self {
        self.body = Some(body.into());
        self
    }

    /// Set a response delay.
    pub fn delay(mut self, delay: Delay) -> Self {
        self.delay = Some(delay);
        self
    }
}

// ---------------------------------------------------------------------------
// HttpForward
// ---------------------------------------------------------------------------

/// Forward action — proxy the matched request to another host.
///
/// # Example
/// ```
/// use mockserver_client::HttpForward;
///
/// let forward = HttpForward::new("backend.local", 8080);
/// ```
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpForward {
    pub host: String,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub scheme: Option<String>,
}

impl HttpForward {
    /// Create a forward action to the given host and port.
    pub fn new(host: impl Into<String>, port: u16) -> Self {
        Self {
            host: host.into(),
            port: Some(port),
            scheme: None,
        }
    }

    /// Set the scheme (HTTP or HTTPS).
    pub fn scheme(mut self, scheme: impl Into<String>) -> Self {
        self.scheme = Some(scheme.into());
        self
    }
}

// ---------------------------------------------------------------------------
// HttpError
// ---------------------------------------------------------------------------

/// Error action — return a connection-level error to the caller.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HttpError {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub drop_connection: Option<bool>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub response_bytes: Option<String>,
}

impl HttpError {
    /// Create a new error action.
    pub fn new() -> Self {
        Self::default()
    }

    /// Drop the connection without a response.
    pub fn drop_connection(mut self, drop: bool) -> Self {
        self.drop_connection = Some(drop);
        self
    }

    /// Send arbitrary bytes then close.
    pub fn response_bytes(mut self, bytes: impl Into<String>) -> Self {
        self.response_bytes = Some(bytes.into());
        self
    }
}

// ---------------------------------------------------------------------------
// Delay
// ---------------------------------------------------------------------------

/// A time delay (e.g., for response delays).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Delay {
    pub time_unit: String,
    pub value: u64,
}

impl Delay {
    /// Create a delay in milliseconds.
    pub fn milliseconds(value: u64) -> Self {
        Self {
            time_unit: "MILLISECONDS".to_string(),
            value,
        }
    }

    /// Create a delay in seconds.
    pub fn seconds(value: u64) -> Self {
        Self {
            time_unit: "SECONDS".to_string(),
            value,
        }
    }
}

// ---------------------------------------------------------------------------
// Times
// ---------------------------------------------------------------------------

/// How many times an expectation should be matched.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Times {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub remaining_times: Option<u32>,

    pub unlimited: bool,
}

impl Times {
    /// Match unlimited times.
    pub fn unlimited() -> Self {
        Self {
            remaining_times: None,
            unlimited: true,
        }
    }

    /// Match exactly `n` times.
    pub fn exactly(n: u32) -> Self {
        Self {
            remaining_times: Some(n),
            unlimited: false,
        }
    }

    /// Match once.
    pub fn once() -> Self {
        Self::exactly(1)
    }
}

// ---------------------------------------------------------------------------
// TimeToLive
// ---------------------------------------------------------------------------

/// How long an expectation remains active.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TimeToLive {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_unit: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<u64>,

    pub unlimited: bool,
}

impl TimeToLive {
    /// Unlimited TTL (never expires).
    pub fn unlimited() -> Self {
        Self {
            time_unit: None,
            time_to_live: None,
            unlimited: true,
        }
    }

    /// Expire after the given number of seconds.
    pub fn seconds(seconds: u64) -> Self {
        Self {
            time_unit: Some("SECONDS".to_string()),
            time_to_live: Some(seconds),
            unlimited: false,
        }
    }

    /// Expire after the given number of milliseconds.
    pub fn milliseconds(millis: u64) -> Self {
        Self {
            time_unit: Some("MILLISECONDS".to_string()),
            time_to_live: Some(millis),
            unlimited: false,
        }
    }
}

// ---------------------------------------------------------------------------
// VerificationTimes
// ---------------------------------------------------------------------------

/// Verification constraints — how many times a request must have been received.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationTimes {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_least: Option<u32>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_most: Option<u32>,
}

impl VerificationTimes {
    /// Require at least `n` matching requests.
    pub fn at_least(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: None,
        }
    }

    /// Require at most `n` matching requests.
    pub fn at_most(n: u32) -> Self {
        Self {
            at_least: None,
            at_most: Some(n),
        }
    }

    /// Require exactly `n` matching requests.
    pub fn exactly(n: u32) -> Self {
        Self {
            at_least: Some(n),
            at_most: Some(n),
        }
    }

    /// Require between `min` and `max` matching requests (inclusive).
    pub fn between(min: u32, max: u32) -> Self {
        Self {
            at_least: Some(min),
            at_most: Some(max),
        }
    }
}

// ---------------------------------------------------------------------------
// Expectation
// ---------------------------------------------------------------------------

/// A full expectation combining a request matcher with an action.
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Expectation {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<i32>,

    pub http_request: HttpRequest,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_response: Option<HttpResponse>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_forward: Option<HttpForward>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub http_error: Option<HttpError>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<Times>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_to_live: Option<TimeToLive>,
}

impl Expectation {
    /// Create a new expectation with the given request matcher.
    pub fn new(request: HttpRequest) -> Self {
        Self {
            http_request: request,
            ..Default::default()
        }
    }

    /// Set the expectation ID (for upsert semantics).
    pub fn id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Set the priority (higher = matched first).
    pub fn priority(mut self, priority: i32) -> Self {
        self.priority = Some(priority);
        self
    }

    /// Set a response action.
    pub fn respond(mut self, response: HttpResponse) -> Self {
        self.http_response = Some(response);
        self
    }

    /// Set a forward action.
    pub fn forward(mut self, forward: HttpForward) -> Self {
        self.http_forward = Some(forward);
        self
    }

    /// Set an error action.
    pub fn error(mut self, error: HttpError) -> Self {
        self.http_error = Some(error);
        self
    }

    /// Set the number of times this expectation matches.
    pub fn times(mut self, times: Times) -> Self {
        self.times = Some(times);
        self
    }

    /// Set the time-to-live.
    pub fn time_to_live(mut self, ttl: TimeToLive) -> Self {
        self.time_to_live = Some(ttl);
        self
    }
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

/// A verification request sent to MockServer.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Verification {
    pub http_request: HttpRequest,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub times: Option<VerificationTimes>,
}

/// A verification sequence request.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct VerificationSequence {
    pub http_requests: Vec<HttpRequest>,
}

// ---------------------------------------------------------------------------
// Ports
// ---------------------------------------------------------------------------

/// Port list (used by status and bind endpoints).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Ports {
    pub ports: Vec<u16>,
}

// ---------------------------------------------------------------------------
// Retrieve types
// ---------------------------------------------------------------------------

/// The type of data to retrieve from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetrieveType {
    /// Recorded inbound requests.
    Requests,
    /// Active (live) expectations.
    ActiveExpectations,
    /// Recorded expectations (from proxy mode).
    RecordedExpectations,
    /// Log messages.
    Logs,
    /// Request/response pairs.
    RequestResponses,
}

impl RetrieveType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveType::Requests => "REQUESTS",
            RetrieveType::ActiveExpectations => "ACTIVE_EXPECTATIONS",
            RetrieveType::RecordedExpectations => "RECORDED_EXPECTATIONS",
            RetrieveType::Logs => "LOGS",
            RetrieveType::RequestResponses => "REQUEST_RESPONSES",
        }
    }
}

/// The response format for retrieve calls.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RetrieveFormat {
    Json,
    LogEntries,
}

impl RetrieveFormat {
    /// The query parameter value for this format.
    pub fn as_str(&self) -> &'static str {
        match self {
            RetrieveFormat::Json => "JSON",
            RetrieveFormat::LogEntries => "LOG_ENTRIES",
        }
    }
}

/// The type of data to clear from MockServer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClearType {
    All,
    Log,
    Expectations,
}

impl ClearType {
    /// The query parameter value for this type.
    pub fn as_str(&self) -> &'static str {
        match self {
            ClearType::All => "ALL",
            ClearType::Log => "LOG",
            ClearType::Expectations => "EXPECTATIONS",
        }
    }
}
