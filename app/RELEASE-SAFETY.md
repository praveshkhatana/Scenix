# Android artifact safety

Production is `com.scenix.tv`, version `0.2-p5` / versionCode `5`. It deliberately succeeds the
already deployed `0.2-p4` / versionCode `4` and must keep increasing on every production release.

Debug and QA are separate applications:

| Variant | Application id | Version label |
| --- | --- | --- |
| `debug` | `com.scenix.tv.debug` | `0.2-p5-debug` |
| `qa` | `com.scenix.tv.qa` | `0.2-p5-qa` |
| `release` | `com.scenix.tv` | `0.2-p5` |

Build the side-by-side QA artifact with:

```sh
cd app
./gradlew assembleQa
cd ..
bash scripts/verify-android-artifact.sh app/build/outputs/apk/qa/app-qa.apk qa
```

The verification command only reads the APK. It rejects any QA file whose package id could replace
the production app, and it never contacts a TV or attempts an install.

Production needs these Gradle properties supplied outside source control:

```properties
scenix.release.storeFile=/absolute/path/to/release.keystore
scenix.release.storePassword=...
scenix.release.keyAlias=...
scenix.release.keyPassword=...
```

Without all four properties and a readable keystore, every production artifact task fails before it
can emit an unsigned or debug-key APK. Before treating a signed release as an update candidate,
compare it against the version code presently installed:

```sh
bash scripts/verify-android-artifact.sh app/build/outputs/apk/release/app-release.apk production 4
```

Replace `4` with the actual installed production version code. The verifier refuses a matching or
lower version code, so it cannot endorse downgrade-style installation.
