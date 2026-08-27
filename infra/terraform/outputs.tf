output "resource_prefix" {
  value       = local.resource_prefix
  description = "Prefix all provider resources with this value."
}

output "required_modules" {
  value       = local.required_modules
  description = "Infrastructure modules required before production launch."
}
