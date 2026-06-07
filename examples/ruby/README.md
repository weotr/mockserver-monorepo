# MockServer Ruby Examples

Runnable examples demonstrating the [MockServer Ruby client](../../mockserver-client-ruby/).

## Prerequisites

- **Ruby 3.0+**
- **MockServer running** on `localhost:1080` (e.g. `docker run -d -p 1080:1080 mockserver/mockserver`)
- **Install the client gem** -- either from the local source:

  ```bash
  cd ../../mockserver-client-ruby && gem build mockserver-client.gemspec && gem install mockserver-client-*.gem
  ```

  or from RubyGems:

  ```bash
  gem install mockserver-client
  ```

## Scenarios

| Folder | Description |
|--------|-------------|
| [response_action](response_action/) | Return a canned HTTP response (status, headers, body, delay) |
| [forward_action](forward_action/) | Forward requests to another host or override the forwarded request |
| [request_matchers](request_matchers/) | Match requests by path, method, headers, query params, cookies, and body |
| [openapi_expectations](openapi_expectations/) | Create expectations from an OpenAPI specification |
| [verify_requests](verify_requests/) | Verify that MockServer received specific requests |
| [retrieve_recorded_requests](retrieve_recorded_requests/) | Retrieve requests that MockServer has recorded |
| [reverse_proxy](reverse_proxy/) | Use MockServer as a reverse proxy in front of real services |
