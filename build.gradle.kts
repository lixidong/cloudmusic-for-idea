import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    intellijPlatform {
        intellijIdea("2026.1.2")
        testFramework(TestFrameworkType.Platform)
    }
}
