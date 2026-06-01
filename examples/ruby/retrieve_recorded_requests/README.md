# Retrieve Recorded Requests

## What it demonstrates

How to retrieve requests that MockServer has recorded, including:

- Retrieving all recorded requests
- Filtering recorded requests by method and path
- Retrieving active expectations

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`

## Run

```bash
ruby retrieve_recorded_requests.rb
```

## Expected output

```
Sent 3 requests (2x GET, 1x POST to /api/users)

1. All recorded requests: 3 total
   [0] GET /api/users
   [1] POST /api/users
   [2] GET /api/users

2. POST /api/users requests: 1
   [0] POST /api/users

3. Active expectations: 2
   [0] * /api/users
   [1] POST /api/users

All expectations cleared.
```
