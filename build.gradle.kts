// Top-level build file. Versions match what this machine already has in the Gradle cache
// (AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.10.2), so the first build needs no downloads.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
