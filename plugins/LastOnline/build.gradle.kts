plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.aliucord.plugin)
}

aliucord {
    author("Adham", 0L)
    description.set("Displays the last seen timestamp of a user on their profile.")
    version.set("1.0.0")
    deploy.set(true)
}
