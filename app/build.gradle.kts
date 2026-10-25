// 앱을 "어떻게 만들지" 적은 빌드 설정 파일이다. Gradle(빌드 도구)이 이 파일을 읽고 앱을 조립한다.
// 지원하는 안드로이드 버전, 개발용/배포용 차이, 가져다 쓰는 라이브러리 목록이 여기 있다.

import java.util.Properties

// 빌드에 쓰는 도구들: 안드로이드 앱, 코틀린 언어, Compose 화면, JSON 읽기
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 비밀값은 local.properties에서만 읽는다. 키가 없으면 빈 문자열 → 번들 샘플 데이터로 동작.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.chargis.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chargis.app"   // 앱 고유 이름(설치된 앱을 구별하는 주소)
        minSdk = 28                         // 설치 가능한 가장 낮은 안드로이드 버전(Android 9)
        targetSdk = 36
        versionCode = 1                     // 내부 버전 번호(올릴 때마다 1씩 증가)
        versionName = "0.1.0"               // 사용자에게 보이는 버전
        // true면 데모 샘플 대신 공공 충전소 데이터를 먼저 쓴다(실패하면 샘플로 폴백). 데모 경로는 가상이라 기본은 끔.
        buildConfigField("boolean", "USE_PUBLIC_CHARGER_API", localProps.getProperty("chargis.usePublicChargerApi", "false"))
        // 충전소 API 주소. 비우면 data.go.kr. 키를 앱에 넣지 않으려면 키를 붙여 주는 중계 서버(프록시) 주소를 넣는다.
        buildConfigField("String", "CHARGER_API_BASE_URL", "\"${localProps.getProperty("chargis.chargerApiBaseUrl", "")}\"")
        // 중계 서버(server/charger-proxy.mjs)에 APP_TOKEN을 정했으면 같은 값. 서비스 키가 아니라 남용을 줄이는 표시일 뿐이다.
        buildConfigField("String", "CHARGER_API_TOKEN", "\"${localProps.getProperty("chargis.chargerApiToken", "")}\"")
    }

    // debug = 개발·시연용(개발자 오버레이 켬), release = 배포용(끔)
    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_OVERLAY", "true")
            // data.go.kr 키는 개발용(debug) 빌드에만 넣는다. 앱에 넣은 문자열은 APK에서 꺼낼 수 있기 때문이다.
            buildConfigField("String", "CHARGER_API_KEY", "\"${localProps.getProperty("chargis.chargerApiKey", "")}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEV_OVERLAY", "false")
            // 배포용 빌드에는 키를 넣지 않는다. 공공데이터를 쓰려면 chargis.chargerApiBaseUrl에 프록시를 지정한다.
            buildConfigField("String", "CHARGER_API_KEY", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // 테스트에서 안드로이드 기능(예: Log)을 부르면 오류 대신 기본값을 돌려준다.
        unitTests.isReturnDefaultValues = true
        // Compose 화면 테스트(Robolectric)가 strings.xml·글꼴 같은 리소스를 읽을 수 있게 한다.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// 가져다 쓰는 외부 라이브러리. 정확한 버전은 gradle/libs.versions.toml에 모아 두었다.
// implementation = 앱에 포함, debugImplementation = 개발용 빌드에만, testImplementation = 테스트에만
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Compose 화면 테스트를 에뮬레이터 없이 JVM에서 돌린다(Robolectric).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
