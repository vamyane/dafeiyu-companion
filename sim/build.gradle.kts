plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:state"))
    implementation(project(":core:proactive"))
    implementation(project(":core:card"))
    implementation(project(":core:memory"))
    implementation(project(":core:reminder"))
    implementation(project(":core:llm"))
}

application {
    mainClass.set("com.dafeiyu.companion.sim.SimulationKt")
}

tasks.register<JavaExec>("liveRun") {
    group = "application"
    description = "用真实 DeepSeek API 跑一次联调"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dafeiyu.companion.sim.LiveMainKt")
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("reminderLiveRun") {
    group = "application"
    description = "用真实 DeepSeek API 跑一次提醒表达联调"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dafeiyu.companion.sim.ReminderLiveMainKt")
    workingDir = rootProject.projectDir
}

tasks.register("printRuntimeClasspath") {
    group = "help"
    doLast {
        println(sourceSets["main"].runtimeClasspath.asPath)
    }
}

kotlin {
    jvmToolchain(21)
}
