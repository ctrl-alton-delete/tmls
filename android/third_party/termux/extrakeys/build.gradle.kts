// Termux termux-shared's extra-keys row (terminal/io/extrakeys + TerminalExtraKeys) at 0.118.3,
// vendored (spec Q1; see ../PATCHES.md). GPLv3 only, like the app.
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.termux.shared.terminal.io"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal-view"))
    implementation(libs.androidx.annotation)
}
