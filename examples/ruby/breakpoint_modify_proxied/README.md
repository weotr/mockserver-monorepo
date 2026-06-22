# Breakpoint: Modify Proxied Exchange

## What it demonstrates

Using an interactive RESPONSE-phase breakpoint to modify a proxied (forwarded) response before it reaches the caller. The example is fully self-contained using a loopback forward (the MockServer forwards to itself):

1. A mock "upstream" endpoint returns a JSON greeting
2. A forward expectation routes `/service/greeting` to the upstream mock via `socketAddress` loopback
3. A RESPONSE-phase breakpoint intercepts the upstream response and injects additional fields
4. The caller receives the modified response

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080` with breakpoint support

## Run

```bash
ruby breakpoint_modify_proxied.rb
```

## Expected output

```
1. Created upstream mock: GET /upstream/greeting -> 200 JSON
2. Created forward expectation: GET /service/greeting -> loopback to /upstream/greeting
   Breakpoint registered with id: <uuid>
3. Breakpoint fired! Original response body: {"message":"Hello from upstream","source":"original"}
4. Sending GET /service/greeting ...

--- Response received ---
Status: 200
Body:   {"message":"Hello from upstream","source":"modified-by-breakpoint","injectedField":"this was added by the breakpoint handler"}

Breakpoint successfully modified the proxied response!

Breakpoint matchers cleared.
MockServer reset.
```
