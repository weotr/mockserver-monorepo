terraform {
  required_version = ">= 1.15"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region  = "eu-west-2"
  profile = "mockserver-build"
}

locals {
  project     = "mockserver"
  bucket_name = "${local.project}-terraform-state"
  table_name  = "${local.project}-terraform-locks"
  profile     = "mockserver-build"
  region      = "eu-west-2"
}

data "aws_caller_identity" "current" {}

data "external" "s3_bucket_exists" {
  program = [
    "bash", "-c",
    "${path.module}/scripts/check_s3_bucket_exists.sh ${local.bucket_name} ${local.profile}"
  ]
}

data "external" "dynamodb_table_exists" {
  program = [
    "bash", "-c",
    "${path.module}/scripts/check_dynamodb_table_exists.sh ${local.table_name} ${local.profile} ${local.region}"
  ]
}

# --- KMS CMK for Terraform state bucket encryption ----------------------------
# Mirrors the CloudTrail CMK pattern: rotation enabled, 30-day deletion
# window, key policy allows the account root + the S3 service.
#
# APPLY-WITH-CARE: this key must exist before the main stack's backend can
# reference it via kms_key_id. Apply the bootstrap stack first, then update
# the main stack's backend.tf. Existing state objects re-encrypt on next
# PutObject (S3 default encryption applies to new writes, not retroactively).
resource "aws_kms_key" "terraform_state" {
  description             = "CMK used to encrypt the Terraform state bucket (mockserver-terraform-state)"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnableRootPermissions"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowS3ServiceEncrypt"
        Effect    = "Allow"
        Principal = { Service = "s3.amazonaws.com" }
        Action    = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource  = "*"
      }
    ]
  })
}

resource "aws_kms_alias" "terraform_state" {
  name          = "alias/mockserver-terraform-state"
  target_key_id = aws_kms_key.terraform_state.key_id
}

resource "aws_s3_bucket" "terraform_state" {
  bucket = local.bucket_name
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.terraform_state.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket                  = aws_s3_bucket.terraform_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Audit finding F-BLD-10 — deny any non-TLS access to the state bucket.
resource "aws_s3_bucket_policy" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.terraform_state.arn,
        "${aws_s3_bucket.terraform_state.arn}/*",
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}

resource "aws_dynamodb_table" "terraform_locks" {
  name         = local.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }
}

import {
  for_each = data.external.s3_bucket_exists.result.name != "unknown" ? { bucket = local.bucket_name } : {}
  id       = each.value
  to       = aws_s3_bucket.terraform_state
}

import {
  for_each = data.external.s3_bucket_exists.result.name != "unknown" ? { bucket = local.bucket_name } : {}
  id       = each.value
  to       = aws_s3_bucket_versioning.terraform_state
}

import {
  for_each = data.external.s3_bucket_exists.result.name != "unknown" ? { bucket = local.bucket_name } : {}
  id       = each.value
  to       = aws_s3_bucket_server_side_encryption_configuration.terraform_state
}

import {
  for_each = data.external.s3_bucket_exists.result.name != "unknown" ? { bucket = local.bucket_name } : {}
  id       = each.value
  to       = aws_s3_bucket_public_access_block.terraform_state
}

import {
  for_each = data.external.dynamodb_table_exists.result.name != "unknown" ? { table = local.table_name } : {}
  id       = each.value
  to       = aws_dynamodb_table.terraform_locks
}

output "state_bucket" {
  value = aws_s3_bucket.terraform_state.bucket
}

output "lock_table" {
  value = aws_dynamodb_table.terraform_locks.name
}

output "state_kms_key_arn" {
  description = "ARN of the KMS CMK encrypting the Terraform state bucket"
  value       = aws_kms_key.terraform_state.arn
}

output "state_kms_key_alias" {
  description = "Alias of the KMS CMK encrypting the Terraform state bucket"
  value       = aws_kms_alias.terraform_state.name
}
