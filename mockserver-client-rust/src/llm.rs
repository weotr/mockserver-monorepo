//! Fluent LLM-mocking builders for the MockServer Rust client.
//!
//! This module is an idiomatic Rust port of the Java/Node/Python client LLM
//! builders (`org.mockserver.client.Llm` / `LlmMockBuilder` /
//! `LlmConversationBuilder` / `LlmFailoverBuilder` / `TurnBuilder`) and the
//! underlying model classes (`Completion`, `ToolUse`, `Usage`,
//! `StreamingPhysics`, `EmbeddingResponse`). The builders produce exactly the
//! same expectation wire JSON the other clients emit, so a mock scripted from
//! Rust is byte-for-byte equivalent (modulo JSON key order, which the server
//! ignores) to one scripted from Java.
//!
//! The expectation action is carried in the `httpLlmResponse` field of an
//! expectation (a sibling of `httpRequest`, `scenarioName`, `scenarioState`,
//! `newScenarioState`, `times`, `timeToLive`, `httpResponse`). Null/unset
//! fields are omitted from the JSON, matching the server's `NON_NULL`
//! serialization.
//!
//! # Example
//!
//! ```
//! use mockserver_client::llm::{llm_mock, completion, Provider};
//!
//! let expectation = llm_mock("/v1/chat/completions")
//!     .with_provider(Provider::OPENAI)
//!     .with_model("gpt-4o")
//!     .responding_with(completion().with_text("Hello!"))
//!     .build();
//!
//! assert_eq!(expectation["httpLlmResponse"]["provider"], "OPENAI");
//! ```

use serde_json::{json, Map, Value};

use crate::error::Result;
use crate::MockServerClient;

// ---------------------------------------------------------------------------
// Provider / Role string constants (serialized as the UPPERCASE enum name)
// ---------------------------------------------------------------------------

/// LLM provider names. Serialized on the wire as the upper-case enum name
/// (mirrors `org.mockserver.model.Provider`).
pub struct Provider;

impl Provider {
    pub const ANTHROPIC: &'static str = "ANTHROPIC";
    pub const OPENAI: &'static str = "OPENAI";
    pub const OPENAI_RESPONSES: &'static str = "OPENAI_RESPONSES";
    pub const GEMINI: &'static str = "GEMINI";
    pub const BEDROCK: &'static str = "BEDROCK";
    pub const AZURE_OPENAI: &'static str = "AZURE_OPENAI";
    pub const OLLAMA: &'static str = "OLLAMA";
}

/// Parsed-message roles (mirrors `org.mockserver.llm.ParsedMessage.Role`).
pub struct Role;

impl Role {
    pub const USER: &'static str = "USER";
    pub const ASSISTANT: &'static str = "ASSISTANT";
    pub const TOOL: &'static str = "TOOL";
    pub const SYSTEM: &'static str = "SYSTEM";
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/// Build a `{method: POST, path}` request matcher object.
fn post_matcher(path: &str) -> Value {
    json!({ "method": "POST", "path": path })
}

/// Insert a key only when the value is present (mirrors NON_NULL omission).
fn insert_some(map: &mut Map<String, Value>, key: &str, value: Option<Value>) {
    if let Some(v) = value {
        map.insert(key.to_string(), v);
    }
}

// ---------------------------------------------------------------------------
// ToolUse
// ---------------------------------------------------------------------------

/// A single tool/function call emitted by the assistant
/// (mirrors `org.mockserver.model.ToolUse`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ToolUse {
    name: String,
    id: Option<String>,
    arguments: Option<String>,
}

impl ToolUse {
    /// Create a tool call with the given tool name.
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            id: None,
            arguments: None,
        }
    }

    /// Set the tool-call id.
    pub fn with_id(mut self, id: impl Into<String>) -> Self {
        self.id = Some(id.into());
        self
    }

    /// Set the tool name.
    pub fn with_name(mut self, name: impl Into<String>) -> Self {
        self.name = name.into();
        self
    }

    /// Set the call arguments as a JSON string (matching the Java API).
    pub fn with_arguments(mut self, arguments: impl Into<String>) -> Self {
        self.arguments = Some(arguments.into());
        self
    }

    fn to_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "id", self.id.clone().map(Value::from));
        map.insert("name".to_string(), Value::from(self.name.clone()));
        insert_some(&mut map, "arguments", self.arguments.clone().map(Value::from));
        Value::Object(map)
    }
}

