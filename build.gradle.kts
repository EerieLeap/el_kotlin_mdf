import com.diffplug.spotless.LineEnding
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "com.eerie_leap"
version = "0.1.0-SNAPSHOT"

kotlin {
    // A library: what it makes public says so, with its types.
    explicitApi()
    jvmToolchain(21)

    // The Gefera app's targets: the JVM, whose variant Android takes too, and iOS.
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Code style, as the Gefera app has it: Spotless formats the sources and build scripts with ktlint, as .editorconfig
// sets it, and detekt lints the sources, as config/detekt/detekt.yml sets it. Every Kotlin compilation first formats
// the sources (on CI it only checks them) and lints them without type resolution; `check` adds detekt's analyses with
// type resolution, which `detektAll` runs alone.
val onCi = providers.environmentVariable("CI").isPresent
val format = if (onCi) "spotlessCheck" else "spotlessApply"

spotless {
    // What .gitattributes checks out.
    lineEndings = LineEnding.UNIX
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    config.setFrom(file("config/detekt/detekt.yml"))
    buildUponDefaultConfig.set(true)
    parallel.set(true)
    basePath.set(layout.projectDirectory)
}

tasks.withType<Detekt>().configureEach {
    exclude { it.file.invariantSeparatorsPath.contains("/build/") }
    mustRunAfter(format)
}

// Without type resolution: one analysis per source set.
val sourceLint = tasks.withType<Detekt>().matching { it.name.endsWith("SourceSet") }
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(format)
    dependsOn(sourceLint)
}

val detektAll =
    tasks.register("detektAll") {
        group = "verification"
        description = "Runs every detekt analysis, with type resolution where it has it."
        dependsOn(sourceLint)
        dependsOn(tasks.withType<Detekt>().matching { it.name == "detektMainJvm" || it.name == "detektTestJvm" })
    }
tasks.named("check") { dependsOn(detektAll) }
