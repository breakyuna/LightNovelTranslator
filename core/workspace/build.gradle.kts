plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.breakyuna.noveltranslator.nativecore.workspace"
  compileSdk {
    version = release(libs.versions.androidCompileSdk.get().toInt()) {
      minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
    }
  }
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions {
    unitTests.all { test ->
      test.maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    }
  }
  lint {
    htmlReport = false
    xmlReport = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  }
}

dependencies {
  api(project(":core:domain"))
  implementation(libs.okhttp)
  implementation(libs.quickjs.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.core)
}
