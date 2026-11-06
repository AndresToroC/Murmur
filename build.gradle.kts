// AGP 9 trae Kotlin integrado (no se aplica org.jetbrains.kotlin.android).
// El plugin de Compose fija la versión de Kotlin (KGP) en el classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
