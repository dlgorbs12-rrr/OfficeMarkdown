import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AgentConnectionTest {
    @Test void authenticationStatesDoNotImplyServiceAvailability(){
        var codex=DocumentAgent.Provider.CODEX;
        var claude=DocumentAgent.Provider.CLAUDE;
        assertEquals("로그인 필요",AgentConnection.authLabel(codex,1,"Not logged in"));
        assertEquals("로그인됨",AgentConnection.authLabel(codex,0,"Logged in using ChatGPT"));
        assertEquals("로그인 필요",AgentConnection.authLabel(claude,1,"{\"loggedIn\":false}"));
        assertEquals("로그인됨",AgentConnection.authLabel(claude,0,"{\"loggedIn\":true}"));
        assertTrue(AgentConnection.authLabel(claude,1,"Unsupported option").contains("확인 불가"));
    }
}
