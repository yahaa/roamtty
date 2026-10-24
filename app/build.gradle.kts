import java.util.Properties
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val signingFile = rootProject.file("signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }
val appVersionName = providers.gradleProperty("itermuxVersionName").get()
/** Play requires a strictly increasing versionCode: MAJOR.MINOR.PATCH maps to MAJOR*10000 + MINOR*100 + PATCH. */
fun versionCodeOf(name: String): Int {
    val (major, minor, patch) = requireNotNull(Regex("""^(\d+)\.(\d+)\.(\d+)""").find(name)) {
        "itermuxVersionName must start with MAJOR.MINOR.PATCH: $name"
    }.destructured.toList().map(String::toInt)
    require(minor < 100 && patch < 100) { "MINOR and PATCH must be below 100: $name" }
    return major * 10000 + minor * 100 + patch
}
android {
    namespace = "io.itermux.app"
    compileSdk = 36
    defaultConfig {
        applicationId = providers.gradleProperty("itermuxApplicationId").orElse("com.roamtty.app").get()
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("itermuxVersionCode").map(String::toInt).getOrElse(versionCodeOf(appVersionName))
        versionName = appVersionName
        testInstrumentationRunner = "io.itermux.app.ItermuxTestRunner"
    }
    lint { warningsAsErrors = true }
    if (signingFile.exists()) signingConfigs.create("localRelease") {
        storeFile = rootProject.file(signingValues.getProperty("storeFile"))
        storePassword = signingValues.getProperty("storePassword")
        keyAlias = signingValues.getProperty("keyAlias")
        keyPassword = signingValues.getProperty("keyPassword")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingFile.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
        // End-to-end suite (scripts/e2e/run.sh): release code paths, not debuggable, so timings resemble a shipped
        // build, but unshrunk so the androidTest APK can call into the app. Its own package keeps it apart from an
        // installed Roamtty and its sessions.
        create("e2e") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            applicationIdSuffix = ".e2e"
            versionNameSuffix = "-e2e"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    testBuildType = providers.gradleProperty("itermuxTestBuildType").getOrElse("debug")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/**", "META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
            // Bouncy Castle data for Picnic signatures and PKIX path-review messages; SSH and mosh use neither.
            "org/bouncycastle/pqc/crypto/picnic/**", "org/bouncycastle/x509/*.properties",
            "DebugProbesKt.bin",
        )
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":ssh-core"))
    implementation(project(":desktop-core"))
    implementation(project(":terminal-view"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "e2eImplementation"("androidx.compose.ui:ui-test-manifest")
}

/** Builds the in-app license text from the same files the repository ships, so it cannot drift. */
abstract class ThirdPartyNotices : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val summary: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val projectLicense: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val licenses: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fontLicenses: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        fun sorted(files: ConfigurableFileCollection) = files.files.sortedBy { it.name.lowercase() }
        val text = StringBuilder(summary.get().asFile.readText()).append("\n\n").append(projectLicense.get().asFile.readText())
        sorted(licenses).forEach { text.append("\n\n=== ${it.name} ===\n").append(it.readText()) }
        text.append("\n\n===== Bundled terminal fonts =====\n")
        sorted(fontLicenses).forEach { text.append("\n----- ${it.name} -----\n").append(it.readText()) }
        outputDir.get().asFile.resolve("THIRD_PARTY_NOTICES.txt").writeText(text.toString())
    }
}
val thirdPartyNotices = tasks.register<ThirdPartyNotices>("thirdPartyNotices") {
    summary = rootProject.file("THIRD_PARTY_NOTICES.md")
    projectLicense = rootProject.file("LICENSE")
    licenses.from(rootProject.fileTree("licenses") { exclude("fonts/**") })
    fontLicenses.from(rootProject.fileTree("licenses/fonts"))
}
/** Ships docs/PRIVACY.md, the policy linked from the Play listing, so the app shows the same text. */
abstract class PrivacyPolicy : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val policy: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() { policy.get().asFile.copyTo(outputDir.get().asFile.resolve("PRIVACY.md"), overwrite = true) }
}
val privacyPolicy = tasks.register<PrivacyPolicy>("privacyPolicy") { policy = rootProject.file("docs/PRIVACY.md") }
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(thirdPartyNotices, ThirdPartyNotices::outputDir)
    variant.sources.assets?.addGeneratedSourceDirectory(privacyPolicy, PrivacyPolicy::outputDir)
}