/// Factory mirroring `ToolUse.toolUse(name)`.
pub fn tool_use(name: impl Into<String>) -> ToolUse {
    ToolUse::new(name)
}

// ---------------------------------------------------------------------------
// Usage
// ---------------------------------------------------------------------------

/// Token usage counts for a completion (mirrors `org.mockserver.model.Usage`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Usage {
    input_tokens: Option<i64>,
    output_tokens: Option<i64>,
}

impl Usage {
    /// Create an empty usage.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the input-token count (must be `>= 0`).
    ///
    /// # Panics
    /// Panics if `input_tokens < 0`.
    pub fn with_input_tokens(mut self, input_tokens: i64) -> Self {
        assert!(input_tokens >= 0, "input_tokens must be >= 0");
        self.input_tokens = Some(input_tokens);
        self
    }

    /// Set the output-token count (must be `>= 0`).
    ///
    /// # Panics
    /// Panics if `output_tokens < 0`.
    pub fn with_output_tokens(mut self, output_tokens: i64) -> Self {
        assert!(output_tokens >= 0, "output_tokens must be >= 0");
        self.output_tokens = Some(output_tokens);
        self
    }

    fn to_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "inputTokens", self.input_tokens.map(Value::from));
        insert_some(&mut map, "outputTokens", self.output_tokens.map(Value::from));
        Value::Object(map)
    }
}

/// Factory mirroring `Usage.usage()`.
pub fn usage() -> Usage {
    Usage::new()
}

/// Factory mirroring `Usage.inputTokens(n)`.
pub fn input_tokens(n: i64) -> Usage {
    Usage::new().with_input_tokens(n)
}

/// Factory mirroring `Usage.outputTokens(n)`.
pub fn output_tokens(n: i64) -> Usage {
    Usage::new().with_output_tokens(n)
}

// ---------------------------------------------------------------------------
// StreamingPhysics
// ---------------------------------------------------------------------------

/// Controls the timing physics of a streamed (SSE) completion
/// (mirrors `org.mockserver.model.StreamingPhysics`).
///
/// `timeToFirstToken` serialises as a `Delay`: `{ timeUnit, value }`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct StreamingPhysics {
    time_to_first_token: Option<Value>,
    tokens_per_second: Option<i64>,
    jitter: Option<f64>,
    seed: Option<i64>,
}

impl StreamingPhysics {
    /// Create empty streaming physics.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set time-to-first-token as a delay value with the given time unit
    /// (e.g. `"MILLISECONDS"`).
    pub fn with_time_to_first_token(mut self, value: i64, time_unit: impl Into<String>) -> Self {
        self.time_to_first_token = Some(json!({ "timeUnit": time_unit.into(), "value": value }));
        self
    }

    /// Set tokens-per-second (must be in `[1, 10000]`).
    ///
    /// # Panics
    /// Panics if `tokens_per_second` is outside `1..=10000`.
    pub fn with_tokens_per_second(mut self, tokens_per_second: i64) -> Self {
        assert!(
            (1..=10000).contains(&tokens_per_second),
            "tokens_per_second must be between 1 and 10000"
        );
        self.tokens_per_second = Some(tokens_per_second);
        self
    }

    /// Set the inter-token jitter (must be in `[0.0, 1.0]`).
    ///
    /// # Panics
    /// Panics if `jitter` is outside `0.0..=1.0`.
    pub fn with_jitter(mut self, jitter: f64) -> Self {
        assert!(
            (0.0..=1.0).contains(&jitter),
            "jitter must be between 0.0 and 1.0"
        );
        self.jitter = Some(jitter);
        self
    }

    /// Set the deterministic seed.
    pub fn with_seed(mut self, seed: i64) -> Self {
        self.seed = Some(seed);
        self
    }

    fn to_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "timeToFirstToken", self.time_to_first_token.clone());
        insert_some(&mut map, "tokensPerSecond", self.tokens_per_second.map(Value::from));
        insert_some(&mut map, "jitter", self.jitter.map(Value::from));
        insert_some(&mut map, "seed", self.seed.map(Value::from));
        Value::Object(map)
    }
}

