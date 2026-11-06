plugins {
  `java-library`
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
  }
}
java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test>().configureEach {
  maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
}

dependencies {
  api(libs.re2j)
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}
