plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

android {
    sourceSets {
        named("main") {
            java.srcDirs("src/main/kotlin", "src/main/java")
        }
    }
}

aliucord {
    author("Adham", 0L)
    description = "Shows last online presence timestamp."
    version = "1.0.1"
    deploy.set(true)
}
