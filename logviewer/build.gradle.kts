plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

group = "com.pedronveloso"

version = "0.2.1"

android {
  namespace = "com.pedronveloso.logviewer"
  compileSdk = 37
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  lint { warningsAsErrors = true }
  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
      it.jvmArgs("-XX:TieredStopAtLevel=1")
    }
  }
}

dependencies {
  lintChecks(libs.composeLints)
  api(libs.timber)
  implementation(libs.coreKtx)
  api(libs.coroutinesAndroid)
  api(platform(libs.composeBom))
  api(libs.composeUi)
  implementation(libs.composeMaterial3)
  implementation(libs.composeIcons)
  implementation(libs.activityCompose)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidxTestCore)
  testImplementation(libs.coroutinesTest)
  androidTestImplementation(platform(libs.composeBom))
  androidTestImplementation(libs.composeUiTest)
  androidTestImplementation(libs.androidxJunit)
  androidTestImplementation(libs.androidxRunner)
  androidTestImplementation(libs.espressoCore)
  debugImplementation(libs.composeUiTestManifest)
}
