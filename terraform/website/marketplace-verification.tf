# VS Code Marketplace publisher verification.
#
# The Visual Studio Marketplace verifies domain ownership for the `mock-server`
# publisher via a TXT record at a provider-specified hostname. The value is a
# verification code issued in the Marketplace publisher UI (Verify domain flow).
#
# `allow_overwrite = true` adopts the record that was first created out-of-band
# via the Route 53 API (to start the up-to-72h verification clock immediately)
# without a separate `terraform import`.
#
# This may be removed once the publisher domain shows as verified AND Marketplace
# no longer re-checks it; keeping it is harmless and guards against silent
# re-verification. To rotate, update var.vscode_marketplace_verification with the
# new code from the Marketplace UI.

variable "vscode_marketplace_verification" {
  type        = string
  default     = "fc3a574c-c9fc-4e71-8912-3406cee9031a"
  description = "VS Code Marketplace domain-verification code for the mock-server publisher (TXT value)."
}

resource "aws_route53_record" "vscode_marketplace_verification" {
  zone_id         = var.zone_id
  name            = "_visual-studio-marketplace-mockserver.${var.domain}"
  type            = "TXT"
  ttl             = 300
  allow_overwrite = true

  records = [var.vscode_marketplace_verification]
}
