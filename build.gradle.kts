// Mineradio Android —— 顶层构建脚本
// 移植自 https://github.com/XxHuberrr/Mineradio (GPL-3.0)
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
