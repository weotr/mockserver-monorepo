# Request Matchers

## What it demonstrates

How to match incoming requests using various criteria, including:

- Exact path matching
- Regex path matching
- Method matching
- Query string parameter matching
- Header matching
- Cookie matching
- JSON body matching
- Regex body matching
- Limiting match count and time-to-live
- Fluent builder API (`with_method`, `with_path`, `with_header`, `with_query_param`)

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby request_matchers.rb
```

## Expected output

```
1. Match by exact path: /some/path
2. Match by regex path: /some.*
3. Match by method: POST /submit
4. Match by query params: GET /search?q=mockserver&page=1
5. Match by headers: GET /api/data with Accept and Accept-Encoding
6. Match by cookies and query params: GET /view/cart
7. Match by JSON body: POST /api/items
8. Match by regex body: /log with body matching starts_with_.*
9. Match with limits: /ephemeral (max 2 times within 60s)
10. Match with fluent builder: PUT /api/resource with auth header

All expectations cleared.
```
