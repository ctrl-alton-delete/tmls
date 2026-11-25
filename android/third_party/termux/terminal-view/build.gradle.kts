// Termux terminal-view 0.118.3, vendored (spec Q1; see ../PATCHES.md). Apache-2.0.
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.termux.view"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation(libs.androidx.annotation)
}
