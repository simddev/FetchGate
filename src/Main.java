import java.io.OutputStream;

public class Main {

    public static void main(String[] args) throws Exception {
        // Capture the real stdin/stdout FIRST — these are the Native Messaging channels.
        // Firefox communicates with this process exclusively through these two streams.
        OutputStream nativeOut = System.out;

        // Redirect System.out → System.err so any accidental println() calls
        // elsewhere in the code cannot corrupt the binary Native Messaging stream.
        System.setOut(System.err);

        new NativeHost(System.in, nativeOut).start();
    }
}
