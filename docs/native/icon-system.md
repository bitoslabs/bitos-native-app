# Native icon system

## Decision

BitOS uses native platform system icons across both apps:

- iOS resolves `AppIcons` through SwiftUI SF Symbols (`Image(systemName:)`).
- Android resolves `AppIcons` through Compose Material Rounded Icons (`Icons.Rounded.*` / `Icons.AutoMirrored.Rounded.*`).
- `shared/business-core` contains no icon asset, renderer, color, size, or
  asset filename.

The two native `AppIcons` files are the semantic source of truth. Feature code
must use those tokens rather than introduce direct SF Symbol strings or Material
icon references.

## Selection rules

- Use outline/unfilled variants for inactive/secondary controls and filled
  variants for selected or completed states where distinct.
- Keep the same semantic token across both platforms; visual geometry follows
  native OS conventions (e.g., `square.and.arrow.up` on iOS; `Icons.Rounded.Share`
  on Android).
- Every interactive icon has a text accessibility label and meets the native
  target size: 44 pt on iOS and 48 dp on Android.
- Never download or render an SVG received from a relay, note, profile, or
  network response.
