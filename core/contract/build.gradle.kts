plugins { id("com.android.library") }
android { namespace = "com.tunvix.contract"; compileSdk = 37; defaultConfig { minSdk = 29 } }
dependencies { api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") }
