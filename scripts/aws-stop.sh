#!/bin/bash
# Stops the EC2 instance and RDS database. ALB keeps billing while it exists —
# destroy it separately with `terraform destroy -target=aws_lb.app` if you want
# to avoid that cost too, and re-apply when needed.
set -euo pipefail

cd "$(dirname "$0")/../infra"
EC2_ID=$(terraform output -raw ec2_instance_id)
RDS_ID=$(terraform output -raw rds_instance_id)

echo "Stopping EC2 ($EC2_ID)..."
aws ec2 stop-instances --instance-ids "$EC2_ID"

echo "Stopping RDS ($RDS_ID)..."
aws rds stop-db-instance --db-instance-identifier "$RDS_ID" || true

echo "Done. Note: RDS auto-restarts after 7 days if left stopped (AWS limitation)."
