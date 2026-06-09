//! # mockserver-client
//!
//! An idiomatic Rust client for [MockServer](https://www.mock-server.com)'s
//! control-plane REST API.
//!
//! This crate provides a blocking (synchronous) client that communicates with a
//! running MockServer instance over HTTP. It supports creating expectations,
//! verifying requests, clearing/resetting state, and retrieving recorded data.
//!
//! # Quick Start
//!
//! ```no_run
//! use mockserver_client::{ClientBuilder, HttpRequest, HttpResponse};
//!
//! let client = ClientBuilder::new("localhost", 1080).build().unwrap();
//!
//! client.when(HttpRequest::new().method("GET").path("/hello"))
//!     .respond(HttpResponse::new().status_code(200).body("world"))
//!     .unwrap();
//!
//! client.verify(
//!     HttpRequest::new().path("/hello"),
//!     mockserver_client::VerificationTimes::at_least(1),
//! ).unwrap();
//!
//! client.reset().unwrap();
//! ```

mod model;
mod client;
mod error;

pub use client::{ClientBuilder, MockServerClient, ForwardChainExpectation};
pub use error::{Error, Result};
pub use model::*;
