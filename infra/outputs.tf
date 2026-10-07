output "alb_dns_name" {
  value = aws_lb.app.dns_name
}

output "ec2_instance_id" {
  value = aws_instance.app.id
}

output "ec2_public_ip" {
  value = aws_instance.app.public_ip
}

output "rds_endpoint" {
  value = aws_db_instance.postgres.endpoint
}

output "rds_instance_id" {
  value = aws_db_instance.postgres.identifier
}

output "monitoring_instance_id" {
  value = aws_instance.monitoring.id
}

output "monitoring_public_ip" {
  value = aws_instance.monitoring.public_ip
}

output "app_private_ip" {
  value = aws_instance.app.private_ip
}

output "runtime_instance_id" {
  value = aws_instance.runtime.id
}

output "runtime_public_ip" {
  value = aws_instance.runtime.public_ip
}

output "runtime_private_ip" {
  value = aws_instance.runtime.private_ip
}
