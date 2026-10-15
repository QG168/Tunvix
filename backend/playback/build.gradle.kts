plugins { id("com.android.library") }

android {
    namespace = "com.tunvix.backend.playback"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    testOptions {
        // 允许 android.util.Log 等在本地单测中静默返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:contract"))
    implementation(project(":backend:audio-decoder"))

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
