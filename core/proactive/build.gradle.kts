plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:state"))
}

kotlin {
    jvmToolchain(21)
}
