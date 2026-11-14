plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

// Coverage is reported, never enforced. `./gradlew koverHtmlReportUnitTests koverXmlReportUnitTests` runs
// :core:test and :app:testDebugUnitTest once, instrumented, and merges both into build/reports/kover/.
dependencies {
    kover(project(":core"))
    kover(project(":app"))
}
kover {
    currentProject { createVariant("unitTests") {} }
}
