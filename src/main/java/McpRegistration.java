import com.google.gson.JsonParser;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

final class McpRegistration {
    static void open(JFrame owner, JButton setup) {
        var providers = new JComboBox<>(DocumentAgent.Provider.values());
        providers.setSelectedItem(AgentConnection.preferred());
        var name = new JTextField("office");
        var form = LibraryStyle.panel(new GridLayout(0, 1, 0, 4), 4);
        form.add(new JLabel("연결할 외부 에이전트"));
        form.add(providers);
        form.add(new JLabel("MCP 서버 이름"));
        form.add(name);
        form.add(new JLabel("등록한 에이전트는 색인된 전체 문서를 검색할 수 있습니다."));
        if (JOptionPane.showOptionDialog(owner, form, "검색 MCP 설정", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, new String[]{"등록", "취소"}, "등록") != 0) return;
        var provider = (DocumentAgent.Provider) providers.getSelectedItem();
        String serverName = name.getText().strip();
        setup.setEnabled(false);
        setup.setText("등록 중…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                register(provider, serverName);
                return null;
            }
            @Override protected void done() {
                setup.setEnabled(true);
                setup.setText("MCP 설정");
                try {
                    get();
                    JOptionPane.showMessageDialog(owner, provider + "에 " + serverName + " 서버를 등록했습니다. 새 세션에서 사용하세요.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(owner, e.getCause().getMessage(), "MCP 설정", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    static void register(DocumentAgent.Provider provider, String name) throws Exception {
        if (!name.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("서버 이름은 영문, 숫자, 밑줄, 하이픈으로 1~64자를 입력하세요.");
        if (name.startsWith("-")) throw new IOException("서버 이름은 하이픈으로 시작할 수 없습니다.");
        String exe = provider.executable().toString();
        // Codex replaces an existing entry on add. The other two CLIs reject duplicates.
        if (provider == DocumentAgent.Provider.CODEX) {
            var servers = JsonParser.parseString(run(List.of(exe,"mcp","list","--json"))).getAsJsonArray();
            for (var server : servers) {
                if (server.getAsJsonObject().get("name").getAsString().equals(name))
                    throw new IOException("같은 이름의 서버가 있습니다. 다른 이름을 입력하세요.");
            }
        }
        var command = new ArrayList<>(List.of(exe, "mcp", "add"));
        if (provider == DocumentAgent.Provider.CLAUDE) command.addAll(List.of("--scope", "user"));
        command.addAll(List.of(name, "--"));
        command.addAll(serverCommand());
        run(command);
    }

    private static List<String> serverCommand() throws IOException {
        String runner = System.getenv("OFFICEMD_MCP_RUNNER");
        String launcher = System.getProperty("jpackage.app-path");
        if (runner == null && launcher != null) {
            Path server = Path.of(launcher).resolveSibling("OfficeIndexMcp.exe");
            if (Files.isRegularFile(server)) return List.of(server.toString());
            throw new IOException("설치 폴더에 OfficeIndexMcp.exe가 없습니다.");
        }
        Path script = Path.of(runner == null ? "run-index-mcp.ps1" : runner).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script) || !Files.isRegularFile(script.resolveSibling("local/current.txt")))
            throw new IOException("로컬 MCP 실행본이 없습니다. build-local.ps1로 빌드한 뒤 다시 시도하세요.");
        return List.of(Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
                "-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",script.toString());
    }

    private static String run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
            try {
                process.getOutputStream().close();
                if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("MCP 설정 시간이 초과되었습니다.");
                if (process.exitValue() != 0)
                    throw new IOException("CLI가 MCP 설정을 완료하지 못했습니다. 서버 이름 중복과 CLI 버전을 확인하세요.");
                return output.get();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }
}
