# Agent runtime (LangGraph) + local LLM (Ollama) on their own instance: the LLM needs far more memory
# than the app EC2 has. Both run as containers from deploy/runtime/compose.yaml (scripts/deploy-runtime.sh).

variable "runtime_instance_type" {
  description = "Runtime EC2 type. Ollama runs on CPU here: t4g.large (8 GiB) fits up to ~3B models comfortably; use t4g.xlarge for 7B"
  type        = string
  default     = "t4g.large"
}

resource "aws_security_group" "runtime" {
  name_prefix = "agentgate-runtime-"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  ingress {
    description     = "Runtime API from AgentGate (resume, workflow validation, executions)"
    from_port       = 8000
    to_port         = 8000
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# The runtime calls AgentGate (action evaluation, execution events) and keeps checkpoints in RDS.
resource "aws_security_group_rule" "app_from_runtime" {
  type                     = "ingress"
  description              = "AgentGate API from runtime"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.app.id
  source_security_group_id = aws_security_group.runtime.id
}

resource "aws_security_group_rule" "rds_from_runtime" {
  type                     = "ingress"
  description              = "Postgres (LangGraph checkpoints) from runtime"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = aws_security_group.rds.id
  source_security_group_id = aws_security_group.runtime.id
}

resource "aws_instance" "runtime" {
  ami                         = data.aws_ami.al2023_arm64.id
  instance_type               = var.runtime_instance_type
  key_name                    = var.key_pair_name
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.runtime.id]
  associate_public_ip_address = true

  # Room for model weights (a 7B model is ~5 GB) and container images.
  root_block_device {
    volume_size = 40
    volume_type = "gp3"
  }

  user_data = <<-EOF
    #!/bin/bash
    dnf install -y docker rsync
    systemctl enable --now docker
    usermod -aG docker ec2-user
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -fsSL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64 \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
  EOF

  tags = {
    Name = "agentgate-runtime"
  }
}
