package dev.flags.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlagEvaluatorTest {

    private static final EvaluationContext PK_PRO = EvaluationContext.of(
            "user-42", Map.of("country", "PK", "plan", "pro", "email", "dev@example.com", "seats", "25"));

    private static FlagDefinition flag(boolean enabled, int fallthrough, Rule... rules) {
        return new FlagDefinition("new-checkout", "salt", enabled, List.of(rules), fallthrough);
    }

    private static Rule rule(String id, int percentage, Condition... conditions) {
        return new Rule(id, "", List.of(conditions), percentage);
    }

    private static Condition when(String attribute, Operator operator, String... values) {
        return new Condition(attribute, operator, List.of(values));
    }

    @Test
    void aDisabledFlagIsOffWhateverItsRulesSay() {
        FlagDefinition flag = flag(false, 100, rule("everyone-in-pk", 100, when("country", Operator.IN, "PK")));

        Evaluation result = FlagEvaluator.evaluate(flag, PK_PRO);

        assertThat(result.value()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.OFF);
    }

    @Test
    void theFirstMatchingRuleWins() {
        FlagDefinition flag = flag(
                true,
                0,
                rule("block-free", 0, when("plan", Operator.IN, "free")),
                rule("pk-on", 100, when("country", Operator.IN, "PK", "AE")),
                rule("pro-off", 0, when("plan", Operator.IN, "pro")));

        Evaluation result = FlagEvaluator.evaluate(flag, PK_PRO);

        assertThat(result.value()).isTrue();
        assertThat(result.reason()).isEqualTo(Reason.RULE_MATCH);
        assertThat(result.ruleId()).isEqualTo("pk-on");
    }

    @Test
    void aRuleNeedsAllOfItsConditions() {
        Rule proInGermany =
                rule("pro-in-de", 100, when("plan", Operator.IN, "pro"), when("country", Operator.IN, "DE"));

        Evaluation result = FlagEvaluator.evaluate(flag(true, 0, proInGermany), PK_PRO);

        assertThat(result.value()).isFalse();
        assertThat(result.reason()).isEqualTo(Reason.FALLTHROUGH);
    }

    @Test
    void aRuleCanSwitchTheFlagOffAheadOfABroadRollout() {
        FlagDefinition flag = flag(true, 100, rule("not-for-pro", 0, when("plan", Operator.IN, "pro")));

        assertThat(FlagEvaluator.evaluate(flag, PK_PRO).value()).isFalse();
        assertThat(FlagEvaluator.evaluate(flag, EvaluationContext.of("u", Map.of("plan", "free"))).value())
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "country, IN,           PK,           true",
        "country, IN,           US,           false",
        "country, NOT_IN,       US,           true",
        "country, NOT_IN,       PK,           false",
        "email,   ENDS_WITH,    @example.com, true",
        "email,   ENDS_WITH,    @other.com,   false",
        "email,   STARTS_WITH,  dev@,         true",
        "email,   CONTAINS,     example,      true",
        "email,   CONTAINS,     EXAMPLE,      false",
        "seats,   GREATER_THAN, 10,           true",
        "seats,   GREATER_THAN, 25,           false",
        "seats,   LESS_THAN,    100,          true",
        "seats,   LESS_THAN,    9,            false",
        "plan,    GREATER_THAN, 1,            false",
        "seats,   GREATER_THAN, ten,          false",
        "key,     IN,           user-42,      true",
        "key,     STARTS_WITH,  admin-,       false",
    })
    void operators(String attribute, Operator operator, String value, boolean expected) {
        FlagDefinition flag = flag(true, 0, rule("r", 100, when(attribute, operator, value)));

        assertThat(FlagEvaluator.evaluate(flag, PK_PRO).value()).isEqualTo(expected);
    }

    @Test
    void numbersCompareAsNumbersNotAsText() {
        FlagDefinition flag = flag(true, 0, rule("r", 100, when("seats", Operator.GREATER_THAN, "9")));

        // As text, "25" sorts before "9".
        assertThat(FlagEvaluator.evaluate(flag, PK_PRO).value()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"IN", "NOT_IN", "CONTAINS", "STARTS_WITH", "ENDS_WITH", "GREATER_THAN", "LESS_THAN"})
    void anAttributeTheContextLacksNeverMatches(Operator operator) {
        FlagDefinition flag = flag(true, 0, rule("r", 100, when("region", operator, "1")));

        Evaluation result = FlagEvaluator.evaluate(flag, PK_PRO);

        assertThat(result.reason()).isEqualTo(Reason.FALLTHROUGH);
    }

    @Test
    void aPercentageRolloutReachesAboutThatShareAndReportsTheBucket() {
        FlagDefinition flag = flag(true, 30);

        long on = IntStream.range(0, 20_000)
                .filter(i -> FlagEvaluator.evaluate(flag, EvaluationContext.of("user-" + i)).value())
                .count();
        Evaluation one = FlagEvaluator.evaluate(flag, PK_PRO);

        assertThat(on).isBetween(5_700L, 6_300L);
        assertThat(one.bucket()).isEqualTo(Bucketing.bucket("salt", "user-42"));
        assertThat(one.value()).isEqualTo(one.bucket() < 3_000);
    }

    @Test
    void aRuleCanRollOutToAShareOfItsOwnAudience() {
        FlagDefinition flag = flag(true, 0, rule("half-of-pk", 50, when("country", Operator.IN, "PK")));

        long on = IntStream.range(0, 10_000)
                .mapToObj(i -> EvaluationContext.of("user-" + i, Map.of("country", i % 2 == 0 ? "PK" : "US")))
                .filter(context -> FlagEvaluator.evaluate(flag, context).value())
                .count();

        // Half the users are in PK, and half of those are in the rollout.
        assertThat(on).isBetween(2_300L, 2_700L);
    }

    @Test
    void invalidDefinitionsAreRejectedWhenBuiltNotWhenEvaluated() {
        assertThatIllegalArgumentException().isThrownBy(() -> flag(true, 101));
        assertThatIllegalArgumentException().isThrownBy(() -> flag(true, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule("r", "", List.of(), 100));
        assertThatIllegalArgumentException().isThrownBy(() -> new Condition("plan", Operator.IN, List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Condition(" ", Operator.IN, List.of("x")));
        assertThatIllegalArgumentException().isThrownBy(() -> EvaluationContext.of(" "));
    }
}
