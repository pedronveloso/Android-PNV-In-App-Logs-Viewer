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
  lint { warningsAsErrors = true }
}

dependencies {
  lintChecks(libs.composeLints)
  implementation(platform(libs.composeBom))
  implementation(libs.composeUi)
  implementation(libs.composeMaterial3)
  implementation(libs.activityCompose)
  debugImplementation(project(":logviewer"))
}
