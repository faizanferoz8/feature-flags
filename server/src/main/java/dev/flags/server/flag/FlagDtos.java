package dev.flags.server.flag;

import dev.flags.core.Evaluation;
import dev.flags.core.EvaluationContext;
import dev.flags.core.Rule;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class FlagDtos {

    private FlagDtos() {}

    public record CreateFlagRequest(
            @NotBlank
                    @Pattern(
                            regexp = "^[a-z0-9][a-z0-9._-]{0,63}$",
                            message = "must be lower-case letters, digits, dots, dashes or underscores")
                    String key,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description) {}

    public record UpdateFlagRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {}

    /**
     * @param version the version the caller loaded and is editing; the update is refused
     *     if the stored one has moved on
     */
    public record UpdateConfigRequest(
            @NotNull Boolean enabled,
            @NotNull @Size(max = 50) List<Rule> rules,
            @Min(0) @Max(100) int fallthroughPercentage,
            @NotNull Long version) {}

    /** A configuration that may not be saved yet, and the contexts to try it on. */
    public record PreviewRequest(
            @NotNull Boolean enabled,
            @NotNull @Size(max = 50) List<Rule> rules,
            @Min(0) @Max(100) int fallthroughPercentage,
            @NotEmpty @Size(max = 1000) List<EvaluationContext> contexts) {}

    public record PreviewResponse(List<Evaluation> evaluations) {}

    public record ConfigView(
            String environment,
            String environmentName,
            boolean enabled,
            List<Rule> rules,
            int fallthroughPercentage,
            long version,
            Instant updatedAt) {

        static ConfigView of(FlagConfig config) {
            return new ConfigView(
                    config.getEnvironment().getKey(),
                    config.getEnvironment().getName(),
                    config.isEnabled(),
                    config.getRules(),
                    config.getFallthroughPercentage(),
                    config.getVersion(),
                    config.getUpdatedAt());
        }
    }

    public record FlagView(
            String key, String name, String description, Instant createdAt, List<ConfigView> environments) {

        static FlagView of(Flag flag, List<FlagConfig> configs) {
            return new FlagView(
                    flag.getKey(),
                    flag.getName(),
                    flag.getDescription(),
                    flag.getCreatedAt(),
                    configs.stream().map(ConfigView::of).toList());
        }
    }

    /** What the audit log keeps of a configuration, before and after a change. */
    record ConfigState(boolean enabled, List<Rule> rules, int fallthroughPercentage) {

        static ConfigState of(FlagConfig config) {
            return new ConfigState(config.isEnabled(), config.getRules(), config.getFallthroughPercentage());
        }
    }
}
