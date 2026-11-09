plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Private signing material comes only from CI secrets or local shell environment.
val releaseSigningKeys = listOf(
    "HG_RELEASE_STORE_FILE",
    "HG_RELEASE_STORE_PASSWORD",
    "HG_RELEASE_KEY_ALIAS",
    "HG_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningKeys.associateWith { System.getenv(it) }
val releaseSigningConfigured = releaseSigningKeys.all {
    !releaseSigningValues[it].isNullOrBlank()
}

android {
    namespace = "com.yagay.hongguo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.yagay.hongguo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-web-rewrite"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseSigningValues.getValue("HG_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("HG_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("HG_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("HG_RELEASE_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") { isMinifyEnabled = false }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Apache-2.0, published Android AAR with Go core; optional read-only source.
    implementation("io.github.beilunyang:openhongguo:0.2.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** Fail early on missing secrets instead of accidentally packaging an unsigned release. */
val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verify the release signing environment before building."
    doLast {
        val missing = releaseSigningKeys.filter { releaseSigningValues[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing not configured. Missing environment variable(s): " +
                    missing.joinToString(", ")
            )
        }
        val keystore = file(releaseSigningValues.getValue("HG_RELEASE_STORE_FILE")!!)
        if (!keystore.isFile) {
            throw GradleException("Release signing keystore file was not found.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}
