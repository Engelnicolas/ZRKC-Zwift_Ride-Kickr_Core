plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.kickzwift.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kickzwift.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 5
        versionName = "1.4"
        // Date du build, affichée dans les réglages. Lue par une commande externe : le cache de
        // configuration la revérifie à chaque build, au lieu de figer la date du premier.
        val buildDate =
            providers
                .exec { commandLine("date", "+%Y-%m-%d") }
                .standardOutput.asText
                .get()
                .trim()
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    // Signature de l'APK release : la clé reste hors du dépôt. Sans ces variables, l'APK
    // release est produit non signé (CI, vérification du manifeste).
    val storeFile = providers.environmentVariable("KICKZWIFT_STORE_FILE").orNull
    val release =
        storeFile?.let { path ->
            signingConfigs.create("release") {
                this.storeFile = file(path)
                storePassword = providers.environmentVariable("KICKZWIFT_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KICKZWIFT_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("KICKZWIFT_KEY_PASSWORD").get()
            }
        }

    buildTypes {
        release {
            signingConfig = release
            // R8 activé ; la clé de signature reste hors du dépôt (APK release non signé ici).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Logique de session et lecteur GPX testés sur la JVM.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = rootProject.file("config/lint.xml")
    }
}

dependencies {
    implementation(project(":ble"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // Implémentation de XmlPullParser hors d'Android, pour les tests du lecteur GPX.
    testImplementation(libs.kxml2)
    testRuntimeOnly(libs.junit.platform.launcher)
}
