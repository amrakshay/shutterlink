// A plain JVM module, deliberately. Everything the two phones say to each other is
// defined here, so it can be tested on a desktop in seconds with no emulator and no
// pair of phones. The CI job enforces that it stays free of android.* imports —
// see CONTRIBUTING.md for why that rule is worth keeping.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
