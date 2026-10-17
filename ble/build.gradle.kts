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

    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("config/lint.xml")
    }
}

dependencies {
    api(project(":protocol"))
}
