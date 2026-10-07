resource "aws_security_group" "monitoring" {
  name_prefix = "agentgate-monitoring-"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  ingress {
    description = "Grafana (admin only)"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "monitoring" {
  ami                         = data.aws_ami.al2023_arm64.id
  instance_type               = "t4g.micro"
  key_name                    = var.key_pair_name
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.monitoring.id]
  associate_public_ip_address = true

  user_data = <<-EOF
    #!/bin/bash
    dnf install -y docker
    systemctl enable --now docker

    mkdir -p /opt/monitoring
    cat > /opt/monitoring/prometheus.yml <<'PROMCFG'
    scrape_configs:
      - job_name: agentgate
        metrics_path: /actuator/prometheus
        static_configs:
          - targets: ['${aws_instance.app.private_ip}:8080']
    PROMCFG

    docker run -d --name prometheus --restart unless-stopped \
      -p 9090:9090 -v /opt/monitoring/prometheus.yml:/etc/prometheus/prometheus.yml \
      prom/prometheus:latest

    docker run -d --name grafana --restart unless-stopped -p 3000:3000 grafana/grafana:latest
  EOF

  tags = {
    Name = "agentgate-monitoring"
  }
}
