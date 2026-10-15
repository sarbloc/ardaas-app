plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin) and pins KGP 2.2.x at
    // runtime. Declaring the Kotlin plugin here, unapplied, raises KGP on the
    // build classpath to the catalog version so it matches the Compose
    // compiler plugin — which must be the same Kotlin version exactly.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
