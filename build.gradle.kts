plugins {
    kotlin("jvm") version "2.0.21" apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
