plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.android.application") version "8.13.2" apply false
}

subprojects {
    // 默认把各模块产物集中到根目录下的 .build，避免工作区文件索引占用 build 目录。
    // 也可以通过 -Pcompanion.buildRoot=/path/to/output 指定外部目录。
    val externalBuildRoot = file(
        providers.gradleProperty("companion.buildRoot")
            .getOrElse(rootProject.layout.projectDirectory.dir(".build").asFile.absolutePath)
    )
    val moduleDirName = path.trim(':').replace(':', '-').ifBlank { "root" }
    layout.buildDirectory.set(externalBuildRoot.resolve(moduleDirName))

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.withType<org.gradle.api.tasks.JavaExec>().configureEach {
        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8"
        )
    }
}
