# Class (and object) Callbacks

## What it demonstrates

How to register **callback** actions with the MockServer Ruby client.

**Class callbacks** (the required, always-run deliverable) reference a
server-side Java class that implements a callback interface. They are pure,
declarative, REST-only actions -- no WebSocket -- so the wire shape can be
validated without the referenced class existing on the server. The example:

- Sets a **response** class callback from a fully-qualified class-name `String`
  (wrapped automatically into an `HttpClassCallback`)
- Sets a **response** class callback from an `HttpClassCallback` carrying an
  optional `delay` and `primary`
- Sets a **forward** class callback from a `String`
- Uses the fluent builder `when(...).respond_with_class_callback(...)` and
  `when(...).forward_with_class_callback(...)`
- Reads the active expectations back to confirm the server stored
  `httpResponseClassCallback.callbackClass` / `httpForwardClassCallback`

It also **best-effort** demonstrates an **object/closure callback** (the
response is written in Ruby over a WebSocket via `mock_with_callback`). That
step is skipped -- not failed -- if a callback WebSocket is unavailable.

## API

Either pass a class-name `String` or a pre-built `HttpClassCallback`:

```ruby
# On the Expectation model (constructor or setter)
Expectation.new(
  http_request: HttpRequest.new(method: 'GET', path: '/things'),
  http_response_class_callback: 'com.example.MyResponseCallback'
)

exp = Expectation.new(http_request: HttpRequest.new(path: '/things'))
exp.http_forward_class_callback = HttpClassCallback.new(
  callback_class: 'com.example.MyForwardCallback',
  delay: Delay.new(time_unit: 'SECONDS', value: 1),
  primary: true
)

# Via the fluent builder
client.when(HttpRequest.request(path: '/things'))
      .respond_with_class_callback('com.example.MyResponseCallback')

client.when(HttpRequest.request(path: '/forward'))
      .forward_with_class_callback('com.example.MyForwardCallback')
```

A `String` serializes to `{"callbackClass": "..."}`; an `HttpClassCallback`
additionally carries `delay` and `primary`.

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby callback.rb
```

The server location can be overridden with `MOCKSERVER_HOST` / `MOCKSERVER_PORT`.

## Expected output

```
PASS: response class callback (String) accepted (status=201)
PASS: response class callback (HttpClassCallback, delay+primary) accepted (status=201)
PASS: forward class callback (String) accepted (status=201)
PASS: fluent respond_with_class_callback accepted
PASS: fluent forward_with_class_callback accepted
PASS: class-callback expectations round-trip from the server with the expected wire shape
PASS: object/closure callback produced a Ruby-written response

All class-callback assertions passed.
```

(The final object-callback line may instead read `SKIP: ...` if a callback
WebSocket is unavailable; the class-callback assertions are the required
deliverable.)
