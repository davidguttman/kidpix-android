# Fonts for the headless harness

`NotoColorEmoji.ttf`: `/system/fonts/NotoColorEmoji.ttf` of the Android 15 x86_64 google_apis
system image (`noto-emoji:20231129`, COLRv1), the image the reference pictures were captured on.
Robolectric bundles an older bitmap emoji font with different glyph designs (the paw print points
another way). Noto Emoji is under the SIL Open Font License 1.1 (https://github.com/googlefonts/noto-emoji).
`HarnessFonts.install()` points `EmojiFallback` at it.
