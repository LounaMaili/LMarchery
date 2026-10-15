plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(21)
    jvm()
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Both targets are JVM platforms. Share sources, without moving java.time domain code to commonMain.
    sourceSets {
        listOf(jvmMain, androidMain).forEach { source ->
            source.configure {
                kotlin.srcDir("src/sharedMain/kotlin")
                dependencies {
                    api(project(":core:model"))
                    api(project(":core:scoring"))
                    implementation("androidx.room:room-runtime:2.7.2")
                    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
                }
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

android {
    namespace = "com.lmarchery.core.database"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("kspJvm", "androidx.room:room-compiler:2.7.2")
    add("kspAndroid", "androidx.room:room-compiler:2.7.2")
}

room { schemaDirectory("$projectDir/schemas") }

// Keep the existing quality-gate entry point: `./gradlew test` must execute actual SQLite JVM tests.
tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed", "skipped", "passed")
        showStandardStreams = true
    }
}
