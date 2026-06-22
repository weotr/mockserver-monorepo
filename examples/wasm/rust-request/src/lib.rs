//! Example MockServer WASM matcher using the **richer ABI** (method, path, headers)
//! via [`mockserver_wasm_sdk`].
//!
//! It matches when **all** of the following hold, demonstrating that a rule can read
//! more than just the body:
//!
//! * the method is `POST`,
//! * the path is exactly `/orders`,
//! * the `X-Tenant` header equals `acme` (case-insensitive header name).
//!
//! Because it exports `match_request`, MockServer passes the full JSON request
//! envelope; body-only modules that export `match` continue to work unchanged.

#![no_std]

use mockserver_wasm_sdk::{export_match_request, Request};

fn rule(req: &Request) -> bool {
    req.method() == "POST"
        && req.path() == "/orders"
        && req.header("X-Tenant") == Some("acme")
}

export_match_request!(rule);
