import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DocumentAgentProcessTest {
    @TempDir Path temp;

    @Test @Timeout(10) @EnabledOnOs(OS.WINDOWS)
    void preservesReplyWhileDescendantRetainsInput() throws Exception {
        Path work=Files.createDirectory(temp.resolve("question"));
        Path pid=temp.resolve("child.pid");
        try{
            String reply=DocumentAgent.runCommand(new ProcessBuilder(command("reply",pid)).directory(work.toFile()),
                    DocumentAgent.Provider.CLAUDE,DocumentAgent.Effort.DEFAULT,"synthetic request",()->{},TimeUnit.SECONDS.toNanos(5));
            assertEquals("OFFICE_CONNECTED",reply);
            assertTrue(Files.isRegularFile(work.resolve("request")));
            assertFalse(Files.exists(work.resolve("reply")));
        }finally{
            if(Files.isRegularFile(pid)){
                var child=ProcessHandle.of(Long.parseLong(Files.readString(pid)));
                if(child.isPresent() && child.get().destroyForcibly())child.get().onExit().get(3,TimeUnit.SECONDS);
            }
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(Files.exists(work) && System.nanoTime()-deadline<0)Thread.sleep(20);
        assertFalse(Files.exists(work));
    }

    private static List<String> command(String mode,Path pid) throws Exception {
        Path java=Path.of(System.getProperty("java.home"),"bin","java.exe");
        Path classes=Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(java.toString(),"-cp",classes.toString(),Fixture.class.getName(),mode,pid.toString());
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("hold")){
                Thread.sleep(TimeUnit.SECONDS.toMillis(15));
            }else{
                Path pid=Path.of(args[1]);
                Process child=new ProcessBuilder(command("hold",pid))
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(pid,Long.toString(child.pid()));
                System.out.println("OFFICE_CONNECTED");
            }
        }
    }
}
