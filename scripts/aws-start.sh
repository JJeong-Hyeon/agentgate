#!/bin/bash
# Starts the EC2 instance and RDS database for the AWS deployment.
# ALB is not covered here — it has no stop/start concept, only destroy/apply via terraform.
set -euo pipefail

cd "$(dirname "$0")/../infra"
EC2_ID=$(terraform output -raw ec2_instance_id)
RDS_ID=$(terraform output -raw rds_instance_id)
MONITORING_ID=$(terraform output -raw monitoring_instance_id)
RUNTIME_ID=$(terraform output -raw runtime_instance_id)

echo "Starting RDS ($RDS_ID)..."
aws rds start-db-instance --db-instance-identifier "$RDS_ID" || true

echo "Starting EC2 ($EC2_ID, $MONITORING_ID, $RUNTIME_ID)..."
aws ec2 start-instances --instance-ids "$EC2_ID" "$MONITORING_ID" "$RUNTIME_ID"

echo "Waiting for EC2 to be running..."
aws ec2 wait instance-running --instance-ids "$EC2_ID" "$MONITORING_ID" "$RUNTIME_ID"

echo "Public IP:       $(terraform output -raw ec2_public_ip)"
echo "ALB DNS:         $(terraform output -raw alb_dns_name)"
echo "Monitoring IP:   $(terraform output -raw monitoring_public_ip)"
echo "Runtime IP:      $(terraform output -raw runtime_public_ip)"
