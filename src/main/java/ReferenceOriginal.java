import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

/** Rebuild legacy page text from a fingerprinted original, without rewriting user notes. */
final class ReferenceOriginal {
    record Content(String hash,Map<Integer,String> pages) {}
    static Content read(Path source) throws IOException {
        Path temp=Path.of(System.getProperty("java.io.tmpdir"));
        try(var snapshot=EvidenceIndex.Snapshot.capture(source,temp)){
            var pages=new LinkedHashMap<Integer,String>();
            try(var pkg=OPCPackage.open(snapshot.file().toFile(),PackageAccess.READ);var show=new XMLSlideShow(pkg)){
                for(var slide:show.getSlides()){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("장표 색인 준비 취소");
                    int number=pages.size()+1;
                    var out=new StringWriter();
                    Library.writeSlide(slide,out,null,"슬라이드 "+number);
                    pages.put(number,out.toString().strip());
                }
            }
            snapshot.check(source);
            return new Content(snapshot.hash(),Collections.unmodifiableMap(pages));
        }catch(InvalidFormatException e){throw new IOException("PPTX 원본 형식을 확인할 수 없습니다.",e);}
    }
}
