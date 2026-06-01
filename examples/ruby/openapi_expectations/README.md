# OpenAPI Expectations

## What it demonstrates

How to create expectations directly from an OpenAPI (Swagger) specification, including:

- Loading an OpenAPI spec by URL and creating expectations for all operations
- Selecting specific operations and their response status codes

## Prerequisites

- Ruby 3.0+
- `mockserver-client` gem installed
- MockServer running on `localhost:1080`
- Network access to fetch the Petstore OpenAPI spec from GitHub (or use a local spec file)

## Run

```bash
ruby openapi_expectations.rb
```

## Expected output

```
1. Created expectations from Petstore OpenAPI spec (all operations)
2. Created expectations: showPetById->200, createPets->500

All expectations cleared.
```
