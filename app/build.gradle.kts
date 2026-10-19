plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
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
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.breakyuna.noveltranslator"
    minSdk = 24
    targetSdk = 36
    versionCode = ciVersionCode ?: 7
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
  testOptions { unitTests { isIncludeAndroidResources = true } }
  sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
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
