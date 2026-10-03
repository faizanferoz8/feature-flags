package dev.flags.server.flag;

import dev.flags.server.flag.FlagDtos.ConfigView;
import dev.flags.server.flag.FlagDtos.CreateFlagRequest;
import dev.flags.server.flag.FlagDtos.FlagView;
import dev.flags.server.flag.FlagDtos.PreviewRequest;
import dev.flags.server.flag.FlagDtos.PreviewResponse;
import dev.flags.server.flag.FlagDtos.UpdateConfigRequest;
import dev.flags.server.flag.FlagDtos.UpdateFlagRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flags")
class FlagController {

    private final FlagService flags;

    FlagController(FlagService flags) {
        this.flags = flags;
    }

    @GetMapping
    List<FlagView> list() {
        return flags.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FlagView create(@Valid @RequestBody CreateFlagRequest request) {
        return flags.create(request);
    }

    @GetMapping("/{key}")
    FlagView get(@PathVariable String key) {
        return flags.get(key);
    }

    @PutMapping("/{key}")
    FlagView describe(@PathVariable String key, @Valid @RequestBody UpdateFlagRequest request) {
        return flags.describe(key, request);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key) {
        flags.delete(key);
    }

    @PutMapping("/{key}/environments/{environment}")
    ConfigView configure(
            @PathVariable String key,
            @PathVariable String environment,
            @Valid @RequestBody UpdateConfigRequest request) {
        return flags.configure(key, environment, request);
    }

    @PostMapping("/{key}/preview")
    PreviewResponse preview(@PathVariable String key, @Valid @RequestBody PreviewRequest request) {
        return flags.preview(key, request);
    }
}
