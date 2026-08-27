#!/bin/sh

set -eu

required_paths="
apps/ios/BitOS.xcodeproj/project.pbxproj
apps/android/build.gradle.kts
shared/business-core/build.gradle.kts
native/media-core/CMakeLists.txt
contracts/project-schema/project-v1.schema.json
services/platform/src/api.ts
infra/compose.yaml
docs/engineering/architecture.md
docs/product/ux-ui-flows.md
docs/product/studio-mass-production.md
.github/workflows/ci.yml
.github/pull_request_template.md
docs/adr/0000-template.md
"

for path in $required_paths; do
    if [ ! -e "$path" ]; then
        echo "Missing required path: $path" >&2
        exit 1
    fi
done

echo "Repository structure is complete"
