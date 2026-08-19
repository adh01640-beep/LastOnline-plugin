plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

android {
    compileSdk = 34
    
    defaultConfig {
        minSdk = 21
    }
}

aliucord {
    author("Adham", 0L)
    description = "Displays the user's last online timestamp on their profile."
    version = "1.0.1"
    deploy.set(true)
}
