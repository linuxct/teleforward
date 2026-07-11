// AGP 9's built-in Kotlin ships KGP 2.2.10. Pin a newer KGP on the buildscript classpath so the
// built-in Kotlin compiler is 2.3.21 (able to read the latest libraries' Kotlin 2.4.0 metadata).
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