/// Factory mirroring `StreamingPhysics.streamingPhysics()`.
pub fn streaming_physics() -> StreamingPhysics {
    StreamingPhysics::new()
}

/// Factory mirroring `StreamingPhysics.tokensPerSecond(n)`.
pub fn tokens_per_second(n: i64) -> StreamingPhysics {
    StreamingPhysics::new().with_tokens_per_second(n)
}

/// Factory mirroring `StreamingPhysics.jitter(j)`.
pub fn jitter(j: f64) -> StreamingPhysics {
    StreamingPhysics::new().with_jitter(j)
}

// ---------------------------------------------------------------------------
// Completion
// ---------------------------------------------------------------------------

/// A mocked LLM chat/completion response, provider-agnostic
/// (mirrors `org.mockserver.model.Completion`).
///
/// MockServer re-encodes this into the wire shape of the configured provider
/// when a request is served.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Completion {
    text: Option<String>,
    tool_calls: Option<Vec<ToolUse>>,
    stop_reason: Option<String>,
    usage: Option<Usage>,
    streaming: Option<bool>,
    streaming_physics: Option<StreamingPhysics>,
    output_schema: Option<String>,
    model: Option<String>,
}

impl Completion {
    /// Create an empty completion.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the response text.
    pub fn with_text(mut self, text: impl Into<String>) -> Self {
        self.text = Some(text.into());
        self
    }

    /// Append a single tool call.
    pub fn with_tool_call(mut self, tool_call: ToolUse) -> Self {
        self.tool_calls.get_or_insert_with(Vec::new).push(tool_call);
        self
    }

    /// Replace all tool calls.
    pub fn with_tool_calls(mut self, tool_calls: Vec<ToolUse>) -> Self {
        self.tool_calls = Some(tool_calls);
        self
    }

    /// Set the stop reason.
    pub fn with_stop_reason(mut self, stop_reason: impl Into<String>) -> Self {
        self.stop_reason = Some(stop_reason.into());
        self
    }

    /// Set token usage.
    pub fn with_usage(mut self, usage: Usage) -> Self {
        self.usage = Some(usage);
        self
    }

    /// Set the streaming flag explicitly.
    pub fn with_streaming(mut self, streaming: bool) -> Self {
        self.streaming = Some(streaming);
        self
    }

    /// Enable streaming (mirrors Java `completion().streaming()`).
    pub fn streaming(self) -> Self {
        self.with_streaming(true)
    }

    /// Set streaming physics. Does NOT touch the `streaming` flag — enable it
    /// explicitly via [`with_streaming`](Self::with_streaming) / [`streaming`](Self::streaming).
    pub fn with_streaming_physics(mut self, physics: StreamingPhysics) -> Self {
        self.streaming_physics = Some(physics);
        self
    }

    /// Set the structured-output JSON schema (a JSON string, matching Java).
    pub fn with_output_schema(mut self, output_schema: impl Into<String>) -> Self {
        self.output_schema = Some(output_schema.into());
        self
    }

    /// Set the model name carried by the completion.
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    fn to_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "text", self.text.clone().map(Value::from));
        insert_some(
            &mut map,
            "toolCalls",
            self.tool_calls
                .as_ref()
                .map(|calls| Value::Array(calls.iter().map(ToolUse::to_value).collect())),
        );
        insert_some(&mut map, "stopReason", self.stop_reason.clone().map(Value::from));
        insert_some(&mut map, "usage", self.usage.as_ref().map(Usage::to_value));
        insert_some(&mut map, "streaming", self.streaming.map(Value::from));
        insert_some(
            &mut map,
            "streamingPhysics",
            self.streaming_physics.as_ref().map(StreamingPhysics::to_value),
        );
        insert_some(&mut map, "outputSchema", self.output_schema.clone().map(Value::from));
        insert_some(&mut map, "model", self.model.clone().map(Value::from));
        Value::Object(map)
    }
}

/// Factory mirroring `Completion.completion()`.
pub fn completion() -> Completion {
    Completion::new()
}

// ---------------------------------------------------------------------------
// EmbeddingResponse
// ---------------------------------------------------------------------------

/// A mocked embedding response — vector shape and determinism
/// (mirrors `org.mockserver.model.EmbeddingResponse`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct EmbeddingResponse {
    dimensions: Option<i64>,
    deterministic_from_input: Option<bool>,
    seed: Option<i64>,
}

