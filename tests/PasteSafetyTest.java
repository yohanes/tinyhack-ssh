import com.tinyhack.ssh.util.PasteSafety;

public class PasteSafetyTest {
    private static void check(String text, boolean expected) {
        if (PasteSafety.requiresConfirmation(text) != expected) {
            throw new AssertionError("Unexpected paste policy for length " + text.length());
        }
    }

    public static void main(String[] args) {
        check("", false);
        check("hello\tworld", false);
        check("hello 😀 日本語", false);
        check("x".repeat(128), false);
        check("x".repeat(129), true);
        check("echo unsafe\r", true);
        check("echo unsafe\n", true);
        check("echo unsafe\r\n", true);
        check("\u001b[201~echo unsafe\r", true);
        check("abc\u0000def", true);
        check("abc\u007fdef", true);
        for (char c = 0; c < 32; c++) {
            check("x" + c, c != '\t');
        }
        System.out.println("Paste safety regression tests passed");
    }
}
