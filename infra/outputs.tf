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
