# Nocturne toolchain cache preparation — 2026-10-22

Scope: environment/cache preparation only. No Git checkout edited; no Task 5/6/7 implementation, product review or integration test pass.

## Provenance and pins

Read `nocturne-hardening/docs/review/platform-preflight.md` and current version catalog/settings/app build files. Official AGP compatibility page https://developer.android.com/build/releases/agp-8-10-0-release-notes confirms AGP 8.10 supports API36 and requires/defaults Android Build Tools35.0.0, JDK17 and Gradle>=8.11.1.

Prepared against AGP8.10.1, Health Connect1.1.0, compileSdk36, target35, min31, existing Gradle8.14.3, Kotlin2.0.21, Compose BOM2024.12.01. Existing app dependency versions were included in the disposable resolver project.

Paths:
- Java: `/workspace/scratch/ad4eb857ceea/nocturne-jdk17/jdk-17.0.20.1+1`
- SDK: `/workspace/scratch/ad4eb857ceea/android-sdk`
- Gradle user home: `/workspace/scratch/ad4eb857ceea/gradle-cache`
- Cached Gradle binary: `/workspace/scratch/ad4eb857ceea/gradle-cache/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle`
- Disposable project: `/workspace/scratch/ad4eb857ceea/nocturne-sdd-scratch/toolchain-warmup`

## SDK result

Installed official `build-tools;35.0.0` using existing sdkmanager, exit0. Verified `build-tools/35.0.0/source.properties`: `Pkg.Revision=35.0.0`. Existing SDK36 remains in place.

Command (JAVA_HOME as above):
```sh
JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts bash /workspace/scratch/ad4eb857ceea/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/workspace/scratch/ad4eb857ceea/android-sdk --proxy=http --proxy_host=browser-proxy --proxy_port=8889 'build-tools;35.0.0'
```

Initial direct/proxy-only Java downloads failed with `SSLHandshakeException: PKIX path building failed`. Existing system Java trust store resolved SDK download. Settings are process-local; no trust bypass, global configuration or shared-cache metadata edit. SDK download did not hold Gradle heavy lock. Log: `sdk-build-tools35-system-trust.log`; diagnostic evidence: `sdk-build-tools35-diagnostic.log`.

## Gradle preparation

Disposable project uses official Google Maven and the existing Google mirror of Maven Central, then Maven Central/plugin portal fallbacks. `warmPinnedArtifacts` resolves debugCompileClasspath/debugRuntimeClasspath artifact files (not just dependency metadata). Plugins pinned above are applied solely in the scratch project.

Command (JAVA_HOME and GRADLE_USER_HOME as above, working directory disposable project):
```sh
flock /tmp/nocturne-gradle-heavy.lock /workspace/scratch/ad4eb857ceea/gradle-cache/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g -Dhttps.proxyHost=browser-proxy -Dhttps.proxyPort=8889 -Dhttp.proxyHost=browser-proxy -Dhttp.proxyPort=8889 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts' warmPinnedArtifacts
```

Online resolution succeeded, exit0, 23s: 50 debug compile artifact files and 86 debug runtime artifact files. AGP8.10.1 JAR/POM/module and Health Connect1.1.0 AAR/POM/module are present in Gradle-managed cache. Log: `toolchain-warmup-system-trust.log`.

Offline confirmation succeeded, exit0, 9s, same 50/86 artifact counts. Repeated above command with `--offline`, and JVM argument only `-Xmx2g` (no proxy/trust override needed offline). Log: `toolchain-warmup-offline.log`.

Gradle reported deprecated features incompatible with Gradle9; no version was changed and Gradle8.14.3 succeeded. Only cache resolver tasks ran; no product build or tests ran.

## Remaining needs

Task-specific tool configurations (for example new AGP AAPT2/lint artifacts) were not independently resolved; a later actual build may still download them using the documented secure per-process network settings.

Task6 still requires its authorised coordinated build/dependency changes and actual integration gates. Cache preparation does not certify product compilation, tests, lint, manifests, runtime permissions, Health Connect availability or device behaviour.
