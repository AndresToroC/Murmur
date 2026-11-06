// AGP 9 has built-in Kotlin (org.jetbrains.kotlin.android is not applied).
// The Compose plugin pins the Kotlin (KGP) version on the classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
