terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "key_pair_name" {
  description = "Existing EC2 key pair name (create in AWS console/CLI first)"
  type        = string
}

variable "admin_cidr" {
  description = "CIDR allowed to SSH into the EC2 instance (e.g. your IP /32)"
  type        = string
}

variable "db_password" {
  description = "RDS master password"
  type        = string
  sensitive   = true
}

variable "admin_password" {
  description = "AgentGate admin Basic Auth password (agentgate.admin.password)"
  type        = string
  sensitive   = true
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}
