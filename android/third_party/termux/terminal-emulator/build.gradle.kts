// Termux terminal-emulator 0.118.3, vendored (spec Q1; see ../PATCHES.md). Apache-2.0.
plugins { alias(libs.plugins.android.library) }

val shippedAbis: List<String> = (findProperty("tmls.abis") as String? ?: "arm64-v8a,x86_64").split(",").map { it.trim() }

android {
    namespace = "com.termux.terminal"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += shippedAbis }
        externalNativeBuild {
            ndkBuild { cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections") }
        }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation(libs.junit)
}
