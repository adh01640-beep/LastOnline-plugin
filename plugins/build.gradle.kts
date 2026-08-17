plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

aliucord {
    author("Adham", 0L)
    description = "Shows last online presence timestamp."
    version = "1.0.0"
    deploy.set(true)
}
