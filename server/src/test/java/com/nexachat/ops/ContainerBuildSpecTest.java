package com.nexachat.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerBuildSpecTest {

  @Test
  void dockerfileUsesNonRootUserPinnedBasesJvmAndLivenessProbe() throws Exception {
    Path dockerfile = Path.of("Dockerfile").toAbsolutePath();
    if (!Files.isRegularFile(dockerfile)) {
      dockerfile = Path.of("server", "Dockerfile").toAbsolutePath();
    }
    assertThat(dockerfile).exists();
    String text = Files.readString(dockerfile);

    assertThat(text).contains("USER nexachat");
    assertThat(text).contains("@sha256:");
    assertThat(text).contains("ExitOnOutOfMemoryError");
    assertThat(text).contains("MaxRAMPercentage");
    assertThat(text).contains("/actuator/health/liveness");
    assertThat(text).doesNotContain("JWT_SECRET=");
    assertThat(text).doesNotContain("DATABASE_PASSWORD=");
  }

  @Test
  void dockerignoreExcludesTestsAndSecrets() throws Exception {
    Path ignore = Path.of("server", ".dockerignore").toAbsolutePath();
    if (!Files.isRegularFile(ignore)) {
      ignore = Path.of(".dockerignore").toAbsolutePath();
    }
    assertThat(ignore).exists();
    String text = Files.readString(ignore);
    assertThat(text).contains("src/test/");
    assertThat(text).contains(".env");
  }
}
