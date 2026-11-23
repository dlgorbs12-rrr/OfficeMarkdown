import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConnectionTest {
    @TempDir Path temp;

    @Test void authenticationStatesDoNotImplyServiceAvailability(){
        var codex=DocumentAgent.Provider.CODEX;
        var claude=DocumentAgent.Provider.CLAUDE;
        assertEquals("로그인 필요",AgentConnection.authLabel(codex,1,"Not logged in"));
        assertEquals("로그인됨",AgentConnection.authLabel(codex,0,"Logged in using ChatGPT"));
        assertEquals("로그인 필요",AgentConnection.authLabel(claude,1,"{\"loggedIn\":false}"));
        assertEquals("로그인됨",AgentConnection.authLabel(claude,0,"{\"loggedIn\":true}"));
        assertTrue(AgentConnection.authLabel(claude,1,"Unsupported option").contains("확인 불가"));
    }

    @Test void findsClaudeInstalledByNpmWithoutShellPath() throws Exception {
        Path roaming=temp.resolve("Application Data");
        Path executable=roaming.resolve(Path.of("npm","node_modules","@anthropic-ai","claude-code","bin","claude.exe"));
        Files.createDirectories(executable.getParent());
        Files.createFile(executable);
        assertEquals(executable,DocumentAgent.Provider.CLAUDE.discoverExecutable(
                Map.of("APPDATA",roaming.toString())::get,temp.resolve("home").toString()));
    }

    @Test void findsNativeClaudeInWindowsProfileWhenJavaHomeDiffers() throws Exception {
        Path profile=temp.resolve("Windows Profile");
        Path executable=profile.resolve(Path.of(".local","bin","claude.exe"));
        Files.createDirectories(executable.getParent());
        Files.createFile(executable);
        assertEquals(executable,DocumentAgent.Provider.CLAUDE.discoverExecutable(
                Map.of("USERPROFILE",profile.toString())::get,temp.resolve("home").toString()));
    }
}
