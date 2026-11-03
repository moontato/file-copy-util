package com.fileverifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs only after package: the child receives the original jar, never Maven's runtime classpath. */
class ThinJarIT {
    @TempDir Path temp;

    @Test
    void headlessViewAndLiveThemeSwitchWorkWithoutFlatLaf() throws Exception {
        Path jar = Path.of(System.getProperty("thinJar")).toRealPath();
        Path testClasses = Path.of(System.getProperty("testClasses")).toRealPath();
        String classpath = jar + File.pathSeparator + testClasses;
        String output = run(List.of(java(), "-Djava.awt.headless=true",
            "-Djava.util.prefs.userRoot=" + temp, "-cp", classpath,
            "com.fileverifier.ThinJarProbe", jar.toString()));
        assertTrue(output.contains("FlatLaf absent from thin classpath"), output);
        assertTrue(output.contains("light -> dark -> light, button restyled"), output);
    }

    @Test
    void thinJarCliHelpRunsWithoutDependencies() throws Exception {
        String output = run(List.of(java(), "-Djava.awt.headless=true", "-jar",
            Path.of(System.getProperty("thinJar")).toRealPath().toString(), "--cli", "--help"));
        assertTrue(output.contains("--source"), output);
    }

    @Test
    void populatedLayoutAndEnlargedFontsWorkInThePlatformFallbackWithoutFlatLaf() throws Exception {
        Path jar = Path.of(System.getProperty("thinJar")).toRealPath();
        Path testClasses = Path.of(System.getProperty("testClasses")).toRealPath();
        String classpath = jar + File.pathSeparator + testClasses;
        String output = run(List.of(java(), "-Djava.awt.headless=true",
            "-Djava.util.prefs.userRoot=" + temp, "-cp", classpath,
            "com.fileverifier.ThinJarLayoutProbe", jar.toString()));
        assertTrue(output.contains("FlatLaf absent from thin classpath"), output);
        assertTrue(output.contains("platform fallback at 13 point"), output);
        assertTrue(output.contains("platform fallback at 24 point"), output);
        assertTrue(output.contains("after theme switch"), output);
    }

    private String run(List<String> command) throws Exception {
        Path output = Files.createTempFile(temp, "child-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("Child timed out: " + command + "\n" + Files.readString(output));
        }
        String text = Files.readString(output);
        System.out.println("Child command: " + command + "\n" + text);
        assertEquals(0, process.exitValue(), text);
        return text;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
