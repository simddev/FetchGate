/**
 * Minimal assertion library for the FetchGate test suite.
 *
 * Throws AssertionError (caught by TestRunner) on failure.
 * Every method states what was expected vs. what was actually observed.
 */
public class Assert {

    public static void equal(Object expected, Object actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    public static void equal(long expected, long actual) {
        if (expected != actual)
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
    }

    public static void isTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void isFalse(boolean condition, String message) {
        if (condition) throw new AssertionError(message);
    }

    public static void isNull(Object value, String message) {
        if (value != null) throw new AssertionError(message + " (was: " + value + ")");
    }

    public static void notNull(Object value, String message) {
        if (value == null) throw new AssertionError(message);
    }

    public static void contains(String haystack, String needle) {
        if (!haystack.contains(needle))
            throw new AssertionError("<" + haystack + "> does not contain <" + needle + ">");
    }
}
