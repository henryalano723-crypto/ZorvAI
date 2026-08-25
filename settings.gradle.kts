pluginManagement {
    repositories {
        // 中国网络优先使用镜像，避免 plugins.gradle.org / Maven Central TLS 握手失败。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // GeckoView（Mozilla 开源浏览器引擎）官方仓库
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "Quro AI"
include(":app", ":aidl-aci-browser", ":aidl-aci-core", ":mnn", ":llama")
project(":mnn").projectDir = file("llm/mnn")
project(":llama").projectDir = file("llm/llama")
