package com.nexachat.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerComposeSpecTest {

  private static final String POSTGRES_16_ALPINE_AMD64_DIGEST =
      "ac838714b7d794185a866e5a6b479b954e7d8a070568aa0acac6fe07c3070bf5";

  private static final String APP_TMPFS_ENTRY = "/tmp:uid=10001,gid=10001,mode=1777";

  @Test
  void composePinsPostgresHardensAppServiceAndProbesLiveness() throws Exception {
    Path compose = resolveComposeFile();
    assertThat(compose).exists();

    @SuppressWarnings("unchecked")
    Map<String, Object> root = new Yaml().load(Files.readString(compose));
    Map<String, Object> services = assertMap(root, "services");
    Map<String, Object> db = assertMap(services, "db");
    Map<String, Object> app = assertMap(services, "app");

    String dbImage = String.valueOf(db.get("image"));
    assertThat(dbImage)
        .isEqualTo("postgres:16-alpine@sha256:" + POSTGRES_16_ALPINE_AMD64_DIGEST)
        .matches("postgres:16-alpine@sha256:[a-f0-9]{64}");

    assertThat(app.get("read_only")).isEqualTo(true);

    List<?> tmpfs = assertList(app, "tmpfs");
    assertThat(tmpfs.stream().map(String::valueOf).toList()).contains(APP_TMPFS_ENTRY);

    List<?> capDrop = assertList(app, "cap_drop");
    assertThat(capDrop.stream().map(String::valueOf).toList()).contains("ALL");

    List<?> securityOpt = assertList(app, "security_opt");
    assertThat(securityOpt.stream().map(String::valueOf).toList())
        .contains("no-new-privileges:true");

    String grace = String.valueOf(app.get("stop_grace_period"));
    assertThat(parseComposeDuration(grace))
        .isGreaterThanOrEqualTo(Duration.ofSeconds(20));

    Map<String, Object> healthcheck = assertMap(app, "healthcheck");
    List<?> probe = assertList(healthcheck, "test");
    assertThat(probe).anySatisfy(part ->
        assertThat(String.valueOf(part)).contains("/actuator/health/liveness"));

    Map<String, Object> deploy = assertMap(app, "deploy");
    Map<String, Object> resources = assertMap(deploy, "resources");
    Map<String, Object> limits = assertMap(resources, "limits");
    assertThat(limits).containsKeys("memory", "cpus");
  }

  private static Path resolveComposeFile() {
    Path fromServerModule = Path.of("..", "docker-compose.yml").toAbsolutePath().normalize();
    if (Files.isRegularFile(fromServerModule)) {
      return fromServerModule;
    }
    Path fromRepoRoot = Path.of("docker-compose.yml").toAbsolutePath().normalize();
    return fromRepoRoot;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> assertMap(Map<String, Object> parent, String key) {
    assertThat(parent).containsKey(key);
    Object value = parent.get(key);
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<?> assertList(Map<String, Object> parent, String key) {
    assertThat(parent).containsKey(key);
    Object value = parent.get(key);
    assertThat(value).isInstanceOf(List.class);
    return (List<?>) value;
  }

  private static Duration parseComposeDuration(String raw) {
    if (raw == null || raw.isBlank()) {
      return Duration.ZERO;
    }
    String normalized = raw.trim();
    if (normalized.endsWith("s") && !normalized.endsWith("ms")) {
      return Duration.ofSeconds(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
    }
    if (normalized.endsWith("m")) {
      return Duration.ofMinutes(Long.parseLong(normalized.substring(0, normalized.length() - 1)));
    }
    throw new IllegalArgumentException("Unrecognized compose duration: " + raw);
  }
}
