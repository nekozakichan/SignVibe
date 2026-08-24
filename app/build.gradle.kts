plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.ucucite.signvibe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ucucite.signvibe"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Required so the .tflite / .task model files aren't corrupted by APK compression
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Firebase (BoM manages all Firebase library versions together)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    // Onboarding slider / Translate carousel
    implementation(libs.viewpager2)

    // Module lists, RecyclerViews
    implementation(libs.recyclerview)

    // Fragments (Home bottom-nav tabs)
    implementation(libs.fragment)

    // Video playback (lesson videos from Firebase Storage)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Hand landmark detection (Number Detection camera)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.tensorflow.lite)

    // Camera (live detection preview)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Image loading (lesson thumbnails, profile pics)
    implementation(libs.glide)


    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
