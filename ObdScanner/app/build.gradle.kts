plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.odit.obd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.odit.obd"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    // A fixed key kept in the repo so every build (local or CI) can be
    // installed over the previous one. This is a personal sideloaded app,
    // not a Play Store app.
    signingConfigs {
        create("odit") {
            storeFile = file("odit-sign.jks")
            storePassword = "oditobd123"
            keyAlias = "odit"
            keyPassword = "oditobd123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("odit")
        }
        debug {
            signingConfig = signingConfigs.getByName("odit")
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
    // The data packs (WMI, VIN rules, trouble codes...) live in ../../odit-data so they can also be
    // published for download; the same files are bundled into the APK as the offline baseline.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/oditData"))

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

val copyOditData by tasks.registering(Copy::class) {
    from(rootProject.file("../odit-data")) {
        include("manifest.json", "packs/*.json")
    }
    into(layout.buildDirectory.dir("generated/oditData/odit-data"))
}
tasks.named("preBuild") { dependsOn(copyOditData) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for plain JVM tests (Android's copy is only a stub there).
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // UI flow test: runs the real app in demo mode on Robolectric and saves screenshots.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