impl EmbeddingResponse {
    /// Create an empty embedding response.
    pub fn new() -> Self {
        Self::default()
    }

    /// Set the embedding vector dimensionality.
    pub fn with_dimensions(mut self, dimensions: i64) -> Self {
        self.dimensions = Some(dimensions);
        self
    }

    /// Whether the embedding should be deterministically derived from input.
    pub fn with_deterministic_from_input(mut self, deterministic: bool) -> Self {
        self.deterministic_from_input = Some(deterministic);
        self
    }

    /// Set the deterministic seed.
    pub fn with_seed(mut self, seed: i64) -> Self {
        self.seed = Some(seed);
        self
    }

    fn to_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "dimensions", self.dimensions.map(Value::from));
        insert_some(
            &mut map,
            "deterministicFromInput",
            self.deterministic_from_input.map(Value::from),
        );
        insert_some(&mut map, "seed", self.seed.map(Value::from));
        Value::Object(map)
    }
}

/// Factory mirroring `EmbeddingResponse.embedding()`.
pub fn embedding() -> EmbeddingResponse {
    EmbeddingResponse::new()
}

// ---------------------------------------------------------------------------
// A completion-or-embedding response payload
// ---------------------------------------------------------------------------

/// Either a [`Completion`] or an [`EmbeddingResponse`], accepted by
/// `responding_with` builder methods.
#[derive(Debug, Clone, PartialEq)]
pub enum LlmResponseBody {
    Completion(Completion),
    Embedding(EmbeddingResponse),
}

impl From<Completion> for LlmResponseBody {
    fn from(c: Completion) -> Self {
        LlmResponseBody::Completion(c)
    }
}

impl From<EmbeddingResponse> for LlmResponseBody {
    fn from(e: EmbeddingResponse) -> Self {
        LlmResponseBody::Embedding(e)
    }
}

/// Build the `httpLlmResponse` action object.
fn build_llm_response(
    provider: Option<&str>,
    model: Option<&str>,
    completion: Option<&Completion>,
    embedding: Option<&EmbeddingResponse>,
    conversation_predicates: Option<Value>,
    chaos: Option<Value>,
) -> Value {
    let mut map = Map::new();
    insert_some(&mut map, "provider", provider.map(Value::from));
    insert_some(&mut map, "model", model.map(Value::from));
    insert_some(&mut map, "completion", completion.map(Completion::to_value));
    insert_some(&mut map, "embedding", embedding.map(EmbeddingResponse::to_value));
    insert_some(&mut map, "conversationPredicates", conversation_predicates);
    insert_some(&mut map, "chaos", chaos);
    Value::Object(map)
}

// ---------------------------------------------------------------------------
// IsolationSource
// ---------------------------------------------------------------------------

const ISOLATION_MARKER: &str = "__iso=";

/// Where to read the per-session isolation key from an inbound request
/// (mirrors `org.mockserver.llm.IsolationSource`). Encodes as `"<kind>:<name>"`.
#[derive(Debug, Clone, PartialEq)]
pub struct IsolationSource {
    kind: String,
    name: String,
}

impl IsolationSource {
    /// `kind` is one of `"header"`, `"query_parameter"`, `"cookie"`.
    fn new(kind: &str, name: impl Into<String>) -> Self {
        Self {
            kind: kind.to_string(),
            name: name.into(),
        }
    }

    /// Encode as `"<kind>:<name>"`.
    pub fn encode(&self) -> String {
        format!("{}:{}", self.kind, self.name)
    }
}

/// Isolate conversations by a request header.
pub fn header(name: impl Into<String>) -> IsolationSource {
    IsolationSource::new("header", name)
}

/// Isolate conversations by a query parameter.
pub fn query_parameter(name: impl Into<String>) -> IsolationSource {
    IsolationSource::new("query_parameter", name)
}

/// Isolate conversations by a cookie.
pub fn cookie(name: impl Into<String>) -> IsolationSource {
    IsolationSource::new("cookie", name)
}

// ---------------------------------------------------------------------------
// LlmMockBuilder — a single completion or embedding mock
// ---------------------------------------------------------------------------

/// Fluent builder for a single LLM mock expectation
/// (mirrors `org.mockserver.client.LlmMockBuilder`).
#[derive(Debug, Clone)]
pub struct LlmMockBuilder {
    path: String,
    provider: Option<String>,
    model: Option<String>,
    completion: Option<Completion>,
    embedding: Option<EmbeddingResponse>,
}

