plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("androidx.room") version "2.7.2" apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
