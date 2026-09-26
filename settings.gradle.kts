pluginManagement {
    repositories {
        // CI/国外环境优先用官方仓库（稳定快速），国内开发时自动 fallback 到阿里云镜像。
        // 注意：GitHub Actions 运行在国外，阿里云镜像可能 502，必须官方源在前。
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // Xposed API 与部分模块依赖走 JitPack
        maven("https://jitpack.io")
    }
}

rootProject.name = "Yanwai"
include(":app")
