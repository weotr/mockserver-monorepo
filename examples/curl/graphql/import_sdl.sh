#!/usr/bin/env bash
# Import a GraphQL SDL (Schema Definition Language) document.
# MockServer parses the schema and auto-generates expectations that return
# schema-valid example responses for queries and mutations sent to /graphql.
# The request body is the raw SDL (Content-Type: application/graphql), not JSON.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/graphql" \
-H "Content-Type: application/graphql" \
--data-raw '
type Query {
  user(id: ID!): User
  products(category: String): [Product]
}
type Mutation {
  createOrder(input: OrderInput!): Order
}
type User    { id: ID! name: String email: String }
type Product { id: ID! name: String price: Float inStock: Boolean }
type Order   { id: ID! status: String total: Float }
input OrderInput { productId: ID! quantity: Int }
'
