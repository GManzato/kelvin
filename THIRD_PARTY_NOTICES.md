# Third-Party Notices

Kelvin bundles third-party material listed below. Each item keeps its own
license terms; the licenses reproduce the exact text distributed by the
upstream project.

**Kelvin's own code currently has no project-wide license.** Nothing in this
file licenses Kelvin-original code; it only documents third-party
material and its terms.

## Fonts

### Inter

- Version: 4.001 (name-table build `git-9221beed3`)
- Source files under `android/app/src/main/res/font/` (bundled font binaries):
  `inter_regular.ttf`, `inter_medium.ttf`, `inter_semibold.ttf`, `inter_bold.ttf`
- Copyright 2016 The Inter Project Authors (https://github.com/rsms/inter)
- License: SIL Open Font License, Version 1.1
  - Full text shipped in the APK at `assets/font-licenses/inter-OFL-1.1.txt`
    (byte-identical to `LICENSE.txt` at the upstream `v4.0` and `v4.1` tags;
    SHA-256 `262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a`)
- Upstream: https://rsms.me/inter/ · https://github.com/rsms/inter
- Provenance: each bundled `.ttf` is byte-identical (SHA-256) to
  `extras/ttf/Inter-{Regular,Medium,SemiBold,Bold}.ttf` in the official
  Inter 4.1 release package (https://github.com/rsms/inter/releases/tag/v4.1).
- The Inter license text declares no Reserved Font Name.

### JetBrains Mono

- Version: 2.305
- Source files under `android/app/src/main/res/font/` (bundled font binaries):
  `jetbrainsmono_regular.ttf`, `jetbrainsmono_medium.ttf`, `jetbrainsmono_semibold.ttf`
- Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono)
- License: SIL Open Font License, Version 1.1
  - Full text shipped in the APK at `assets/font-licenses/jetbrains-mono-OFL-1.1.txt`
    (byte-identical to `OFL.txt` on the upstream `master` branch;
    SHA-256 `a76abf002c49097d146e86740a3105a5d00450b1592e820a1109a8c5680cd697`)
- Upstream: https://www.jetbrains.com/mono/ · https://github.com/JetBrains/JetBrainsMono
- Provenance: each bundled `.ttf` is byte-identical (SHA-256) to
  `fonts/ttf/JetBrainsMono-{Regular,Medium,SemiBold}.ttf` on the upstream
  `master` branch (build 2.305; the latest tagged GitHub release is v2.304).
- The JetBrains Mono license text declares no Reserved Font Name. The font
  binaries are distributed under OFL 1.1; this notice makes no license grant
  for Kelvin-original code.

## Gradle Wrapper

- `android/gradle/wrapper/gradle-wrapper.jar` fetches Gradle 8.13
  (`https://services.gradle.org/distributions/gradle-8.13-bin.zip`).
- License: Apache License 2.0, as embedded in the wrapper JAR itself
  (`META-INF/LICENSE`; JAR SHA-256
  `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`).
- An extracted copy of that exact embedded license text is at
  `third_party/gradle-wrapper-LICENSE.txt` (10,174 bytes; SHA-256
  `0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594`).
- Upstream: https://gradle.org

## Build and runtime code dependencies

The app is built with the Kotlin compiler/plugin, Jetpack Compose, and
AndroidX libraries fetched by Gradle at build time (see
`android/build.gradle.kts` and `android/app/build.gradle.kts`). These are
independent third-party works that retain their own licenses (Apache
License 2.0 for the Kotlin, Compose, and AndroidX artifacts). The absence
of a license for Kelvin-original code does not extend to them, and this
project does not redistribute their sources.

## UI icons

The interface stores geometric vector paths directly in
`android/app/src/main/java/be/apexlab/kelvin/ui/Icons.kt`; no separate
icon-library package is bundled.

## Trademarks

Kelvin is an independent project and is not affiliated with, endorsed by,
or sponsored by Teledyne FLIR. "FLIR" and "FLIR ONE" are trademarks of
their respective owners and are used descriptively for hardware
compatibility only. No vendor logos or brand assets are included.
