//! Example MockServer WASM body-matcher rule (Rust).
//!
//! ## The MockServer WASM ABI
//!
//! MockServer's `WasmRuntime` (chicory interpreter) drives a module like this:
//!
//! 1. It writes the HTTP request body into the module's exported linear memory
//!    starting at **offset 0**.
//! 2. It calls the exported function `match(ptr: i32, len: i32) -> i32` with
//!    `ptr = 0` and `len = <body length in bytes>`.
//! 3. A **non-zero** return value means "this rule matches"; zero means "no match".
//!
//! There are **no host imports / no WASI** — the module must be freestanding.
//! Building for the `wasm32-unknown-unknown` target with `crate-type = cdylib`
//! gives exactly that, and Rust exports the linear memory as `memory` by default.
//!
//! ## What this example matches
//!
//! A realistic rule rather than a toy: it parses the body as text and matches
//! when it contains a JSON-style `"amount": <number>` whose value is **greater
//! than 1000** — e.g. flag "large" payment requests. This shows real body
//! inspection, not just a fixed-string compare.

#![no_std]

// wasm32-unknown-unknown has no default panic handler without std; provide one.
// `panic = "abort"` in Cargo.toml means this simply traps.
#[cfg(not(test))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

/// The function MockServer calls. `#[export_name = "match"]` is required because
/// `match` is a reserved keyword in Rust and cannot be used as an identifier.
///
/// # Safety
/// `ptr`/`len` describe a region MockServer wrote into our linear memory. We only
/// read it, and only within `[ptr, ptr+len)`.
#[export_name = "match"]
pub extern "C" fn match_rule(ptr: i32, len: i32) -> i32 {
    if ptr < 0 || len <= 0 {
        return 0;
    }
    // SAFETY: MockServer guarantees `len` bytes of request body live at `ptr`.
    let body = unsafe { core::slice::from_raw_parts(ptr as *const u8, len as usize) };

    match find_amount(body) {
        Some(amount) if amount > 1000 => 1,
        _ => 0,
    }
}

/// Find `"amount": <number>` in the (UTF-8-ish) body and return the integer part.
/// Deliberately dependency-free (no serde) to keep the module tiny and `no_std`.
fn find_amount(body: &[u8]) -> Option<i64> {
    const KEY: &[u8] = b"\"amount\"";
    let mut i = 0usize;
    while i + KEY.len() <= body.len() {
        if &body[i..i + KEY.len()] == KEY {
            return parse_number_after_colon(&body[i + KEY.len()..]);
        }
        i += 1;
    }
    None
}

/// Given the slice just after the key, skip whitespace + ':' and parse digits.
fn parse_number_after_colon(rest: &[u8]) -> Option<i64> {
    let mut j = 0usize;
    // skip whitespace and a single ':'
    let mut seen_colon = false;
    while j < rest.len() {
        match rest[j] {
            b' ' | b'\t' | b'\r' | b'\n' => j += 1,
            b':' if !seen_colon => {
                seen_colon = true;
                j += 1;
            }
            _ => break,
        }
    }
    if !seen_colon {
        return None;
    }
    let mut value: i64 = 0;
    let mut any = false;
    while j < rest.len() && rest[j].is_ascii_digit() {
        value = value.saturating_mul(10).saturating_add((rest[j] - b'0') as i64);
        any = true;
        j += 1;
    }
    if any {
        Some(value)
    } else {
        None
    }
}