impl LlmMockBuilder {
    /// Set the LLM provider (use a [`Provider`] constant).
    pub fn with_provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }

    /// Set the model name.
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    /// Set the response body. Passing a [`Completion`] clears any embedding and
    /// vice-versa.
    pub fn responding_with(mut self, response: impl Into<LlmResponseBody>) -> Self {
        match response.into() {
            LlmResponseBody::Completion(c) => {
                self.completion = Some(c);
                self.embedding = None;
            }
            LlmResponseBody::Embedding(e) => {
                self.embedding = Some(e);
                self.completion = None;
            }
        }
        self
    }

    /// Build the single expectation JSON.
    pub fn build(&self) -> Value {
        let mut map = Map::new();
        map.insert("httpRequest".to_string(), post_matcher(&self.path));
        map.insert(
            "httpLlmResponse".to_string(),
            build_llm_response(
                self.provider.as_deref(),
                self.model.as_deref(),
                self.completion.as_ref(),
                self.embedding.as_ref(),
                None,
                None,
            ),
        );
        Value::Object(map)
    }

    /// Build and register the expectation on the given client.
    pub fn apply_to(&self, client: &MockServerClient) -> Result<Value> {
        client.upsert_raw(self.build())
    }
}

/// Entry point mirroring `LlmMockBuilder.llmMock(path)`.
pub fn llm_mock(path: impl Into<String>) -> LlmMockBuilder {
    LlmMockBuilder {
        path: path.into(),
        provider: None,
        model: None,
        completion: None,
        embedding: None,
    }
}

// ---------------------------------------------------------------------------
// LlmConversationBuilder + TurnBuilder
// ---------------------------------------------------------------------------

const SCENARIO_PREFIX: &str = "__llm_conv_";
const DONE_STATE: &str = "__done";

/// Sub-builder configuring one turn of a conversation mock
/// (mirrors `org.mockserver.client.TurnBuilder`).
#[derive(Debug, Clone, Default)]
pub struct TurnBuilder {
    turn_index: Option<i64>,
    latest_message_contains: Option<String>,
    latest_message_matches: Option<String>,
    latest_message_role: Option<String>,
    contains_tool_result_for: Option<String>,
    semantic_match_against: Option<String>,
    normalization: Option<Value>,
    chaos: Option<Value>,
    completion: Option<Completion>,
}

impl TurnBuilder {
    /// Match when the conversation is at the given (0-based) turn index.
    pub fn when_turn_index(mut self, n: i64) -> Self {
        self.turn_index = Some(n);
        self
    }

    /// Match when the latest message contains the given substring.
    pub fn when_latest_message_contains(mut self, text: impl Into<String>) -> Self {
        self.latest_message_contains = Some(text.into());
        self
    }

    /// Match when the latest message matches the given regex.
    pub fn when_latest_message_matches(mut self, regex: impl Into<String>) -> Self {
        self.latest_message_matches = Some(regex.into());
        self
    }

    /// Match when the latest message has the given role (use a [`Role`] constant).
    pub fn when_latest_message_role(mut self, role: impl Into<String>) -> Self {
        self.latest_message_role = Some(role.into());
        self
    }

    /// Match when the latest message contains a tool result for the given tool.
    pub fn when_contains_tool_result_for(mut self, tool_name: impl Into<String>) -> Self {
        self.contains_tool_result_for = Some(tool_name.into());
        self
    }

    /// Match semantically against the given expected meaning.
    pub fn when_semantic_match(mut self, expected_meaning: impl Into<String>) -> Self {
        self.semantic_match_against = Some(expected_meaning.into());
        self
    }

    /// Set normalization options (a JSON object). Not counted as a predicate.
    pub fn with_normalization(mut self, normalization: Value) -> Self {
        self.normalization = Some(normalization);
        self
    }

    /// Set a chaos profile (a JSON object) for this turn.
    pub fn with_chaos(mut self, chaos: Value) -> Self {
        self.chaos = Some(chaos);
        self
    }

    /// Set the completion this turn responds with.
    pub fn responding_with(mut self, completion: Completion) -> Self {
        self.completion = Some(completion);
        self
    }

