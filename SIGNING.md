# Release signing (Android)

Release APKs are built **unsigned** (`gradlew assembleRelease`) and signed afterwards with `sign_release.py`.
Nothing secret is in this repository, and no signing config exists in the Gradle files, so a release build can never silently use the debug key.

* Private key + passwords: `~/.houseli-keystore/island/` on the maintainer's machine only. Never commit, upload, zip or paste them.
* `signing/island.lineage` is public: it proves the new key was authorised by the previous (debug) key so installed copies can update in place.
  Keep it; every future release needs it. (Android 9+ only: the app's minSdk is 30.)
* Build and sign:  `gradlew assembleRelease`, then  `python sign_release.py island app/build/outputs/apk/release/app-release-unsigned.apk Island-release.apk --min-sdk 30`
* Verify:  `apksigner verify --verbose --print-certs Island-release.apk`   - expect "v3 scheme: true" and certificate SHA-256 `b6386a21bb892b8795b2f15d16e8b2280479fdd65787dc9e8877e6ef4a114fee`.
