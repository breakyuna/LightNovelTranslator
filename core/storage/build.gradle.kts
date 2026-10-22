plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.breakyuna.noveltranslator.nativecore.storage"
  compileSdk {
    version = release(libs.versions.androidCompileSdk.get().toInt()) {
      minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
    }
  }
  defaultConfig {
    minSdk = libs.versions.androidMinSdk.get().toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
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

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.incremental", "true")
}

dependencies {
  api(project(":core:domain"))
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  "ksp"(libs.androidx.room.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation(project(":core:engine"))
}
