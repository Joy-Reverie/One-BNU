import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// release 签名。凭据放在项目根目录的 keystore.properties（已在 .gitignore 里，
// 绝不进版本库）。格式：
//     storeFile=/绝对路径/xxx.jks     （写相对路径时按项目根目录解析）
//     storePassword=…
//     keyAlias=…
//     keyPassword=…
// 文件缺失或签名库不存在时只打印一行提醒，release 仍可构建，只是产出未签名包。
// ---------------------------------------------------------------------------
val keystoreProps = Properties().also { props ->
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
val releaseStoreFile: File? = keystoreProps.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
if (releaseStoreFile == null) {
    logger.warn("未找到可用的 release 签名库（keystore.properties → storeFile），release 包将不签名")
}

android {
    namespace = "io.github.joyreverie.onebnu"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.joyreverie.onebnu"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "1.9.15"

        // 「检查更新」查询的 GitHub 仓库；fork 后改这里即可指向自己的 Releases
        buildConfigField("String", "GITHUB_REPO", "\"Joy-Reverie/One-BNU\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 混淆 + 裁剪无用代码与资源，规则与理由见 proguard-rules.pro。
            // 每次发版归档 build/outputs/mapping/release/mapping.txt，用于还原崩溃栈。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只是桩，单元测试里换成真实实现才能测课表缓存的编解码
    testImplementation("org.json:json:20231013")
}
