# Terraform root

This root validates environment naming and records the required module boundaries. It does not create cloud resources yet because the cloud/provider ADR, accounts, state backend, regions, RPO/RTO and budget have not been approved.

When the ADR is approved, create one module per item in `required_modules`, use a remote encrypted state backend with locking, and keep staging/production state and credentials separate.
