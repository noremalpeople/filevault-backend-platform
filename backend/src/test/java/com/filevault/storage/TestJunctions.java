package com.filevault.storage;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class TestJunctions {
    private TestJunctions() { }

    public static void create(Path alias, Path target) throws Exception {
        if (!FileSystems.getDefault().getSeparator().equals("\\")) {
            Files.createSymbolicLink(alias, target);
            return;
        }
        var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path $env:FILEVAULT_TEST_ALIAS "
                        + "-Target $env:FILEVAULT_TEST_TARGET | Out-Null").redirectErrorStream(true);
        command.environment().put("FILEVAULT_TEST_ALIAS", alias.toString());
        command.environment().put("FILEVAULT_TEST_TARGET", target.toString());
        Process process = command.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timeout creating the test junction.");
        }
        if (process.exitValue() != 0) throw new IOException("Could not create the test junction: "
                + new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
