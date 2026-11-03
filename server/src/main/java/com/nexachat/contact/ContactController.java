package com.nexachat.contact;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.contact.dto.AddContactRequest;
import com.nexachat.contact.dto.ContactResponse;
import com.nexachat.contact.dto.UpdateAliasRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Contact endpoints (plan §10.2): list, add-by-phone, alias update, delete.
 * All require Bearer (SecurityConfig {@code anyRequest().authenticated()}).
 */
@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @GetMapping
    public List<ContactResponse> list(@AuthenticationPrincipal JwtPrincipal principal) {
        return contactService.list(requireUserId(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContactResponse add(@AuthenticationPrincipal JwtPrincipal principal,
                               @Valid @RequestBody AddContactRequest request) {
        return contactService.add(requireUserId(principal), request.phone());
    }

    @PatchMapping("/{userId}")
    public ContactResponse updateAlias(@AuthenticationPrincipal JwtPrincipal principal,
                                       @PathVariable("userId") UUID userId,
                                       @Valid @RequestBody UpdateAliasRequest request) {
        return contactService.updateAlias(requireUserId(principal), userId, request.aliasName());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal JwtPrincipal principal,
                       @PathVariable("userId") UUID userId) {
        contactService.delete(requireUserId(principal), userId);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
