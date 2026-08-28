# Native icon system

## Decision

BitOS uses Solar as the product icon language. Icon rendering remains native:

- iOS resolves `AppIcons` through SwiftUI/SF Symbols or a reviewed bundled
  Solar asset.
- Android resolves `AppIcons` through Compose/Material equivalents or a
  reviewed bundled Solar vector drawable.
- `shared/business-core` contains no icon asset, renderer, color, size, or
  asset filename.

The two native `AppIcons` files are the semantic source of truth. Feature code
must use those tokens rather than introduce a direct SF Symbol, Material Icon,
or Solar asset dependency.

## Solar selection rules

- Default to **Linear** for inactive/secondary controls and **Bold** for
  selected or completed states.
- Keep the same semantic token across both platforms; visual geometry may vary
  when an OS convention improves recognition.
- Use the platform share icon and native share sheet (`square.and.arrow.up` on
  iOS; Android Sharesheet on Android). It is intentionally not a custom Solar
  replacement.
- Every interactive icon has a text accessibility label and meets the native
  target size: 44 pt on iOS and 48 dp on Android.
- Only checked-in, reviewed assets may be bundled. Never download or render an
  SVG received from a relay, note, profile, or network response.

## Adding a Solar icon

1. Select the glyph and style from the [Solar collection][solar].
2. Add/update the semantic token in both native `AppIcons` files.
3. Vendor the reviewed SVG/vector drawable in the platform asset catalog when
   a native equivalent is insufficient; do not put it in BusinessCore.
4. Update `THIRD_PARTY_NOTICES.md` if the source or license changes.
5. Add a UI/accessibility test when the token changes a release journey.

[solar]: https://icones.js.org/collection/solar
