plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.serialization)
    testImplementation(libs.junit)
}
