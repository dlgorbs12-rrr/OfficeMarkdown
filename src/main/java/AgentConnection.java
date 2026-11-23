import com.google.gson.JsonParser;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

final class AgentConnection {
    private static final Preferences PREF=Preferences.userRoot().node("OfficeMarkdown/agents");
    static DocumentAgent.Provider preferred(){
        try{return DocumentAgent.Provider.valueOf(PREF.get("default","CODEX"));}
        catch(IllegalArgumentException e){return DocumentAgent.Provider.CODEX;}
    }
    static void prefer(DocumentAgent.Provider p){PREF.put("default",p.name());}
    static boolean passedTest(DocumentAgent.Provider p,Path executable){
        return executable.normalize().toString().equals(PREF.get("tested."+p.name(),""));
    }
    static void testResult(DocumentAgent.Provider p,Path executable,boolean success){
        String key="tested."+p.name();
        if(success)PREF.put(key,executable.normalize().toString());
        else if(passedTest(p,executable))PREF.remove(key);
    }
    static DocumentAgent.Effort effort(DocumentAgent.Provider provider){
        String stored=PREF.get("effort."+provider.name(),"DEFAULT");
        for(var choice:DocumentAgent.Effort.choices(provider))if(choice.name().equals(stored))return choice;
        return DocumentAgent.Effort.DEFAULT;
    }
    static void effort(DocumentAgent.Provider provider,DocumentAgent.Effort choice){
        PREF.put("effort."+provider.name(),choice.name());
    }
    static String model(DocumentAgent.Provider p){return PREF.get("model."+p.name(),"");}
    static void model(DocumentAgent.Provider p,String id){PREF.put("model."+p.name(),id);}
    record Model(String id,String name,String description) {}
    static List<Model> models(DocumentAgent.Provider p){
        var options=new LinkedHashMap<String,Model>();
        if(p==DocumentAgent.Provider.CODEX){
            String home=System.getenv("CODEX_HOME");
            Path folder=home==null || home.isBlank()?Path.of(System.getProperty("user.home"),".codex"):Path.of(home);
            try(var reader=Files.newBufferedReader(folder.resolve("models_cache.json"))){
                for(var entry:JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("models")){
                    var m=entry.getAsJsonObject();
                    if(!"list".equals(m.get("visibility").getAsString()))continue;
                    String id=m.get("slug").getAsString();
                    String name=m.has("display_name") && m.get("display_name").isJsonPrimitive()
                            ? m.get("display_name").getAsString() : id;
                    String description=m.has("description") && m.get("description").isJsonPrimitive()
                            ? m.get("description").getAsString() : "";
                    options.put(id,new Model(id,name,description));
                }
            }catch(IOException | RuntimeException ignored){/* Custom IDs work without a catalog. */}
        }else if(p==DocumentAgent.Provider.CLAUDE){
            for(String id:List.of("sonnet","opus","haiku"))options.put(id,new Model(id,id,""));
        }
        String saved=model(p);
        if(!saved.isEmpty())options.putIfAbsent(saved,new Model(saved,saved,""));
        return List.copyOf(options.values());
    }
    static String override(DocumentAgent.Provider p){return PREF.get("exe."+p.name(),"");}
    static void path(DocumentAgent.Provider p,Path file) throws IOException {
        if(file==null){PREF.remove("exe."+p.name());return;}
        if(!file.isAbsolute() || !Files.isRegularFile(file) || !file.getFileName().toString().equalsIgnoreCase(p.exe+".exe"))
            throw new IOException(p.exe+".exe 실행 파일을 선택하세요.");
        PREF.put("exe."+p.name(),file.normalize().toString());
    }
    static URI guide(DocumentAgent.Provider p){
        return URI.create(switch(p){
            case CODEX -> "https://developers.openai.com/codex/cli/";
            case CLAUDE -> "https://code.claude.com/docs/en/setup";
            case COPILOT -> "https://docs.github.com/en/copilot/how-tos/set-up/install-copilot-cli";
        });
    }
    static String loginArgs(DocumentAgent.Provider p){return p==DocumentAgent.Provider.CLAUDE?"auth login":"login";}
    static void login(DocumentAgent.Provider p) throws IOException {
        // Pass the selected path as data. The interactive CLI owns the OAuth exchange.
        String script="& $env:OFFICEMD_LOGIN_EXE "+loginArgs(p);
        String encoded=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Path ps=Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe");
        var launcher=new ProcessBuilder(ps.toString(),"-NoProfile","-NonInteractive","-Command",
                "$ErrorActionPreference='Stop'; Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList '-NoProfile','-NoExit','-EncodedCommand','"+encoded+"'");
        launcher.environment().put("OFFICEMD_LOGIN_EXE",p.executable().toString());
        Process proc=launcher.redirectErrorStream(true).start();
        try {
            if(!proc.waitFor(5,TimeUnit.SECONDS) || proc.exitValue()!=0) throw new IOException("로그인 창을 열 수 없습니다. 터미널에서 "+p.exe+" "+loginArgs(p)+"을 실행하세요.");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("로그인 창 열기 중단",e);}
        finally {if(proc.isAlive())proc.destroyForcibly();}
    }

    static String auth(DocumentAgent.Provider p,Path exe) throws Exception {
        if(p==DocumentAgent.Provider.COPILOT) return "인증 상태는 ‘연결 테스트’로 확인하세요.";
        List<String> cmd=new ArrayList<>(List.of(exe.toString()));
        if(p==DocumentAgent.Provider.CODEX)cmd.addAll(List.of("login","status"));
        else cmd.addAll(List.of("auth","status","--json"));
        var builder=new ProcessBuilder(cmd);
        if(p==DocumentAgent.Provider.CODEX)builder.redirectErrorStream(true);
        else builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc=builder.start();
        try{
            proc.getOutputStream().close();
            var output=new FutureTask<>(()->new String(proc.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
            Thread.startVirtualThread(output);
            if(!proc.waitFor(12,TimeUnit.SECONDS))return "인증 상태 확인 시간 초과";
            String result=output.get(2,TimeUnit.SECONDS);
            return authLabel(p,proc.exitValue(),result);
        }finally{if(proc.isAlive())proc.destroyForcibly();}
    }

    static String authLabel(DocumentAgent.Provider p,int exit,String result){
        if(p==DocumentAgent.Provider.CLAUDE){
            try{
                boolean logged=JsonParser.parseString(result).getAsJsonObject().get("loggedIn").getAsBoolean();
                if(!logged)return "로그인 필요";
                if(exit==0)return "로그인됨";
            }catch(RuntimeException ignored){}
        }else{
            String lower=result.toLowerCase(Locale.ROOT);
            if(lower.contains("not logged in") || lower.contains("not authenticated"))return "로그인 필요";
            if(exit==0 && lower.contains("logged in"))return "로그인됨";
        }
        return "인증 상태 확인 불가 · CLI 버전과 로그인 상태를 확인하세요.";
    }
}
