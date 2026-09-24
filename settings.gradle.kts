// Maven 仓库配置（默认走官方源，行为与之前完全一致）。
//
// 可选镜像注入：国内网络或 CI 环境可通过环境变量整体切换仓库地址，
// 留空即官方源；二者需同时代理对应的仓库内容（如阿里云镜像对）。
//   MSLX_MAVEN_GOOGLE_MIRROR  —— 替换 google()   例：https://maven.aliyun.com/repository/google
//   MSLX_MAVEN_CENTRAL_MIRROR —— 替换 mavenCentral() 例：https://maven.aliyun.com/repository/public
//
// 说明：CNB 制品库（maven.cnb.cool）为私有托管仓库，不提供对 google/mavenCentral 的
// 代理功能（2026-02 调研 docs.cnb.cool/zh/artifact），因此镜像开关做成通用注入，
// 不绑定任何特定制品库；CNB 流水线目前直接访问官方源可用，未注入。
pluginManagement {
    val googleMirror = System.getenv("MSLX_MAVEN_GOOGLE_MIRROR")?.trim().orEmpty()
    val centralMirror = System.getenv("MSLX_MAVEN_CENTRAL_MIRROR")?.trim().orEmpty()
    repositories {
        if (googleMirror.isNotEmpty()) {
            maven { url = uri(googleMirror) }
        } else {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        if (centralMirror.isNotEmpty()) maven { url = uri(centralMirror) } else mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val googleMirror = System.getenv("MSLX_MAVEN_GOOGLE_MIRROR")?.trim().orEmpty()
    val centralMirror = System.getenv("MSLX_MAVEN_CENTRAL_MIRROR")?.trim().orEmpty()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (googleMirror.isNotEmpty()) maven { url = uri(googleMirror) } else google()
        if (centralMirror.isNotEmpty()) maven { url = uri(centralMirror) } else mavenCentral()
    }
}

rootProject.name = "MSLX_APP-Android"
include(":app")
