package dev.flags.server.flag;

import dev.flags.core.FlagDefinition;
import dev.flags.core.FlagEvaluator;
import dev.flags.core.Rule;
import dev.flags.server.audit.AuditAction;
import dev.flags.server.audit.AuditService;
import dev.flags.server.flag.FlagDtos.ConfigState;
import dev.flags.server.flag.FlagDtos.ConfigView;
import dev.flags.server.flag.FlagDtos.CreateFlagRequest;
import dev.flags.server.flag.FlagDtos.FlagView;
import dev.flags.server.flag.FlagDtos.PreviewRequest;
import dev.flags.server.flag.FlagDtos.PreviewResponse;
import dev.flags.server.flag.FlagDtos.UpdateConfigRequest;
import dev.flags.server.flag.FlagDtos.UpdateFlagRequest;
import dev.flags.server.web.ApiException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlagService {

    private final FlagRepository flags;
    private final FlagConfigRepository configs;
    private final EnvironmentRepository environments;
    private final EnvironmentChanges changes;
    private final AuditService audit;

    FlagService(
            FlagRepository flags,
            FlagConfigRepository configs,
            EnvironmentRepository environments,
            EnvironmentChanges changes,
            AuditService audit) {
        this.flags = flags;
        this.configs = configs;
        this.environments = environments;
        this.changes = changes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('VIEWER')")
    public List<FlagView> list() {
        Map<Flag, List<FlagConfig>> byFlag = new LinkedHashMap<>();
        for (FlagConfig config : configs.findAllWithFlagAndEnvironment()) {
            byFlag.computeIfAbsent(config.getFlag(), flag -> new java.util.ArrayList<>()).add(config);
        }
        return byFlag.entrySet().stream()
                .map(entry -> FlagView.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('VIEWER')")
    public FlagView get(String key) {
        List<FlagConfig> found = configs.findByFlagKey(key);
        if (found.isEmpty()) {
            throw noSuchFlag(key);
        }
        return FlagView.of(found.getFirst().getFlag(), found);
    }

    /** A new flag starts switched off everywhere, so creating it changes nothing for anyone. */
    @Transactional
    @PreAuthorize("hasRole('EDITOR')")
    public FlagView create(CreateFlagRequest request) {
        Flag flag;
        try {
            flag = flags.saveAndFlush(new Flag(request.key(), request.name().trim(), text(request.description())));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("A flag with the key '" + request.key() + "' already exists.");
        }
        List<Environment> all = environments.findAllByOrderBySortOrderAsc();
        List<FlagConfig> created = configs.saveAllAndFlush(
                all.stream().map(environment -> new FlagConfig(flag, environment)).toList());
        audit.record(AuditAction.FLAG_CREATED, flag.getKey(), null, null, Map.of("name", flag.getName()));
        changes.changed(all);
        return FlagView.of(flag, created);
    }

    /** Name and description are for people; SDKs never see them, so nothing is published. */
    @Transactional
    @PreAuthorize("hasRole('EDITOR')")
    public FlagView describe(String key, UpdateFlagRequest request) {
        Flag flag = flags.findByKey(key).orElseThrow(() -> noSuchFlag(key));
        Map<String, String> before = Map.of("name", flag.getName(), "description", flag.getDescription());
        flag.describe(request.name().trim(), text(request.description()));
        Map<String, String> after = Map.of("name", flag.getName(), "description", flag.getDescription());
        if (!before.equals(after)) {
            audit.record(AuditAction.FLAG_UPDATED, key, null, before, after);
        }
        return FlagView.of(flag, configs.findByFlagKey(key));
    }

    @Transactional
    @PreAuthorize("hasRole('EDITOR')")
    public void delete(String key) {
        Flag flag = flags.findByKey(key).orElseThrow(() -> noSuchFlag(key));
        // Its configurations go with it: ON DELETE CASCADE.
        flags.delete(flag);
        audit.record(AuditAction.FLAG_DELETED, key, null, Map.of("name", flag.getName()), null);
        changes.changed(environments.findAll());
    }

    @Transactional
    @PreAuthorize("hasRole('EDITOR')")
    public ConfigView configure(String flagKey, String environmentKey, UpdateConfigRequest request) {
        FlagConfig config = configs.findByFlagKeyAndEnvironmentKey(flagKey, environmentKey)
                .orElseThrow(() -> ApiException.notFound(
                        "There is no flag '" + flagKey + "' in an environment '" + environmentKey + "'."));
        if (config.getVersion() != request.version()) {
            throw ApiException.conflict(
                    "This flag was changed in " + environmentKey + " after you opened it. Reload to see the"
                            + " current settings, then make your change again.");
        }
        requireDistinctRuleIds(request.rules());
        if (config.matches(request.enabled(), request.rules(), request.fallthroughPercentage())) {
            return ConfigView.of(config);
        }
        ConfigState before = ConfigState.of(config);
        config.update(request.enabled(), request.rules(), request.fallthroughPercentage());
        // Flushing here raises a concurrent edit as an optimistic-lock failure inside this
        // method, and makes the new version available to return.
        configs.saveAndFlush(config);
        audit.record(AuditAction.FLAG_CONFIG_UPDATED, flagKey, environmentKey, before, ConfigState.of(config));
        changes.changed(config.getEnvironment());
        return ConfigView.of(config);
    }

    /**
     * Evaluates a configuration that has not been saved, with the flag's real salt, so
     * the console can show who a change would affect before anyone commits to it.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('VIEWER')")
    public PreviewResponse preview(String key, PreviewRequest request) {
        Flag flag = flags.findByKey(key).orElseThrow(() -> noSuchFlag(key));
        FlagDefinition draft = new FlagDefinition(
                flag.getKey(), flag.getSalt(), request.enabled(), request.rules(), request.fallthroughPercentage());
        return new PreviewResponse(request.contexts().stream()
                .map(context -> FlagEvaluator.evaluate(draft, context))
                .toList());
    }

    private static void requireDistinctRuleIds(List<Rule> rules) {
        Set<String> seen = new HashSet<>();
        for (Rule rule : rules) {
            if (!seen.add(rule.id())) {
                throw ApiException.badRequest("Two rules share the id '" + rule.id() + "'.");
            }
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static ApiException noSuchFlag(String key) {
        return ApiException.notFound("There is no flag with the key '" + key + "'.");
    }
}
