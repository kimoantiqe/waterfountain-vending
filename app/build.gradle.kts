import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.waterfountainmachine.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.waterfountainmachine.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // SIGNING CONFIGURATION
    // Store sensitive signing info in local.properties (not committed to git)
    signingConfigs {
        create("release") {
            // Read from keystore.properties file
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
                
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                // Fallback: Use debug keystore for local development
                // This allows builds to work even without keystore.properties
                println("⚠️  keystore.properties not found. Using debug keystore for release builds.")
                println("   Create keystore.properties for proper release signing.")
            }
        }
    }

    // PRODUCT FLAVORS - Dev and Prod environments
    flavorDimensions += "environment"
    
    productFlavors {
        create("dev") {
            dimension = "environment"
            // No applicationIdSuffix - use same package name as Firebase config
            versionNameSuffix = "-dev"
            
            // BuildConfig fields for runtime environment detection
            buildConfigField("String", "ENVIRONMENT", "\"development\"")
            buildConfigField("boolean", "IS_PRODUCTION", "false")
            buildConfigField("String", "API_BASE_URL", "\"https://us-central1-waterfountain-dev.cloudfunctions.net\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"waterfountain-dev\"")
        }
        
        create("prod") {
            dimension = "environment"
            // No applicationIdSuffix - use base package name
            
            // BuildConfig fields
            buildConfigField("String", "ENVIRONMENT", "\"production\"")
            buildConfigField("boolean", "IS_PRODUCTION", "true")
            buildConfigField("String", "API_BASE_URL", "\"https://us-central1-waterfountain-25886.cloudfunctions.net\"")
            buildConfigField("String", "FIREBASE_PROJECT_ID", "\"waterfountain-25886\"")
        }
    }

    buildTypes {
        debug {
            // Don't add suffix when using flavors - Firebase config won't match
            versionNameSuffix = "-DEBUG"
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Apply signing config if keystore.properties exists
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true // Enable BuildConfig generation
    }

    // Test configuration following Android best practices
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }

    // Packaging options to avoid conflicts
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
        }
    }
}

// Kapt configuration
kapt {
    correctErrorTypes = true
    arguments {
        arg("dagger.fastInit", "enabled")
        arg("dagger.strictMode", "enabled")
    }
}

// Force consistent dependency versions to avoid conflicts
configurations.all {
    resolutionStrategy {
        force("androidx.constraintlayout:constraintlayout:2.1.4")
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    
    // Security - for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0") // Updated from alpha06 to stable
    
    // Firebase - for backend integration
    implementation(platform("com.google.firebase:firebase-bom:32.7.0")) // Keeping stable version for compatibility
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    
    // Firebase App Check - Include both providers (conditional use at runtime)
    implementation("com.google.firebase:firebase-appcheck-ktx")
    implementation("com.google.firebase:firebase-appcheck-debug:17.1.1")
    implementation("com.google.firebase:firebase-appcheck-playintegrity:17.1.1")
    
    // Kotlin Coroutines for Firebase async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    
    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // QR Code dependencies
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Confetti library for celebration animation
    implementation("nl.dionsegijn:konfetti-xml:2.0.4")

    // Vendor SDK (SerialPortUtils-release.aar) - replaces USB serial library
    implementation(files("libs/SerialPortUtils-release.aar"))

    // Unit Testing dependencies
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.10.3")
    
    // Modern Testing Libraries
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
}