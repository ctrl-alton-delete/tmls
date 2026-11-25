import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Cargo workspace is the repository root, one level above this Gradle project.
val cargoWorkspace: File = rootProject.projectDir.parentFile
val rustSources = files(
    cargoWorkspace.resolve("Cargo.toml"),
    cargoWorkspace.resolve("Cargo.lock"),
    fileTree(cargoWorkspace.resolve("crates/client-core")) { exclude("tests/**") },
    fileTree(cargoWorkspace.resolve("crates/core-ffi")),
)
// ABIs shipped in the APK (spec NF-3); `-Ptmls.abis=arm64-v8a` builds one for a faster loop.
val shippedAbis: List<String> = (findProperty("tmls.abis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }
// `-Ptmls.rust=false` skips the Android build of the Rust library (no NDK or Android Rust targets
// needed); the APK then builds but fails at runtime when it calls into Rust.
val rustWanted: Boolean = (findProperty("tmls.rust") as String?)?.toBoolean() ?: true

/** `cargo ndk`: builds `libcore_ffi.so` for each ABI into `outDir/<abi>/`. */
abstract class CargoNdkTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val platform: Property<Int>
    @get:Input abstract val buildRust: Property<Boolean>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (!buildRust.get()) return
        val args = mutableListOf("cargo", "ndk", "--platform", platform.get().toString())
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-o", out.absolutePath, "build", "--release", "--locked", "-p", "core-ffi")
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(args)
        }
    }
}

/** UniFFI: Kotlin bindings for `core-ffi`, generated from a host build of the library. */
abstract class UniffiBindingsTask : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = workspace.get().asFile
        val out = outDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = root
            commandLine("cargo", "build", "--locked", "-p", "core-ffi", "--features", "bindgen")
        }
        exec.exec {
            workingDir = root
            commandLine(
                "cargo", "run", "-q", "--locked", "-p", "core-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", root.resolve("target/debug/libcore_ffi.so").absolutePath,
                "--language", "kotlin", "--no-format", "--out-dir", out.absolutePath,
            )
        }
    }
}

val cargoNdk = tasks.register<CargoNdkTask>("cargoNdk") {
    abis.set(shippedAbis)
    platform.set(29)
    buildRust.set(rustWanted)
    sources.from(rustSources)
    workspace.set(cargoWorkspace)
    outDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

val uniffiBindings = tasks.register<UniffiBindingsTask>("uniffiBindings") {
    sources.from(rustSources)
    workspace.set(cargoWorkspace)
    outDir.set(layout.buildDirectory.dir("generated/uniffi"))
}

android {
    namespace = "dev.tmls"
    // AndroidX of Nov 2026 needs 37.1+; minor API levels need the block form.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.tmls"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += shippedAbis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs {
            // Extract native libraries to nativeLibraryDir on install. The bundled ssh (M1,
            // spec ARCH-3) is exec'd from there; with the default (uncompressed, not extracted)
            // there is no file on disk to exec.
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdk, CargoNdkTask::outDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindings, UniffiBindingsTask::outDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
}
