plugins { alias(libs.plugins.android.test) }

android {
  namespace = "com.pedronveloso.logviewer.r8test"
  compileSdk = 37
  defaultConfig {
    minSdk = 26
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  targetProjectPath = ":sample"
  experimentalProperties["android.experimental.self-instrumenting"] = true
  buildTypes {
    create("r8") {
      isDebuggable = true
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

androidComponents { beforeVariants { it.enable = it.buildType == "r8" } }

dependencies {
  implementation(libs.androidxJunit)
  implementation(libs.androidxRunner)
  implementation(libs.uiAutomator)
}
