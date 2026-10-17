import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

// Kotlin pur, sans Android : tout se teste sur la JVM, sans téléphone.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Couverture : 90 % des lignes de :protocol (spécification, stratégie de tests), bloquant dans check.
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Fuzzing des décodeurs (Jazzer, CI nocturne) : cibles dans src/fuzz, sans dépendance à
// Jazzer. writeFuzzClasspath écrit le classpath que la CI passe à l'exécutable Jazzer.
sourceSets {
    create("fuzz") {
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
}

tasks.register("writeFuzzClasspath") {
    val classpath = sourceSets["fuzz"].runtimeClasspath
    val target = layout.buildDirectory.file("fuzz-classpath.txt")
    inputs.files(classpath)
    outputs.file(target)
    doLast { target.get().asFile.writeText(classpath.files.joinToString(":")) }
}
