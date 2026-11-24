package com.nexachat.admin;

import com.nexachat.call.CallRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** C5-07: storage health measures the configured storage root, not the process CWD. */
@ExtendWith(MockitoExtension.class)
class AdminSystemControllerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private CallRegistry callRegistry;
    @Mock
    private AlertScanQueue scanQueue;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storageStatusReportsTheUsableSpaceOfTheConfiguredStorageRoot() throws Exception {
        signInAsAdmin();
        stubUpstreamServiceChecks();
        Path root = Files.createTempDirectory("nexachat-storage-status");
        AdminSystemController controller = controller(root.toString());

        long expectedBytes = root.toFile().getUsableSpace();
        AdminSystemController.ServiceStatus storage = storageService(controller.health());

        assertThat(storage.status()).isEqualTo("UP");
        assertThat(storage.detail()).startsWith("usable=").endsWith("MiB");
        long reportedBytes = Long.parseLong(
                storage.detail().substring("usable=".length(), storage.detail().length() - "MiB".length()))
                * 1024 * 1024;
        assertThat(Math.abs(reportedBytes - expectedBytes))
                .as("the reported usable space must come from the configured root")
                .isLessThan(1024 * 1024);
    }

    @Test
    void storageStatusIsDownWhenTheConfiguredRootHasNoUsableSpace() throws Exception {
        signInAsAdmin();
        stubUpstreamServiceChecks();
        // A path with no volume behind it reads as zero usable space; the old
        // code would have measured the process directory instead and said UP.
        Path missingRoot = Path.of(System.getProperty("java.io.tmpdir"),
                "nexachat-storage-status-missing-" + UUID.randomUUID());
        AdminSystemController controller = controller(missingRoot.toString());

        AdminSystemController.ServiceStatus storage = storageService(controller.health());

        assertThat(storage.status()).isEqualTo("DOWN");
        assertThat(storage.detail()).isEqualTo("usable=0MiB");
    }

    private AdminSystemController controller(String storageRoot) {
        return new AdminSystemController(jdbcTemplate, callRegistry, scanQueue, storageRoot);
    }

    private AdminSystemController.ServiceStatus storageService(AdminSystemController.SystemHealth health) {
        return health.services().stream()
                .filter(service -> "storage".equals(service.name()))
                .findFirst()
                .orElseThrow();
    }

    private void stubUpstreamServiceChecks() {
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForMap(anyString()))
                .thenReturn(Map.of("applied", 195L, "latest", "V62"));
    }

    private void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new com.nexachat.common.dto.JwtPrincipal(UUID.randomUUID()), null,
                List.of(() -> "ROLE_ADMIN")));
    }
}
