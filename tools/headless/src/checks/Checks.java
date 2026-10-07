package checks;

import java.util.List;
import java.util.Objects;

/** Minimal assertion bookkeeping: counts every check and prints only the failures. */
public final class Checks {
    private final String suite;
    private int checks;
    private int failures;

    public Checks(String suite) {
        this.suite = suite;
    }

    public void eq(String what, Object actual, Object expected) {
        checks++;
        if (!Objects.equals(actual, expected)) {
            fail(what, String.valueOf(actual), String.valueOf(expected));
        }
    }

    public void listEq(String what, List<?> actual, List<?> expected) {
        checks++;
        if (!Objects.equals(actual, expected)) {
            fail(what, String.valueOf(actual), String.valueOf(expected));
        }
    }

    public void isTrue(String what, boolean condition) {
        checks++;
        if (!condition) {
            fail(what, "false", "true");
        }
    }

    /** Relative comparison, used for costs that come out of two different summation orders. */
    public void closeTo(String what, double actual, double expected) {
        checks++;
        double scale = Math.max(1.0D, Math.max(Math.abs(actual), Math.abs(expected)));
        if (!Double.isFinite(actual) || !Double.isFinite(expected) || Math.abs(actual - expected) > 1e-9D * scale) {
            fail(what, Double.toString(actual), Double.toString(expected));
        }
    }

    /** Bit-exact comparison, used where two calls must produce literally the same number. */
    public void exact(String what, double actual, double expected) {
        checks++;
        if (Double.compare(actual, expected) != 0) {
            fail(what, Double.toString(actual), Double.toString(expected));
        }
    }

    private void fail(String what, String actual, String expected) {
        failures++;
        System.out.println("  FAIL [" + suite + "] " + what + " actual=" + actual + " expected=" + expected);
    }

    public int checks() {
        return checks;
    }

    public int failures() {
        return failures;
    }

    public String suite() {
        return suite;
    }
}
