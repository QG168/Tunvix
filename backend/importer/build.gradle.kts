plugins { id("com.android.library") }

android {
    namespace = "com.tunvix.backend.importer"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
}

// 测试固定样本放在 src/test/resources/samples，脱敏后随模块提交。
dependencies {
    // 公开入口实现 contract 的 SourceImporter，契约需要暴露给使用方（app）。
    api(project(":core:contract"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
