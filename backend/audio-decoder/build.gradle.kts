plugins { id("com.android.library") }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("proguard-rules.txt")
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-decoder:1.11.1")
    implementation("org.checkerframework:checker-qual:3.49.2")
}
