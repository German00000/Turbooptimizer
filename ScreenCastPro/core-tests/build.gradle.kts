plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java/com/screencastpro/core")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
