terraform {
  backend "s3" {
    bucket       = "mockserver-terraform-state"
    key          = "buildkite-agents/terraform.tfstate"
    region       = "eu-west-2"
    use_lockfile = true
    encrypt      = true
    profile      = "mockserver-build"

    # APPLY-WITH-CARE: the bootstrap stack must be applied first to create
    # the KMS CMK (alias/mockserver-terraform-state). After the key exists,
    # run `terraform init -reconfigure` on this stack to pick up the new
    # backend config. Existing state objects re-encrypt on next write.
    kms_key_id = "alias/mockserver-terraform-state"
  }
}
