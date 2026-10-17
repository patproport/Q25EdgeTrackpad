# Release preparation (Windows PowerShell)

The first public release is planned as `1.0.0` (`versionCode` 1), package `io.github.patproport.q25edgetrackpad`. A signed release candidate was installed and functionally checked on Q25 on 2026-10-17. That candidate predates the final public source history. Build a new APK from the final public commit, then record its SHA-256, certificate fingerprint, source commit and Q25 test result in the Release notes. Settings migration from the earlier debug package was intentionally skipped and remains untested. Publish only the reviewed public history; the local original `main` must remain unpublished.

## Signing key storage (key already created)

The release key exists outside this repository. Make a separate encrypted backup and confirm the backup can be read before publication. Keep the key and its password in different places. Never send either password or the keystore through chat, Git, or Discord. The following generation steps are retained only for a new key; do not run them for this release or replace the existing key.

1. Choose an encrypted folder **outside this repository** and prepare a separate encrypted backup location. Keep the key and its password in different places.
2. In PowerShell, set `$keystorePath` to the chosen full path outside this repository. Confirm the parent folder exists. Find the JDK `keytool.exe` from the Java 17+ installation used for Android builds.
3. Run the command below. It asks for passwords interactively; do not put passwords on the command line. If PKCS12 uses one password for both the keystore and key, enter that same password at both prompts in the release build script.

```powershell
$keystorePath = Read-Host 'Full keystore path outside the Q25EdgeTrackpad repository'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'Set JAVA_HOME to JDK 17+ first.' }
if (Test-Path -LiteralPath $keystorePath) { throw 'Choose a new keystore path; do not overwrite an existing key.' }
& $keytool -genkeypair -v -keystore $keystorePath -storetype PKCS12 -alias q25-release -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
```

Make an encrypted offline backup of the keystore and confirm it can be read. Save the password in a password manager. Record the **certificate SHA-256 fingerprint**, which is public identification information, without copying the keystore or password into the repository:

```powershell
& $keytool -list -v -keystore $keystorePath -alias q25-release
```

## Build and check the APK

From this project directory, run:

```powershell
.\build-release.ps1 -Clean
```

The script asks for the keystore path, alias and passwords, builds `:app:assembleRelease`, checks that the former launcher bitmap files are absent, verifies the APK signature and package/version, then prints the certificate fingerprint and APK SHA-256. It does not install or upload the APK. Record the fingerprint, hash, build date, and source commit before publishing. Compare the fingerprint against the separately recorded release certificate on every later release.

Before publication, save the resolved release dependency tree and review each runtime artifact's license and notice against its upstream source. The Gradle tree lists dependencies but does not decide their licenses:

```powershell
$report = Join-Path $env:TEMP 'q25-release-runtime-dependencies.txt'
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath > $report
```

Keep the temporary report outside Git; use it to complete `THIRD_PARTY_NOTICES.md` before making the Release.

If the script fails, do not distribute an older file left under `app/build/outputs/apk/release/`. Resolve the failure, rerun the build, and use only the APK whose hash the successful run printed.

## Q25 migration check before publication

1. In the old debug app (`tech.shroyer.q25trackpadcustomizer.edgescroll`), export a settings JSON file to a location that survives app removal. Keep a second copy until migration is confirmed.
2. Disable the old app's Accessibility service so its root monitor stops. Install the new signed release APK. Because the package ID changed, it can coexist with the old debug app. Grant root access and enable only the new app's Accessibility service.
3. Restore the JSON into the new app. Confirm the saved settings and app specific modes, then check Mouse (Gesture), Mouse (Smooth Wheel), Keyboard, Quick Toggle, and the new launcher icon on Q25.
4. Once satisfied, remove the old debug app. Confirm the release remains functional. Do not remove the only copy of the backup.

Record the Q25 results and APK metadata in `TASKS.md`. The release APK and its source commit must match the GitHub tag. Release notes should link [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the corresponding source. Only publish the clean public history branch; the local original `main` must remain unpublished. The untested settings migration must be identified in the user instructions.
