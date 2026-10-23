import java.security.KeyStore
import java.security.PrivateKey

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keep release-only hardening in the build package while Claude owns the main manifest.
// Android still delivers system broadcasts and widget updates to non-exported receivers.
abstract class GenerateReleaseManifest : DefaultTask() {
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val receivers = listOf(
            ".platform.PlatformClockReceiver",
            ".night.BootReceiver",
            ".night.AlarmChangedReceiver",
            ".widget.QuickControlsReceiver",
            ".widget.SingleActionReceiver",
        ).joinToString("\n") {
            "        <receiver android:name=\"$it\" android:exported=\"false\" tools:replace=\"android:exported\" />"
        }
        manifestFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                |<?xml version="1.0" encoding="utf-8"?>
                |<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                |    xmlns:tools="http://schemas.android.com/tools">
                |    <application>
                |$receivers
                |    </application>
                |</manifest>
                |
            """.trimMargin())
        }
    }
}

android {
    namespace = "io.github.willzy12h.nocturne"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.willzy12h.nocturne"
        minSdk = 31
        targetSdk = 35
        // Release builds get these from the tag (see .github/workflows/build.yml).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    // A missing key produces an unsigned release for local inspection. Partial or invalid
    // configuration fails rather than silently changing the signing identity.
    val signingKeys = listOf("NOCTURNE_KEYSTORE", "NOCTURNE_KEYSTORE_PASSWORD", "NOCTURNE_KEY_ALIAS", "NOCTURNE_KEY_PASSWORD")
    val signingValues = signingKeys.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
    val signingRequested = signingValues.values.any { it != null }
    if (signingRequested && signingValues.values.any { it == null }) {
        throw GradleException("Nocturne release signing: requires all NOCTURNE_KEYSTORE, NOCTURNE_KEYSTORE_PASSWORD, NOCTURNE_KEY_ALIAS and NOCTURNE_KEY_PASSWORD values")
    }
    val releaseKeystore = signingValues.getValue("NOCTURNE_KEYSTORE")?.let(::file)
    if (releaseKeystore != null && !releaseKeystore.isFile) {
        throw GradleException("Nocturne release signing: configured keystore is not a file")
    }
    // Validate every configured credential before any build, including lightweight CI probes.
    // Never print passwords or fall back to another key when a supplied identity is invalid.
    val releaseStoreType = releaseKeystore?.let { keystoreFile ->
        try {
            val store = KeyStore.getInstance(keystoreFile, signingValues.getValue("NOCTURNE_KEYSTORE_PASSWORD")!!.toCharArray())
            val alias = signingValues.getValue("NOCTURNE_KEY_ALIAS")!!
            val key = store.getKey(alias, signingValues.getValue("NOCTURNE_KEY_PASSWORD")!!.toCharArray())
            check(key is PrivateKey && !store.getCertificateChain(alias).isNullOrEmpty())
            store.type
        } catch (_: Exception) {
            throw GradleException("Nocturne release signing: could not load the private key; check the keystore, store password, alias and key password")
        }
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = releaseStoreType
                storePassword = signingValues.getValue("NOCTURNE_KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("NOCTURNE_KEY_ALIAS")
                keyPassword = signingValues.getValue("NOCTURNE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            if (releaseKeystore == null) println("Nocturne release signing is not configured; assembleRelease will produce an unsigned APK")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads its Android image; Maven Central rate-limits busy machines, so use Google's mirror.
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            it.systemProperty("nocturne.serializationFixtures", rootProject.file(".github/workflows/release-serialization-fixtures.json").absolutePath)
        }
    }
    // This harness is test-only and cannot become an R8 root in the application APK.
    sourceSets.getByName("testRelease").java.srcDir(rootProject.file(".github/workflows/release-serialization"))
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val overlay = tasks.register<GenerateReleaseManifest>("generateReleaseSecurityManifest")
        variant.sources.manifests.addGeneratedManifestFile(overlay, GenerateReleaseManifest::manifestFile)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.health.connect)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
