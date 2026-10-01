package com.wludy.rolithax.launcher.ui.settings

import com.wludy.rolithax.launcher.data.model.LocalJava

/** Java 启动方式类型(严格对齐网页版 javaType)。 */
enum class JavaType(val value: String, val label: String) {
    ONLINE("online", "MSLX 在线下载 (Java)"),
    LOCAL("local", "使用本地版本 (Java)"),
    CUSTOM("custom", "自定义路径 (Java)"),
    ENV("env", "环境变量 (Java)"),
    DOCKER_JAVA("docker-java", "Docker MSLX 内置运行时"),
    DOCKER_CUSTOM("docker-custom", "Docker 自定义容器"),
    MCDR("mcdr", "MCDReforged (MCDR)"),
    MCDR_DOCKER_JAVA("mcdr-docker-java", "MCDReforged (Docker 内置 Java)"),
    MCDR_DOCKER_CUSTOM("mcdr-docker-custom", "MCDReforged (Docker 自定义)"),
    NONE("none", "自定义命令 (无 Java)"),
}

/** Docker 内置运行时可选 Java 版本。 */
val DOCKER_JAVA_VERSIONS = listOf("8", "11", "17", "21", "25")

/** 从 dockerImage 提取内置运行时 Java 版本。 */
fun dockerJavaVersion(dockerImage: String?): String =
    dockerImage?.removePrefix("MSLX://DockerImage/Java/")?.takeIf { it.isNotBlank() } ?: "25"

/** 生成内置运行时镜像伪协议。 */
fun dockerImageForVersion(version: String): String = "MSLX://DockerImage/Java/$version"

/** 加载时解析当前 java 值对应的类型(严格对齐网页版解析逻辑)。 */
fun parseJavaType(
    java: String,
    dockerImage: String?,
    args: String?,
    localJavas: List<LocalJava>,
): JavaType {
    val isMcdr = (args ?: "").contains("mcdreforged")
    return when {
        isMcdr -> {
            if (java == "docker-java" || java == "docker-custom") {
                val isPreset = dockerImage?.startsWith("MSLX://DockerImage/Java/") ?: true
                if (isPreset) JavaType.MCDR_DOCKER_JAVA else JavaType.MCDR_DOCKER_CUSTOM
            } else {
                JavaType.MCDR
            }
        }

        java == "docker-java" -> JavaType.DOCKER_JAVA
        java == "docker-custom" -> JavaType.DOCKER_CUSTOM
        java == "none" -> JavaType.NONE
        java == "java" -> JavaType.ENV
        java.startsWith("MSLX://Java/") -> JavaType.ONLINE
        else -> if (localJavas.any { it.path == java }) JavaType.LOCAL else JavaType.CUSTOM
    }
}

/** 切换到某类型时应写入的 java 字段值(严格对齐网页版 watch 映射)。 */
fun defaultJavaFor(type: JavaType): String = when (type) {
    JavaType.ENV -> "java"
    JavaType.DOCKER_JAVA -> "docker-java"
    JavaType.DOCKER_CUSTOM -> "docker-custom"
    JavaType.MCDR -> "none"
    JavaType.MCDR_DOCKER_JAVA, JavaType.MCDR_DOCKER_CUSTOM -> "docker-custom"
    JavaType.NONE -> "none"
    else -> "" // online / local / custom 等用户进一步选择或输入
}
