import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    id("com.eignex.kmp")
    id("com.eignex.publish.github")
    kotlin("plugin.serialization")
}

repositories {
    ivy {
        url = uri("https://github.com/Eignex/kbuild/releases/download")
        patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
        metadataSources { gradleMetadata() }
        content { includeModule("com.eignex", "kbuild-platform") }
    }
}

eignexPublish {
    description.set("Shared schema-serialization plumbing for Eignex libraries (kumulant, klause, combo).")
    githubRepo.set("Eignex/skema")
}

// Run the real JVM artifact on both the supported floor and the current LTS.
eignexBuild {
    packagedTestJdks.set(listOf(17, 25))
}

tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    // Do not reuse results produced with the old aggregate-only failure policy.
    inputs.property("kotlin.tests.individualTaskReports", providers.gradleProperty("kotlin.tests.individualTaskReports"))
}

// Routine checks cover JVM + Linux x64; release validation opts into every target.
val fullTargets = providers.gradleProperty("targets.full").isPresent

@OptIn(ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    abiValidation {
        referenceDumpDir.set(layout.projectDirectory.dir(if (fullTargets) "api-full" else "api"))
    }
    jvm()
    linuxX64()
    if (fullTargets) {
        js { browser(); nodejs() }
        wasmJs { browser(); nodejs() }
        wasmWasi { nodejs() }
        linuxArm64()
        macosArm64(); mingwX64()
        iosX64(); iosArm64(); iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core")
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json")
        }
        commonTest.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf")
        }
        jvmTest.dependencies {
            implementation("com.networknt:json-schema-validator:1.5.9")
        }
    }
}

val schemaTestCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
tasks.register<JavaExec>("validateJsonSchemas") {
    group = "verification"
    description = "Validate emitted JSON Schema fixtures against draft 2020-12."
    dependsOn("jvmTest")
    classpath(schemaTestCompilation.output.allOutputs, schemaTestCompilation.runtimeDependencyFiles)
    mainClass.set("com.eignex.skema.serialization.CheckJsonSchemasKt")
    args(providers.gradleProperty("schemaReportDir").getOrElse("build/test-results/jvmTest"))
}

tasks.named("check") { dependsOn("validateJsonSchemas") }

dokka {
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(projectDir.resolve("src"))
            val sub = projectDir.relativeTo(rootDir).invariantSeparatorsPath
            val prefix = if (sub.isEmpty()) "src" else "$sub/src"
            remoteUrl("https://github.com/Eignex/${rootProject.name}/blob/main/$prefix")
            remoteLineSuffix.set("#L")
        }
    }
}

// Select an installed simulator in CI rather than relying on a particular iPhone model.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosSimulatorDevice").orNull?.let { device.set(it) }
}
