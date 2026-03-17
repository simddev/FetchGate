/**
 * Test runner for FetchGate.
 *
 * Each test suite exposes a static run() method that registers individual
 * test cases via TestRunner.test(). The runner catches AssertionErrors and
 * Exceptions, prints PASS/FAIL per test, and exits with code 1 if any failed.
 *
 * Compile and run from the project root:
 *
 *   javac -d out src/*.java tests/*.java
 *   java  -cp out TestRunner
 */
public class TestRunner {

    private static int passed = 0;
    private static int failed = 0;

    public static void test(String name, ThrowingRunnable body) {
        try {
            body.run();
            System.out.println("  PASS  " + name);
            passed++;
        } catch (AssertionError | Exception e) {
            System.out.println("  FAIL  " + name);
            System.out.println("        " + e.getMessage());
            failed++;
        }
    }

    public static void suite(String name) {
        System.out.println("\n── " + name + " ──");
    }

    public static void main(String[] args) throws Exception {
        NativeMessagingTest.run();
        NativeHostTest.run();

        System.out.println("\n────────────────────────────────");
        System.out.printf("  %d passed, %d failed%n", passed, failed);
        System.out.println("────────────────────────────────");
        System.exit(failed > 0 ? 1 : 0);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
