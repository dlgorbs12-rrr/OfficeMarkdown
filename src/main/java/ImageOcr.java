import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

final class ImageOcr implements AutoCloseable {
    enum Mode {
        WINDOWS("Windows OCR · 로컬"), PADDLE("PaddleOCR · 한글 정밀 · 로컬"),
        VISION("MarkItDown · 비전 API"), OFF("끄기");
        final String label;
        Mode(String label) { this.label=label; }
        public String toString() { return label; }
    }

    private final Mode mode;
    private final Path tools;
    private final Map<String,String> cache = new LinkedHashMap<>(128,.75f,true) {
        protected boolean removeEldestEntry(Map.Entry<String,String> entry) { return size()>512; }
    };
    private Process worker;
    private Path work;
    private BufferedReader replies;
    private FutureTask<String> diagnostics;
    private BufferedWriter requests;
    final Set<String> warnings = new LinkedHashSet<>();

    ImageOcr(Mode mode, Path tools) { this.mode=mode; this.tools=tools; }

    static Path scripts() throws Exception {
        Path jar=Path.of(ImageOcr.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path packaged=jar.resolveSibling("ocr");
        return Files.isDirectory(packaged) ? packaged : Path.of("ocr").toAbsolutePath();
    }

    private static Path python() {
        return Path.of(System.getenv("LOCALAPPDATA"),"OfficeMarkdown","vision-env","Scripts","python.exe");
    }

    private static Path paddlePath(String name) throws IOException {
        String value = System.getenv().getOrDefault(name, "").strip();
        if (value.isEmpty()) throw new IOException("한글 정밀 OCR을 먼저 설치하고 run-local.cmd로 실행하세요. 필요한 설정: " + name);
        try { return Path.of(value); }
        catch (InvalidPathException e) { throw new IOException("한글 정밀 OCR 경로 설정이 올바르지 않습니다: " + name, e); }
    }

    static void validate(Mode mode) throws IOException {
        if (mode == Mode.PADDLE) {
            Path python = paddlePath("OFFICEMD_PADDLE_PYTHON");
            Path models = paddlePath("OFFICEMD_PADDLE_MODELS");
            if (!Files.isRegularFile(python)
                    || !Files.isDirectory(models.resolve("PP-OCRv5_mobile_det_infer"))
                    || !Files.isDirectory(models.resolve("korean_PP-OCRv5_mobile_rec_infer")))
                throw new IOException("한글 정밀 OCR의 Python 또는 한국어 모델을 찾을 수 없습니다. ocr/install-paddle.ps1로 설치하세요.");
        }
        if (mode==Mode.VISION && (!Files.isRegularFile(python()) ||
                System.getenv().getOrDefault("OPENAI_API_KEY","").isBlank() ||
                System.getenv().getOrDefault("OFFICEMD_VISION_MODEL","").isBlank()))
            throw new IOException("비전 OCR에는 Python 플러그인과 OPENAI_API_KEY, OFFICEMD_VISION_MODEL 설정이 필요합니다. 로컬 OCR은 Windows OCR을 선택하세요.");
    }

    String text(byte[] data, BufferedImage image) throws IOException {
        if (mode==Mode.OFF) return "";
        String key;
        try { key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        String value=cache.get(key);
        if (value==null) {
            try { value=recognize(image); }
            catch(IOException e) {
                try { close(); } catch(IOException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
            cache.put(key,value);
        }
        return value;
    }

    private String recognize(BufferedImage image) throws IOException {
        ensureWorker();
        Path png=work.resolve("image.png");
        try {
            double scale=Math.min(1d,4000d/Math.max(image.getWidth(),image.getHeight()));
            var rgb=new BufferedImage(Math.max(1,(int)(image.getWidth()*scale)),
                    Math.max(1,(int)(image.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics=rgb.createGraphics();
            try {
                graphics.setColor(Color.WHITE); graphics.fillRect(0,0,rgb.getWidth(),rgb.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image,0,0,rgb.getWidth(),rgb.getHeight(),null);
            } finally { graphics.dispose(); }
            ImageIO.write(rgb,"PNG",png.toFile());
            String request=Base64.getEncoder().encodeToString(png.toString().getBytes(StandardCharsets.UTF_8));
            requests.write(request); requests.newLine(); requests.flush();
            FutureTask<String> response=new FutureTask<>(replies::readLine);
            Thread.startVirtualThread(response);
            String line=response.get(90,TimeUnit.SECONDS);
            if (line==null) throw new IOException(mode+" 프로세스 종료: "+workerError());
            boolean ok=line.startsWith("OK ");
            if (!ok && !line.startsWith("ERR ")) throw new IOException(mode+" 응답 형식 오류");
            String value=new String(Base64.getDecoder().decode(line.substring(ok?3:4)),StandardCharsets.UTF_8);
            if (!ok) throw new IOException(mode+" 실패: "+value);
            return value.strip();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("OCR 중단",e);
        } catch (ExecutionException | TimeoutException | IllegalArgumentException e) {
            throw new IOException(mode+" 응답 오류",e);
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private void ensureWorker() throws IOException {
        if(worker!=null && worker.isAlive()) return;
        validate(mode);
        close();
        work=Files.createTempDirectory("office-ocr-");
        List<String> command = switch (mode) {
            case VISION -> List.of(python().toString(),"-I",tools.resolve("vision-ocr.py").toString(),"--worker");
            case PADDLE -> List.of(paddlePath("OFFICEMD_PADDLE_PYTHON").toString(),"-I",
                    tools.resolve("paddle-ocr.py").toString(),"--worker");
            case WINDOWS -> List.of(Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
                    "-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",tools.resolve("windows-ocr.ps1").toString(),"-Worker");
            case OFF -> throw new IllegalStateException("OCR 꺼짐 상태에서는 작업 프로세스를 시작할 수 없습니다.");
        };
        worker=new ProcessBuilder(command).start();
        var errors=worker.errorReader(StandardCharsets.UTF_8);
        diagnostics=new FutureTask<>(() -> {
            try(errors) {
                StringBuilder last=new StringBuilder();
                char[] block=new char[1024];
                for(int n;(n=errors.read(block))!=-1;) {
                    last.append(block,0,n);
                    if(last.length()>4096) last.delete(0,last.length()-4096);
                }
                return last.toString().strip();
            }
        });
        Thread.startVirtualThread(diagnostics);
        requests=worker.outputWriter(StandardCharsets.US_ASCII);
        replies=worker.inputReader(StandardCharsets.US_ASCII);
    }

    private String workerError() {
        try { return diagnostics.get(1,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return "OCR 중단"; }
        catch(ExecutionException | TimeoutException e) { return "진단 메시지 없음"; }
    }

    public void close() throws IOException {
        if(worker!=null) {
            try {
                requests.close();
                if (!worker.waitFor(1,TimeUnit.SECONDS)) worker.destroyForcibly().onExit().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); worker.destroyForcibly().onExit().join();
            } finally {
                if (worker.isAlive()) worker.destroyForcibly().onExit().join();
                replies.close(); worker=null;
            }
        }
        if(work!=null) {
            Files.deleteIfExists(work.resolve("image.png"));
            Files.deleteIfExists(work); work=null;
        }
    }
}
