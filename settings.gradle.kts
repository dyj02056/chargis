pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// app의 jvmToolchain(17)에 맞는 JDK가 PC에 없으면 Gradle이 자동으로 내려받게 한다.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK(Vehicle, NaviHelper, TextToSpeech, SpeechToText). 로그인 없이 받을 수 있는 공개 저장소다.
        // TTS·STT는 ai.pleos.playground.security(universal-access)도 함께 받아 하위 그룹까지 연다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("""ai\.pleos\.playground(\..*)?""") }
        }
    }
}

rootProject.name = "chargis"
include(":app")
