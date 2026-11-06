plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

// CI Debug jobs pass a stable code so each run does not invalidate BuildConfig/manifest inputs.
// Release omits the override and keeps the monotonically increasing workflow run number.
val buildVersionCode = providers.gradleProperty("lnt.versionCode")
  .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
  .map { raw ->
    raw.toIntOrNull()?.takeIf { it > 0 }
      ?: error("lnt.versionCode/GITHUB_RUN_NUMBER must be a positive integer")
  }.getOrElse(7)
val signingKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val signingKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning = listOf(
  signingKeystorePath,
  signingKeystorePassword,
  signingKeyAlias,
  signingKeyPassword,
).all { !it.isNullOrBlank() }

android {
  namespace = "com.breakyuna.noveltranslator"
  compileSdk {
    version = release(libs.versions.androidCompileSdk.get().toInt()) {
      minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
    }
  }

  defaultConfig {
    applicationId = "com.breakyuna.noveltranslator"
    minSdk = libs.versions.androidMinSdk.get().toInt()
    targetSdk = libs.versions.androidTargetSdk.get().toInt()
    versionCode = buildVersionCode
    versionName = "0.7.0-beta"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(signingKeystorePath!!)
        storePassword = signingKeystorePassword
        keyAlias = signingKeyAlias
        keyPassword = signingKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
    debug { }
  }
  compileOptions {
    // Koog (ai.koog:koog-agents) ships Java 17 bytecode; AGP 9 already requires JDK 17.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // Koog's transitive jackson-module-kotlin 2.21.x declares minSdk 26; desugaring lets D8
    // dex it for this app's minSdk 24 (manifest tools:overrideLibrary only silences the merger,
    // not the dexer).
    isCoreLibraryDesugaringEnabled = true
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // GitHub runner 4 核：多 fork 并行跑测试（含 Robolectric），CI 墙钟时间约降 4 倍。
      // Robolectric 跨 fork 安全（各自独立 JVM + 共享 android-all 缓存）。
      all { test ->
        test.maxParallelForks = (Runtime.getRuntime().availableProcessors()).coerceIn(1, 8)
        // 默认 512MB 堆对“全 App 导航 + Compose + ViewModel + Room”的重型测试偏小，
        // 提到 1g（4 fork × 1g + daemon 4g，16GB runner 仍宽裕）。
        test.maxHeapSize = "1g"
        // 测试 JVM 崩溃（未捕获异常）时 Gradle 默认不转发 stderr，崩溃原因会整段丢失；
        // 打开标准流转发与完整异常格式，保证堆栈进入 CI 日志。
        test.testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
      }
    }
  }
  sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
  lint {
    // CI 保存 XML 供诊断，省去未使用的 HTML 报告；不改变测试源码的检查范围。
    htmlReport = false
    xmlReport = true
    textReport = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
}

// Built-in Kotlin registers `kotlin` on the Project (AGP 9's android extension has no
// `kotlin` member). Keep jvmTarget aligned with compileOptions (Java 17).
kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  }
}

dependencies {
  implementation(libs.commonmark)
  implementation(libs.commonmark.tables)
  implementation(libs.commonmark.strikethrough)
  implementation(project(":core:domain"))
  implementation(project(":core:engine"))
  implementation(project(":core:agent"))
  implementation(project(":core:storage"))
  implementation(project(":core:formats"))
  implementation(project(":core:workspace"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.moshi.kotlin)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  implementation(libs.koog.agents) {
    // Koog's transitive jackson-module-kotlin 2.21.3 references API 26+ APIs that D8 refuses
    // to dex for minSdk 24 (core library desugaring does not cover them). Jackson is only used
    // by Koog's snapshot/persistence features, which this app does not use (persistence is
    // Room-backed). If a future Koog feature needs it, revisit: either vendor a minSdk-24
    // compatible jackson or raise minSdk.
    exclude(group = "com.fasterxml.jackson.core")
    exclude(group = "com.fasterxml.jackson.module")
    exclude(group = "com.fasterxml.jackson.databind")
    exclude(group = "com.fasterxml.jackson.annotation")
  }
  implementation(libs.jsoup)
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  // ML Kit GenAI requires Android API 26+; see tools:overrideLibrary in AndroidManifest.xml.
  // All call sites must guard Build.VERSION.SDK_INT >= 26 and feature availability.
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mlkit.genai.summarization)
  implementation(libs.mlkit.genai.proofreading)
}
