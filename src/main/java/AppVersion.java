import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class AppVersion {
    private AppVersion() {}

    static String current() {
        try (var input = AppVersion.class.getResourceAsStream("/office-markdown.version")) {
            if (input == null) throw new IllegalStateException("앱 버전 정보가 없습니다.");
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip();
        } catch (IOException e) {
            throw new IllegalStateException("앱 버전을 읽을 수 없습니다.", e);
        }
    }

    // An administrative extraction has the MSI marker too. Check Windows Installer's registered location.
    static boolean installed() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || !System.getProperty("os.name").startsWith("Windows")) return false;
        Process probe = null;
        try {
            Path directory = Path.of(launcher).toRealPath().getParent();
            Path marker = directory.resolve("app/.package");
            if (!Files.isRegularFile(marker) || Files.size(marker) > 64 ||
                    !Files.readString(marker).strip().equals("OfficeMarkdown")) return false;
            String script = """
                    $ErrorActionPreference='Stop'
                    $msi=New-Object -ComObject WindowsInstaller.Installer
                    foreach ($product in $msi.RelatedProducts('{608AA614-7B10-4FAA-A185-542AF26F15E9}')) {
                        $location=$msi.ProductInfo($product,'InstallLocation')
                        if ($location -and ([IO.Path]::GetFullPath($location).TrimEnd([IO.Path]::DirectorySeparatorChar) -eq $env:OFFICEMD_RUNNING_DIR)) { exit 0 }
                    }
                    exit 1
                    """;
            var builder = new ProcessBuilder(systemProgram("WindowsPowerShell/v1.0/powershell.exe"),
                    "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
            builder.environment().put("OFFICEMD_RUNNING_DIR", directory.toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            probe = builder.start();
            return probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (probe != null && probe.isAlive()) probe.destroyForcibly();
        }
    }

    static void startInstaller(Path installer) throws IOException {
        Path path = installer.toAbsolutePath();
        Process install = new ProcessBuilder(systemProgram("msiexec.exe"), "/i", path.toString(), "/norestart").start();
        // The application exits to release its files. Keep the MSI available until Windows Installer exits.
        String cleanup = """
                $p=Get-Process -Id ([int]$env:OFFICEMD_INSTALL_PID) -ErrorAction SilentlyContinue
                if ($p) { $p.WaitForExit() }
                [IO.File]::Delete($env:OFFICEMD_INSTALL_MSI)
                [IO.Directory]::Delete([IO.Path]::GetDirectoryName($env:OFFICEMD_INSTALL_MSI),$false)
                """;
        try {
            var watcher = new ProcessBuilder(systemProgram("WindowsPowerShell/v1.0/powershell.exe"),
                    "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", cleanup);
            watcher.environment().put("OFFICEMD_INSTALL_PID", Long.toString(install.pid()));
            watcher.environment().put("OFFICEMD_INSTALL_MSI", path.toString());
            watcher.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | SecurityException ignored) {
            // Installation is already running. Leave its temporary copy for Windows to clean up.
        }
    }

    private static String systemProgram(String executable) throws IOException {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || !Path.of(systemRoot).isAbsolute()) throw new IOException("Windows 설치 경로를 확인할 수 없습니다.");
        return Path.of(systemRoot, "System32", executable).toString();
    }
}