    /// Build the `conversationPredicates` object (always, even when empty).
    fn predicates_value(&self) -> Value {
        let mut map = Map::new();
        insert_some(&mut map, "turnIndex", self.turn_index.map(Value::from));
        insert_some(
            &mut map,
            "latestMessageContains",
            self.latest_message_contains.clone().map(Value::from),
        );
        insert_some(
            &mut map,
            "latestMessageMatches",
            self.latest_message_matches.clone().map(Value::from),
        );
        insert_some(
            &mut map,
            "latestMessageRole",
            self.latest_message_role.clone().map(Value::from),
        );
        insert_some(
            &mut map,
            "containsToolResultFor",
            self.contains_tool_result_for.clone().map(Value::from),
        );
        insert_some(
            &mut map,
            "semanticMatchAgainst",
            self.semantic_match_against.clone().map(Value::from),
        );
        insert_some(&mut map, "normalization", self.normalization.clone());
        Value::Object(map)
    }

    /// True if at least one predicate (excluding `normalization`) is set.
    fn has_any_predicate(&self) -> bool {
        self.turn_index.is_some()
            || self.latest_message_contains.is_some()
            || self.latest_message_matches.is_some()
            || self.latest_message_role.is_some()
            || self.contains_tool_result_for.is_some()
            || self.semantic_match_against.is_some()
    }
}

/// Builder for multi-turn LLM conversation mocks with scenario advancement
/// (mirrors `org.mockserver.client.LlmConversationBuilder`).
#[derive(Debug, Clone, Default)]
pub struct LlmConversationBuilder {
    path: Option<String>,
    provider: Option<String>,
    model: Option<String>,
    isolation_source: Option<IsolationSource>,
    turns: Vec<TurnBuilder>,
}

impl LlmConversationBuilder {
    /// Set the request path.
    pub fn with_path(mut self, path: impl Into<String>) -> Self {
        self.path = Some(path.into());
        self
    }

    /// Set the LLM provider (use a [`Provider`] constant).
    pub fn with_provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }

    /// Set the model name.
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    /// Isolate per-session conversations by the given source.
    pub fn isolate_by(mut self, source: IsolationSource) -> Self {
        self.isolation_source = Some(source);
        self
    }

    /// Add a turn to the conversation. Build the turn fluently and pass it back.
    pub fn turn(mut self, turn: TurnBuilder) -> Self {
        self.turns.push(turn);
        self
    }

    /// Build the list of conversation expectations.
    ///
    /// The auto-generated scenario id makes the output non-deterministic between
    /// calls; tests should assert structure, not the exact uuid.
    pub fn build(&self) -> Vec<Value> {
        self.build_with_id(&new_uuid())
    }

    /// Build with an explicit conversation id (used by tests for determinism).
    pub fn build_with_id(&self, conversation_uuid: &str) -> Vec<Value> {
        assert!(!self.turns.is_empty(), "At least one turn must be defined");
        let path = self.path.as_deref().expect("Path must be set");
        let provider = self.provider.as_deref().expect("Provider must be set");

        let mut scenario_name = format!("{SCENARIO_PREFIX}{conversation_uuid}");
        if let Some(source) = &self.isolation_source {
            scenario_name = format!("{scenario_name}{ISOLATION_MARKER}{}", source.encode());
        }

        let n = self.turns.len();
        let mut expectations = Vec::with_capacity(n);
        for (i, turn) in self.turns.iter().enumerate() {
            let scenario_state = if i == 0 {
                "Started".to_string()
            } else {
                format!("turn_{i}")
            };
            let new_scenario_state = if i < n - 1 {
                format!("turn_{}", i + 1)
            } else {
                DONE_STATE.to_string()
            };

            let predicates = if turn.has_any_predicate() {
                Some(turn.predicates_value())
            } else {
                None
            };

            let llm_response = build_llm_response(
                Some(provider),
                self.model.as_deref(),
                turn.completion.as_ref(),
                None,
                predicates,
                turn.chaos.clone(),
            );

            let mut map = Map::new();
            map.insert("httpRequest".to_string(), post_matcher(path));
            map.insert("scenarioName".to_string(), Value::from(scenario_name.clone()));
            map.insert("scenarioState".to_string(), Value::from(scenario_state));
            map.insert("newScenarioState".to_string(), Value::from(new_scenario_state));
            map.insert("httpLlmResponse".to_string(), llm_response);
            expectations.push(Value::Object(map));
        }
        expectations
    }

    /// Build and register the expectations on the given client.
    pub fn apply_to(&self, client: &MockServerClient) -> Result<Value> {
        client.upsert_raw(Value::Array(self.build()))
    }
}

