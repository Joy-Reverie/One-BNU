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

// ---------------------------------------------------------------------------
// 版本命名 `<学年><学期>.<序号>`：`2627s1.01` 是 2026-2027 学年第一学期（秋季）的第 1 个版本，
// `2627s2.01` 是同一学年第二学期（春季）的第 1 个版本。学年取起止两年的后两位，第二个年份
// 总是起始年 +1；序号每学期从 01 重新数起。先后一律按 学年 → 学期 → 序号，春季学期因此排在
// 同一学年的秋季之后。tag、APK 文件名、归档目录都用这个串（`v2627s1.01`）。
//
// versionCode 由版本名推导，发版时只改下面 defaultConfig 里的 versionName 一处：
//     学年 * 100000 + 学期 * 10000 + 序号
//     2627s1.01 → 262710001，2627s2.01 → 262720001，2728s1.01 → 272810001
// 严格递增，也远大于旧数字版本的最后一个 versionCode（1.9.37 = 57），升级方向不会反。
// 写错格式直接让构建失败，而不是发出一个排序不对的包；应用内比较版本名的是
// `core/update/UpdateChecker.parts`，两边的先后规则必须一致（有单元测试锁定）。
// ---------------------------------------------------------------------------
fun semesterVersionCode(name: String): Int {
    val m = Regex("""^(\d{2})(\d{2})s([12])\.(\d{1,3})$""").matchEntire(name)
        ?: throw GradleException("版本名「$name」不符合 <学年><学期>.<序号>，例：2627s1.01（2026-2027 学年第一学期第 1 版）")
    val (from, to, semester, serial) = m.destructured
    if (to.toInt() != (from.toInt() + 1) % 100) {
        throw GradleException("版本名「$name」的学年要写连续两年，例：2627 表示 2026-2027 学年")
    }
    if (serial.toInt() < 1) throw GradleException("版本名「$name」的序号从 01 起数")
    return "$from$to".toInt() * 100_000 + semester.toInt() * 10_000 + serial.toInt()
}

android {
    namespace = "io.github.joyreverie.onebnu"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.joyreverie.onebnu"
        minSdk = 26
        targetSdk = 35
        versionName = "2627s1.08"
        versionCode = semesterVersionCode(versionName!!)

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
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只是桩，单元测试里换成真实实现才能测课表缓存的编解码
    testImplementation("org.json:json:20231013")
}
