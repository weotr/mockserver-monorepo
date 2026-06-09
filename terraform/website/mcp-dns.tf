# MCP registry DNS namespace verification.
#
# The official MCP registry (registry.modelcontextprotocol.io) verifies
# ownership of the `com.mock-server` namespace via an apex TXT record carrying
# an ed25519 public key. The matching private key lives in Secrets Manager
# (mockserver-release/mcp-dns-key) and the release pipeline's mcp.sh
# authenticates with it. Exact record format confirmed by `mcp-publisher login
# dns` ("Expected proof record: v=MCPv1; k=ed25519; p=<base64>").
# See docs/operations/mcp-registry-publishing.md.
#
# The apex (mock-server.com) already carries a Google Search Console
# verification TXT value. Route 53 holds ONE record set per name+type, so this
# resource manages the apex TXT set with BOTH values. `allow_overwrite = true`
# adopts the pre-existing (previously unmanaged) record without a separate
# `terraform import`. Keep `google_site_verification` in sync with the live
# value or it will be dropped on apply.

variable "mcp_dns_public_key" {
  type        = string
  default     = ""
  description = "base64 ed25519 public key for MCP registry DNS verification of the com.mock-server namespace. Empty => the MCP value is omitted from the apex TXT set."
}

variable "google_site_verification" {
  type        = string
  default     = "google-site-verification=QIzvnb0rY1MnkLjIDI217X0RjxZCX2tWdUp1CvejoVw"
  description = "Existing apex TXT value for Google Search Console site verification, preserved when managing the apex TXT record set."
}

resource "aws_route53_record" "apex_txt" {
  zone_id         = var.zone_id
  name            = var.domain
  type            = "TXT"
  ttl             = 300
  allow_overwrite = true

  records = compact([
    var.google_site_verification,
    var.mcp_dns_public_key != "" ? "v=MCPv1; k=ed25519; p=${var.mcp_dns_public_key}" : "",
  ])
}

output "apex_txt_values" {
  description = "Values published in the apex TXT record set (Google verification + MCP proof)."
  value       = aws_route53_record.apex_txt.records
}
