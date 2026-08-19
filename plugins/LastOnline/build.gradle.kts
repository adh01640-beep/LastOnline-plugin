plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

android {
    namespace = "com.aliucord.plugins.lastonline"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

aliucord {
    author("Adham", 0L)
    description = "Shows last online presence timestamp."
    version = "1.0.1"
    deploy.set(true)
}
