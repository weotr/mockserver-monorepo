// Example MockServer WASM body-matcher rule (Go / TinyGo).
//
// ## The MockServer WASM ABI
//
// MockServer's WasmRuntime (chicory interpreter) drives a module like this:
//
//  1. It writes the HTTP request body into the module's exported linear memory
//     starting at offset 0.
//  2. It calls the exported function match(ptr i32, len i32) -> i32 with
//     ptr = 0 and len = <body length in bytes>.
//  3. A non-zero return value means "this rule matches"; zero means "no match".
//
// There are no host imports and no WASI -- the module must be freestanding.
// We build with TinyGo's wasm-unknown target (via the custom target file
// mockserver-wasm.json that fixes memory export) to produce exactly that.
//
// ## Why TinyGo, not standard Go?
//
// Standard Go's WebAssembly support targets js/wasm (needs a JS runtime) or
// wasip1 (needs WASI). Neither produces a freestanding module. TinyGo's
// wasm-unknown target emits a minimal WASM binary with no host dependencies.
//
// ## Export directive
//
// TinyGo uses the //export comment directive to set the WASM export name of
// a function. This is critical here because "match" is a reserved keyword in
// Go and cannot be used as a function name. The //export directive decouples
// the WASM export name ("match") from the Go identifier ("matchRule").
//
// Note: TinyGo also supports //go:export as a synonym, and standard Go 1.24+
// introduced //go:wasmexport. We use //export because it is the canonical
// TinyGo directive, works across all TinyGo versions that support
// wasm-unknown, and is the form shown in TinyGo's own documentation.
//
// ## What this example matches
//
// A realistic rule rather than a toy: it parses the body as text and matches
// when it contains a JSON-style "amount": <number> whose value is greater
// than 1000 -- e.g. flag "large" payment requests. This matches the sibling
// Rust example so the two are directly comparable.

package main

import "unsafe"

// matchRule is called by MockServer via the WASM export "match".
// ptr and len describe a region of linear memory that MockServer wrote the
// HTTP request body into. We read it, scan for "amount": <number>, and
// return 1 if the number exceeds 1000, 0 otherwise.
//
//export match
func matchRule(ptr int32, length int32) int32 {
	if ptr < 0 || length <= 0 {
		return 0
	}

	// Build a byte slice from the raw pointer and length.
	// Safety: MockServer guarantees `length` bytes of request body live at
	// `ptr` in our linear memory, so this is safe to read.
	// Note: go vet warns "possible misuse of unsafe.Pointer" for the int-to-
	// pointer conversion below. This is a false positive -- in WASM, linear
	// memory addresses ARE integer offsets and this is the standard pattern
	// used by TinyGo itself and the broader WASM-Go ecosystem.
	body := unsafe.Slice((*byte)(unsafe.Pointer(uintptr(ptr))), int(length))

	amount, found := findAmount(body)
	if found && amount > 1000 {
		return 1
	}
	return 0
}

// findAmount scans the body for the byte sequence "amount" followed by a
// colon and an integer. Returns the integer value and true if found.
// Deliberately dependency-free (no encoding/json) to keep the module tiny.
func findAmount(body []byte) (int64, bool) {
	key := []byte(`"amount"`)
	keyLen := len(key)

	for i := 0; i+keyLen <= len(body); i++ {
		if matchBytes(body[i:i+keyLen], key) {
			return parseNumberAfterColon(body[i+keyLen:])
		}
	}
	return 0, false
}

// matchBytes returns true if a and b are equal byte-for-byte.
// We avoid bytes.Equal to stay dependency-free in the freestanding build.
func matchBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// parseNumberAfterColon skips optional whitespace and a single ':', then
// parses the following ASCII digits as a non-negative integer.
func parseNumberAfterColon(rest []byte) (int64, bool) {
	j := 0
	seenColon := false

	// Skip whitespace and exactly one colon.
	for j < len(rest) {
		ch := rest[j]
		if ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' {
			j++
		} else if ch == ':' && !seenColon {
			seenColon = true
			j++
		} else {
			break
		}
	}

	if !seenColon {
		return 0, false
	}

	// Skip whitespace after the colon.
	for j < len(rest) {
		ch := rest[j]
		if ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' {
			j++
		} else {
			break
		}
	}

	// Parse digits.
	var value int64
	any := false
	for j < len(rest) && rest[j] >= '0' && rest[j] <= '9' {
		digit := int64(rest[j] - '0')
		// Saturating multiply-add to avoid overflow.
		newValue := value*10 + digit
		if newValue < value {
			// Overflow -- saturate at max int64.
			value = 1<<63 - 1
		} else {
			value = newValue
		}
		any = true
		j++
	}

	if !any {
		return 0, false
	}
	return value, true
}

// main is required by TinyGo for the wasm-unknown target but is never called
// by MockServer. The runtime initialises via main, then the host calls the
// exported "match" function directly.
func main() {}
