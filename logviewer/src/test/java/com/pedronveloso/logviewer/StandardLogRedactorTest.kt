package com.pedronveloso.logviewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StandardLogRedactorTest {
  @Test
  fun `redacts urls credentials and user identifiers`() {
    val input =
      "https://example.com/private?code=topsecret " +
        "Authorization: Bearer abc.123 " +
        "access_token=secret123 " +
        "password=\"hunter2\" " +
        "user=person@example.com " +
        "id=123e4567-e89b-42d3-a456-426614174000"

    val result = StandardLogRedactor.redact(input)

    assertThat(result).contains("https://example.com/<redacted>")
    assertThat(result).contains("Authorization: <redacted>")
    assertThat(result).contains("access_token=<redacted>")
    assertThat(result).contains("password=<redacted>")
    assertThat(result).doesNotContain("topsecret")
    assertThat(result).doesNotContain("secret123")
    assertThat(result).doesNotContain("abc.123")
    assertThat(result).doesNotContain("hunter2")
    assertThat(result).doesNotContain("person@example.com")
    assertThat(result).doesNotContain("123e4567-e89b-42d3-a456-426614174000")
  }

  @Test
  fun `redacts standalone credentials and multiple urls`() {
    val result =
      StandardLogRedactor.redact(
        "Basic dXNlcjpwYXNz from https://old.example/path to https://new.example/?token=abc"
      )

    assertThat(result)
      .isEqualTo(
        "Basic <redacted> from https://old.example/<redacted> to https://new.example/<redacted>"
      )
  }

  @Test
  fun `keeps ordinary diagnostics readable`() {
    assertThat(StandardLogRedactor.redact("Pipeline completed in 42 ms"))
      .isEqualTo("Pipeline completed in 42 ms")
  }
}
