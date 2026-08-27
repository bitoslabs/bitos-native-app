variable "environment" {
  description = "Deployment environment."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "Environment must be staging or production."
  }
}

variable "region" {
  description = "Primary cloud region selected by the deployment ADR."
  type        = string
}

variable "service_name" {
  description = "Stable resource-name prefix."
  type        = string
  default     = "bitos"
}