/// Entry point mirroring `LlmConversationBuilder.conversation()`.
pub fn conversation() -> LlmConversationBuilder {
    LlmConversationBuilder::default()
}

/// Entry point mirroring `TurnBuilder` construction (used with
/// [`LlmConversationBuilder::turn`]).
pub fn turn() -> TurnBuilder {
    TurnBuilder::default()
}

// ---------------------------------------------------------------------------
// LlmFailoverBuilder — N failures then a success completion
// ---------------------------------------------------------------------------

/// The default error body for the given status code (mirrors the Java/Node/Python
/// `defaultErrorBody`). Exposed for parity/testing.
pub fn default_error_body(status_code: u16) -> String {
    let (type_, message) = match status_code {
        429 => (
            "rate_limit_error",
            "Rate limit exceeded. Please retry after a brief wait.".to_string(),
        ),
        500 => (
            "internal_server_error",
            "An internal error occurred. Please retry your request.".to_string(),
        ),
        502 => (
            "bad_gateway",
            "Bad gateway. The upstream server returned an invalid response.".to_string(),
        ),
        503 => (
            "service_unavailable",
            "The service is temporarily overloaded. Please retry later.".to_string(),
        ),
        _ => ("error", format!("Request failed with status {status_code}")),
    };
    // Compact, fixed-order JSON exactly matching the other clients.
    format!(r#"{{"error":{{"type":"{type_}","message":"{message}"}}}}"#)
}

#[derive(Debug, Clone, PartialEq)]
struct FailureSpec {
    status_code: u16,
    error_body: Option<String>,
}

/// Builder for provider failover/retry scenarios
/// (mirrors `org.mockserver.client.LlmFailoverBuilder`).
///
/// Produces an ordered list of expectations: failure expectations (limited
/// `times`) first, then a single success expectation with unlimited `times`.
/// Consecutive identical failures are coalesced into one expectation with
/// `times.remainingTimes = count`.
#[derive(Debug, Clone, Default)]
pub struct LlmFailoverBuilder {
    path: Option<String>,
    provider: Option<String>,
    model: Option<String>,
    failures: Vec<FailureSpec>,
    success_completion: Option<Completion>,
}

impl LlmFailoverBuilder {
    /// Set the request path.
    pub fn with_path(mut self, path: impl Into<String>) -> Self {
        self.path = Some(path.into());
        self
    }

    /// Set the LLM provider (use a [`Provider`] constant).
    pub fn with_provider(mut self, provider: impl Into<String>) -> Self {
        self.provider = Some(provider.into());
        self
    }

    /// Set the model name.
    pub fn with_model(mut self, model: impl Into<String>) -> Self {
        self.model = Some(model.into());
        self
    }

    /// Add a single failure with the default error body for `status_code`.
    ///
    /// # Panics
    /// Panics if `status_code` is outside `100..=599`.
    pub fn fail_with(self, status_code: u16) -> Self {
        self.fail_with_count(status_code, 1)
    }

    /// Add a single failure with a custom error body.
    ///
    /// # Panics
    /// Panics if `status_code` is outside `100..=599`.
    pub fn fail_with_body(mut self, status_code: u16, error_body: impl Into<String>) -> Self {
        validate_status_code(status_code);
        self.failures.push(FailureSpec {
            status_code,
            error_body: Some(error_body.into()),
        });
        self
    }

    /// Add `count` consecutive failures with the default error body.
    ///
    /// # Panics
    /// Panics if `status_code` is outside `100..=599` or `count < 1`.
    pub fn fail_with_count(mut self, status_code: u16, count: u32) -> Self {
        validate_status_code(status_code);
        assert!(count >= 1, "count must be >= 1");
        for _ in 0..count {
            self.failures.push(FailureSpec {
                status_code,
                error_body: None,
            });
        }
        self
    }

    /// Set the success completion served after all failures are consumed.
    pub fn then_respond_with(mut self, completion: Completion) -> Self {
        self.success_completion = Some(completion);
        self
    }

    /// Number of failure attempts configured.
    pub fn failure_count(&self) -> usize {
        self.failures.len()
    }

    fn coalesce(&self) -> Vec<(u16, Option<String>, u32)> {
        let mut result: Vec<(u16, Option<String>, u32)> = Vec::new();
        for spec in &self.failures {
            if let Some(last) = result.last_mut() {
                if last.0 == spec.status_code && last.1 == spec.error_body {
                    last.2 += 1;
                    continue;
                }
            }
            result.push((spec.status_code, spec.error_body.clone(), 1));
        }
        result
    }

    /// Build the list of failover expectations.
    pub fn build(&self) -> Vec<Value> {
        let path = self.path.as_deref().expect("Path must be set");
        let provider = self.provider.as_deref().expect("Provider must be set");
        assert!(!self.failures.is_empty(), "At least one failure must be defined");
        let success = self
            .success_completion
            .as_ref()
            .expect("Success completion must be set via then_respond_with()");

        let mut expectations = Vec::new();
        for (status_code, error_body, count) in self.coalesce() {
            let body = error_body.unwrap_or_else(|| default_error_body(status_code));
            expectations.push(json!({
                "httpRequest": post_matcher(path),
                "times": { "remainingTimes": count, "unlimited": false },
                "timeToLive": { "unlimited": true },
                "httpResponse": {
                    "statusCode": status_code,
                    "headers": [{ "name": "Content-Type", "values": ["application/json"] }],
                    "body": body
                }
            }));
        }

        let mut success_map = Map::new();
        success_map.insert("httpRequest".to_string(), post_matcher(path));
        success_map.insert(
            "times".to_string(),
            json!({ "remainingTimes": 0, "unlimited": true }),
        );
        success_map.insert("timeToLive".to_string(), json!({ "unlimited": true }));
        success_map.insert(
            "httpLlmResponse".to_string(),
            build_llm_response(
                Some(provider),
                self.model.as_deref(),
                Some(success),
                None,
                None,
                None,
            ),
        );
        expectations.push(Value::Object(success_map));
        expectations
    }

    /// Build and register the expectations on the given client.
    pub fn apply_to(&self, client: &MockServerClient) -> Result<Value> {
        client.upsert_raw(Value::Array(self.build()))
    }
}

/// Entry point mirroring `LlmFailoverBuilder.llmFailover()`.
pub fn llm_failover() -> LlmFailoverBuilder {
    LlmFailoverBuilder::default()
}

fn validate_status_code(status_code: u16) {
    assert!(
        (100..=599).contains(&status_code),
        "statusCode must be between 100 and 599"
    );
}

// ---------------------------------------------------------------------------
// Minimal RFC4122 v4-ish UUID (dependency-free) for scenario names.
// ---------------------------------------------------------------------------

fn new_uuid() -> String {
    // Derive 16 pseudo-random bytes from a few entropy sources. This does not
    // need to be cryptographically strong — it only needs to be unique enough
    // to keep concurrent conversation scenarios from colliding.
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    // A process-wide monotonic counter guarantees that two calls never share the
    // same input even if every other entropy source (clock, stack address, the
    // per-call RandomState seeds) happened to collide.
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let seq = COUNTER.fetch_add(1, Ordering::Relaxed);

    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let stack_marker = &nanos as *const _ as usize;

    let mut hasher = RandomState::new().build_hasher();
    hasher.write_u128(nanos);
    hasher.write_usize(stack_marker);
    hasher.write_u64(seq);
    let h1 = hasher.finish();
    let mut hasher2 = RandomState::new().build_hasher();
    hasher2.write_u64(h1);
    hasher2.write_u128(nanos.rotate_left(17));
    hasher2.write_u64(seq.rotate_left(32));
    let h2 = hasher2.finish();

    let mut bytes = [0u8; 16];
    bytes[..8].copy_from_slice(&h1.to_le_bytes());
    bytes[8..].copy_from_slice(&h2.to_le_bytes());
    // version 4 + variant bits
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;

    let h = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
    format!(
        "{}-{}-{}-{}-{}",
        h(&bytes[0..4]),
        h(&bytes[4..6]),
        h(&bytes[6..8]),
        h(&bytes[8..10]),
        h(&bytes[10..16]),
    )
}
