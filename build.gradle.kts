plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

//自动复制到服务端文件夹
tasks.register<Copy>("copyPluginJar") {
    // 只有当属性存在时才注册复制动作
    val dest = project.findProperty("pluginCopyPath") as String? ?: return@register
    from(layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))
    into(dest)
}

tasks.named("build"){
    finalizedBy("copyPluginJar")
}
