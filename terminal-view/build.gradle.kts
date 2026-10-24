plugins { id("com.android.library") }
android {
    namespace = "com.termux.view"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    lint { warningsAsErrors = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
