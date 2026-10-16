plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:scoring"))
    // Streaming API only: no reflective serialization of Kotlin/domain classes.
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
