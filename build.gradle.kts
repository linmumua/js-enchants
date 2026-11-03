import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.CommandHelper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.3.20"
    id("io.izzel.taboolib") version "2.0.38"
}

// TabooLib 用 group 作为自身运行期的重定位包名，必须唯一且与 plugin.yml 的 main 对应。
group = "com.linmumua.jsenchants"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.tabooproject.org/repository/releases/")
}

taboolib {
    env {
        // 最小模块集：基础运行期 + Bukkit 平台装载器 + 命令 DSL。
        // 不安装 JavaScript(Nashorn) 与 Kether 模块。
        install(Basic, Bukkit, CommandHelper)
    }
    version {
        taboolib = "6.3.0-75b18a2"
        // 插件描述统一由 src/main/resources/plugin.yml 提供（commands/permissions 无法由 DSL 表达），
        // 因此不让 TabooLib 用 description { } 覆盖它。
        skipPlatformFile = true
    }
    description {
        name("JsEnchants")
        desc("JS 脚本驱动的自定义附魔框架，支持附魔定义与效果脚本热重载。")
        load("POSTWORLD")
        // 必须显式声明，TabooLib 默认值是 1.13。
        bukkitApi("26.3")
        contributors {
            name("linmumua")
        }
    }
    // Rhino 隔离：TabooLibMainTask 用它自己的 ASM 重映射器改写 jar 内所有 .class 的引用、
    // 字符串常量以及条目路径（含资源文件），因此不需要引入 Shadow。
    // 注意：目标包名被 src/main/resources/META-INF/services/ 下的 SPI 描述文件引用，改名要同步改那里。
    relocate("org.mozilla", "com.linmumua.jsenchant.libs.rhino")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.223-stable")
    // SnakeYAML 由服务端提供，这里只编译期引用，避免与 Paper 自带版本冲突。
    compileOnly("org.yaml:snakeyaml:2.4")
    // Kotlin 运行期由 TabooLib 自带，业务侧只编译期引用。
    compileOnly(kotlin("stdlib"))
    // Rhino 由 TabooLibMainTask 直接并入插件 jar（jar 内 org/mozilla/** 会被上面的 relocate 改写）。
    taboo("org.mozilla:rhino:1.9.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.yaml:snakeyaml:2.4")
    testImplementation("io.papermc.paper:paper-api:26.3.build.223-stable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// TabooLib 的注入任务默认不会自己依赖 jar，单独调用时需要显式声明。
tasks.named("taboolibMainTask") {
    dependsOn(tasks.jar)
}

// Rhino 1.9.x 用 ServiceLoader 选正则实现，SPI 文件名是接口的原始 FQCN；ASM 重定位改不了文件名，
// 所以重定位后的那份由 src/main/resources 提供，原始的这份直接剔除，避免留下指向不存在类的死条目。
tasks.jar {
    exclude("META-INF/services/org.mozilla.javascript.RegExpLoader")
}

// 验收脚本沿用 shadowJar 名称：这里只做 TabooLib 最终产物的别名，
// 不再引入 Shadow 插件（实测 Shadow 产物会丢掉 TabooLib 注入的 loader，导致插件无法加载）。
tasks.register("shadowJar") {
    group = "build"
    description = "别名任务：产出 TabooLib 注入并重定位后的最终插件 jar（build/libs/js-enchants-<version>.jar）"
    dependsOn(tasks.named("taboolibMainTask"))
}

tasks.build {
    dependsOn(tasks.named("shadowJar"))
}

tasks.test {
    useJUnitPlatform()
}
