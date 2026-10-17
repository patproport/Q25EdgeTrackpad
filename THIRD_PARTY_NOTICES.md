# Third-party notices

This file records the third-party components and assets identified for Q25 Edge Trackpad 1.0.0. The app is a modified derivative of [Q25 Trackpad Customizer](https://github.com/shroyertech/Q25TrackpadCustomizer) and its [Q25 Trackpad Enhanced fork](https://github.com/astroboii47/Q25TrackpadCustomizer); their app code is distributed under GPLv3. See [LICENSE](LICENSE). The libraries and settings icon below retain their Apache-2.0 terms; a full copy is at [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). The [GNU GPLv3 guide](https://www.gnu.org/licenses/quick-guide-gplv3.html) describes Apache-2.0 as compatible with GPLv3.

## Release dependency inventory

An earlier release candidate's generated `app/build/outputs/sdk-dependencies/release/sdkDependencies.txt` listed 62 Maven coordinates, including BOMs and metadata-only modules. On 2026-10-17, each coordinate was checked against the corresponding POM in the local Gradle cache. All POMs declare Apache-2.0, except `com.google.guava:listenablefuture:1.0`, which inherits that declaration from `guava-parent:26.0-android`. The resolved AAR/JAR archives inspected locally contained no separate `NOTICE` file. Recheck this inventory against the final APK. The metadata lists dependencies and does not itself prove which module bytes were packed into the APK.

| Publisher / license source | Modules and resolved versions | License |
|---|---|---|
| [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) | `activity:1.8.0`; `annotation:1.6.0`, `annotation-jvm:1.6.0`, `annotation-experimental:1.4.0`; `appcompat:1.7.0`, `appcompat-resources:1.7.0`; `arch.core:core-common:2.2.0`, `core-runtime:2.2.0` | Apache-2.0 |
| AndroidX | `cardview:1.0.0`; `collection:1.1.0`; `concurrent:concurrent-futures:1.1.0`; `coordinatorlayout:1.1.0`; `core:1.13.1`, `core-ktx:1.13.1`; `cursoradapter:1.0.0`; `customview:1.1.0`, `customview-poolingcontainer:1.0.0` | Apache-2.0 |
| [AndroidX ConstraintLayout](https://github.com/androidx/constraintlayout/blob/main/LICENSE) | `constraintlayout:2.1.4`, `constraintlayout-core:1.0.4` | Apache-2.0 |
| AndroidX | `documentfile:1.0.0`; `drawerlayout:1.1.1`; `dynamicanimation:1.0.0`; `emoji2:1.3.0`, `emoji2-views-helper:1.3.0`; `fragment:1.5.4`; `interpolator:1.0.0`; `legacy:legacy-support-core-utils:1.0.0` | Apache-2.0 |
| AndroidX | `lifecycle:lifecycle-common:2.6.2`, `lifecycle-livedata:2.6.2`, `lifecycle-livedata-core:2.6.2`, `lifecycle-process:2.6.2`, `lifecycle-runtime:2.6.2`, `lifecycle-viewmodel:2.6.2`, `lifecycle-viewmodel-savedstate:2.6.2`; `loader:1.0.0`; `localbroadcastmanager:1.0.0` | Apache-2.0 |
| AndroidX | `print:1.0.0`; `profileinstaller:1.3.1`; `recyclerview:1.3.2`; `resourceinspection:resourceinspection-annotation:1.0.1`; `savedstate:1.2.1`; `startup:startup-runtime:1.1.1`; `tracing:1.0.0`; `transition:1.5.0` | Apache-2.0 |
| AndroidX | `vectordrawable:1.1.0`, `vectordrawable-animated:1.1.0`; `versionedparcelable:1.1.1`; `viewpager:1.0.0`; `viewpager2:1.1.0-beta02` | Apache-2.0 |
| [Material Components](https://github.com/material-components/material-components-android/blob/master/LICENSE) | `com.google.android.material:material:1.12.0` | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/README.md) | `org.jetbrains.kotlin:kotlin-stdlib:2.0.21`, `kotlin-stdlib-common:2.0.21`, `kotlin-stdlib-jdk7:1.8.22`, `kotlin-stdlib-jdk8:1.8.22`, `kotlin-bom:1.8.22` | Apache-2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4`, `kotlinx-coroutines-core:1.6.4`, `kotlinx-coroutines-core-jvm:1.6.4`, `kotlinx-coroutines-bom:1.6.4` | Apache-2.0 |
| [JetBrains annotations](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt) | `org.jetbrains:annotations:13.0` | Apache-2.0 |
| [Google Guava](https://github.com/google/guava/blob/master/pom.xml) | `com.google.guava:listenablefuture:1.0` | Apache-2.0 (inherited POM) |
| [Google Error Prone](https://github.com/google/error-prone/blob/master/COPYING) | `com.google.errorprone:error_prone_annotations:2.15.0` | Apache-2.0 |

`hidden-api-stubs` is a local compile-only module and not a separate runtime library. The Gradle wrapper and compiler are build tools, not APK libraries.

## Settings vector

The settings gear path is derived from the Google Material Design Icons Action / Settings 24px icon. The vector path data matches the upstream icon; the app applies its own tint and Android VectorDrawable wrapper.

- Source: [google/material-design-icons, Action / Settings 24px](https://github.com/google/material-design-icons/blob/master/src/action/settings/materialicons/24px.svg)
- License: [Apache License 2.0](https://github.com/google/material-design-icons/blob/master/LICENSE)

## Former launcher artwork — removed

The upstream launcher artwork was attributed to “WhoCon from Flaticon,” but its exact acquisition record and redistribution terms could not be established. Q25 Edge Trackpad no longer distributes that artwork: the 15 inherited launcher WebP files and the inherited Play Store PNG were removed from the current source tree.

The replacement launcher artwork is an original composition made for this derivative from simple toggle and gear geometry. It does not use the separate Google Material settings path described above. Its SVG source and Android vector resources are distributed with this project under GPL-3.0-or-later, so no Flaticon attribution or license is required for the current launcher icon.
