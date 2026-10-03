package dev.flags.server.account;

import dev.flags.server.account.AccountDtos.LoginRequest;
import dev.flags.server.account.AccountDtos.SessionView;
import dev.flags.server.account.AccountDtos.SignupRequest;
import dev.flags.server.account.AccountDtos.TokenResponse;
import dev.flags.server.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class AuthController {

    private final AccountService accounts;

    AuthController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    TokenResponse signup(@Valid @RequestBody SignupRequest request) {
        return accounts.signup(request);
    }

    @PostMapping("/auth/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return accounts.login(request);
    }

    @GetMapping("/session")
    SessionView session(@AuthenticationPrincipal CurrentUser user) {
        return accounts.session(user);
    }
}
