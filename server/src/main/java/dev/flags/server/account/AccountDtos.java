package dev.flags.server.account;

import dev.flags.server.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {}

    public record SignupRequest(
            @NotBlank @Size(max = 100) String organization,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record AddMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
            @NotNull Role role) {}

    public record ChangeRoleRequest(@NotNull Role role) {}

    public record UserView(UUID id, String email, Role role, Instant createdAt) {
        static UserView of(User user) {
            return new UserView(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
        }
    }

    public record EnvironmentView(String key, String name) {}

    public record SessionView(UserView user, String organization, List<EnvironmentView> environments) {}

    public record TokenResponse(String token, SessionView session) {}
}
