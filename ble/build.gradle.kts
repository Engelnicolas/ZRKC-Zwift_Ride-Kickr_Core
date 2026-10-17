plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.kickzwift.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Machines de liaison (link/) testées sur la JVM, avec de faux appareils.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("config/lint.xml")
    }
}

dependencies {
    api(project(":protocol"))
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
