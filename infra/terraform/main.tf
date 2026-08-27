locals {
  resource_prefix = "${var.service_name}-${var.environment}"
  required_modules = toset([
    "edge",
    "network",
    "database",
    "cache",
    "queue",
    "object-storage",
    "compute",
    "observability"
  ])
}
