package sqlike.frm.driver;

import java.util.Optional;

/** Outcome of one scenario: expected vs actual data and PASS/FAIL (FR-09). */
public record ScenarioResult(
        String name,
        boolean passed,
        String expected,
        String actual,
        Optional<String> message) {
}
