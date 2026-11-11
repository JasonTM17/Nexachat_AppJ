package com.nexachat.recovery;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private final AccountRecoveryService recoveryService;

    public PasswordResetController(AccountRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> request(@RequestBody(required = false) RequestRecoveryCode request) {
        recoveryService.requestPasswordReset(request == null ? null : request.phone());
        return Map.of();
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@RequestBody(required = false) ConfirmPasswordReset request) {
        recoveryService.confirmPasswordReset(
                request == null ? null : request.phone(),
                request == null ? null : request.code(),
                request == null ? null : request.newPassword());
    }
}
