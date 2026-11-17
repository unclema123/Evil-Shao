plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "com.unclema123"
version = "1.0.0"

repositories {
    // 只走 Maven Central：这台机器上 plugins.gradle.org 会超时，
    // 所以不用 org.openjfx.javafxplugin 插件，改为直接声明 JavaFX 依赖。
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // -win 分类的 jar 同时包含 Java 类和本地 DLL，以 classpath 方式运行即可
    implementation("org.openjfx:javafx-base:21.0.5:win")
    implementation("org.openjfx:javafx-graphics:21.0.5:win")
    implementation("org.openjfx:javafx-controls:21.0.5:win")
}

application {
    mainClass = "com.unclema123.pignpc.LauncherKt"
    applicationName = "PigNpc"
}

tasks.jar {
    archiveFileName.set("PigNpc.jar")
    manifest {
        attributes["Main-Class"] = "com.unclema123.pignpc.LauncherKt"
    }
}