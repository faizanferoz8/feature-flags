package dev.flags.server.apikey;

import dev.flags.server.apikey.ApiKeyService.CreateKeyRequest;
import dev.flags.server.apikey.ApiKeyService.CreatedKey;
import dev.flags.server.apikey.ApiKeyService.KeyView;
import dev.flags.server.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/keys")
class ApiKeyController {

    private final ApiKeyService keys;

    ApiKeyController(ApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    List<KeyView> list() {
        return keys.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedKey create(@Valid @RequestBody CreateKeyRequest request, @AuthenticationPrincipal CurrentUser user) {
        return keys.create(request, user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        keys.revoke(id);
    }
}
