plugins {
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.spotless)
}

spotless {
  kotlinGradle {
    target("*.gradle.kts")
    ktfmt().googleStyle()
  }
}

subprojects {
  apply(plugin = "com.diffplug.spotless")
  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
      target("src/**/*.kt")
      ktfmt().googleStyle()
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktfmt().googleStyle()
    }
  }
}
