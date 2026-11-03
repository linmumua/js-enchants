pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.tabooproject.org/repository/releases/")
    }
    resolutionStrategy {
        eachPlugin {
            // TabooLib 的 Gradle 插件没有发布插件 marker，只能映射到实际模块坐标。
            if (requested.id.id == "io.izzel.taboolib") {
                useModule("io.izzel.taboolib:taboolib-gradle-plugin:${requested.version}")
            }
        }
    }
}

rootProject.name = "js-enchants"
