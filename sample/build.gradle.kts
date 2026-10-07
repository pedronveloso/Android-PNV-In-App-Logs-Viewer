plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.pedronveloso.logviewer.sample"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.pedronveloso.logviewer.sample"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  buildTypes {
    create("r8") {
      initWith(getByName("release"))
      applicationIdSuffix = ".r8"
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
      optimization { enable = true }
    }
  }
  // Exercise the real sample through R8 without adding the viewer to the release variant.
  sourceSets.getByName("r8").kotlin.directories.add("src/debug/java")
  // AGP exposes only a debug unit-test task here; keep its viewer-dependent test in testDebug.
  sourceSets.getByName("test").java.directories.add("src/testDebug/java")
  lint { warningsAsErrors = true }
}

dependencies {
  lintChecks(libs.composeLints)
  implementation(platform(libs.composeBom))
  implementation(libs.composeUi)
  implementation(libs.composeUiToolingPreview)
  implementation(libs.composeMaterial3)
  implementation(libs.activityCompose)
  implementation(libs.coreKtx)
  debugImplementation(project(":logviewer"))
  "r8Implementation"(project(":logviewer"))
  debugImplementation(libs.composeUiTooling)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
