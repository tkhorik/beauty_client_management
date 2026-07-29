import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}

// Release signing material never lives in git. Locally, a developer copies
// their own keystore path/passwords into android/keystore.properties (already
// gitignored). In CI, android-release.yml writes this same file from GitHub
// Secrets immediately before the build, so this code path is identical in
// both places — no separate CI-only signing logic to keep in sync.
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.beauty.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beauty.app"
        minSdk = 24
        targetSdk = 34
        // versionCode must strictly increase on every published release —
        // Android refuses to install an APK whose versionCode is <= the one
        // already on the device, so a stale value silently blocks upgrades.
        //
        // CI therefore derives it from the git commit count rather than from a
        // literal here (see android-release.yml). That is monotonic by
        // construction, and unlike an auto-bump commit it needs no write access
        // to the protected `main` branch. The value below is only the fallback
        // for local builds.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1).get()

        // Release builds are passed the git tag with its leading "v" stripped
        // (v1.2.0 -> "1.2.0"), so the APK version and the tag cannot disagree.
        // The "-local" fallback makes a hand-built APK obvious at a glance.
        versionName = providers.gradleProperty("versionName").orElse("1.0.0-local").get()


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // Only registered when keystore.properties actually exists, so a
        // clean checkout without signing material still configures and runs
        // `assembleDebug` / unit tests fine — only `assembleRelease` /
        // `bundleRelease` need it, and they fail loudly below if it's missing.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl")
                .orElse("https://api.example.invalid/")
                .get()
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fails the build with a clear message instead of silently
                // producing an unsigned APK/AAB that can't be installed on a
                // real device or uploaded to Play.
                tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
                    .configureEach {
                        doFirst {
                            throw GradleException(
                                "Missing android/keystore.properties — release builds must be signed. " +
                                    "See deploy_strategy.md for how to generate a keystore and wire CI secrets."
                            )
                        }
                    }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room DB Offline Caching
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // WorkManager Background Sync
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Ktor Client for Android
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("io.ktor:ktor-client-auth:2.3.8")

    // Jetpack Security — EncryptedSharedPreferences for JWT token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Jetpack Navigation — NavHost for screen routing
    implementation("androidx.navigation:navigation-compose:2.7.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-client-mock:2.3.8")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
