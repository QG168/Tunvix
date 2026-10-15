plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.tunvix.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "com.tunvix.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 22
        versionName = "0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "mode"
    productFlavors {
        create("demo") { dimension = "mode"; applicationIdSuffix = ".demo"; versionNameSuffix = "-demo" }
        create("live") { dimension = "mode" }
    }
}
// UI-only builds deliberately disable live, which requires all three backend modules.
if (providers.gradleProperty("uiOnly").orNull == "true") {
    androidComponents.beforeVariants(androidComponents.selector().withFlavor("mode" to "live")) { it.enable = false }
}
dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
    if (providers.gradleProperty("uiOnly").orNull != "true") {
        add("liveImplementation", project(":backend:importer"))
        add("liveImplementation", project(":backend:catalog"))
        add("liveImplementation", project(":backend:playback"))
        androidTestImplementation(project(":backend:importer"))
        androidTestImplementation(project(":backend:catalog"))
        androidTestImplementation(project(":backend:playback"))
    }
    implementation(project(":core:contract"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
