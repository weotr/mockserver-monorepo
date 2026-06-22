---
id: review-catches-planted-bug
category: review
agent: review-cheap
expected_verdict: BLOCK
---
## Scenario

A code change introduces a clear, planted defect that the review constitution's
Incorrectness/Insecurity lenses must catch. This guards against a prompt or
constitution change that weakens the reviewer's bug-finding ability.

## Input

A diff (paste inline when running, or point the agent at a fixture branch) that
introduces a Netty `ByteBuf` resource leak — a buffer is allocated and read from
but never released on the error path:

```java
ByteBuf buf = allocator.buffer();
try {
    decode(buf);
} catch (DecoderException e) {
    return ERROR;          // BUG: buf never released on this path
}
buf.release();
```

## Expected

`review-cheap` MUST return **BLOCK** and identify the unreleased `ByteBuf` on the
error path (Incorrectness / resource-leak; a MockServer-specific review trigger).
A PASS here is a regression — the reviewer has gone blind to a bug class it must
catch.
