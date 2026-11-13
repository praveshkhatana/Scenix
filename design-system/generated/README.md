# Generated native token inputs

These files are derived from `design-system/tokens/`, the checked canonical token catalog.

- `ios/ScenixDesignTokens.swift` is a Swift-native catalog for a future SwiftUI/tvOS/macOS target.
- `windows/ScenixTokens.xaml` is a WinUI-compatible resource dictionary for a future Windows target.

Do not edit either file directly. Regenerate them with:

```sh
node design-system/tools/platform-tokens.mjs write
node design-system/tools/platform-tokens.mjs check
```

They deliberately export presentation tokens only. Product behaviour, native components, focus,
navigation, playback and target-specific accessibility remain governed by component contracts and
must be implemented and snapshot-tested on each target before that target can be called release-ready.
