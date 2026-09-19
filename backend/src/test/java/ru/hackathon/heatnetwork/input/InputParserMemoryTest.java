package ru.hackathon.heatnetwork.input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InputParserMemoryTest {
    @TempDir Path temporary;

    @Test void parsesInputLargerThanHeapAndRemovesItsDiskStore() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        String classpath = System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path log = temporary.resolve("probe.log");
        Process child = new ProcessBuilder(java.toString(),"-Xmx64m","-cp",classpath,
                InputParserScaleProbe.class.getName(),temporary.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(180,TimeUnit.SECONDS),"Scale probe timed out");
            assertEquals(0,child.exitValue(),()-> {
                try { return Files.readString(log); } catch(Exception e) { return e.toString(); }
            });
            System.out.println(Files.readString(log));
        } finally {
            if(child.isAlive()) { child.destroyForcibly(); child.waitFor(10,TimeUnit.SECONDS); }
        }
    }
}
