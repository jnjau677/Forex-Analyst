plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.devcode940.ForexAnalyst"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devcode940.ForexAnalyst"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // WHY: Ship optimized + obfuscated in release — smaller APK and
            // no trivially readable source in the shipped binary.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // WHY: AndroidIDE's Gradle runs on JDK 17. javac cannot use --release 21
    // on a JDK 17 toolchain, so we target 17 (fully sufficient for this app).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // WHY: ForexRepository's mappers call android.util.Log. Returning
            // default values lets those pure-Kotlin paths run in plain JVM
            // unit tests without Robolectric or an emulator.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

kapt {
    arguments {
        // WHY: exportSchema = true writes the Room schema JSON here. Those
        // files are the input for MigrationTestHelper — without them, schema
        // migrations cannot be tested and are guaranteed to break users.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // WHY: EncryptedSharedPreferences for API keys (Jetpack Security).
    implementation("androidx.security:security-crypto:1.1.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // WHY: debugImplementation — the logging interceptor prints headers,
    // which would leak API keys into logcat in release builds.
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // WHY: explicit runner for the instrumented Room integration test
    // (androidx.test.runner.AndroidJUnitRunner is set as the test runner).
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // WHY: Room in-memory database for the repository integration test —
    // the Android equivalent of Testcontainers (real SQLite engine, real
    // migrations/DAOs, ephemeral per-test instance).
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
