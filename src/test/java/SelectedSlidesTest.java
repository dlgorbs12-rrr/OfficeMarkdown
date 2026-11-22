import java.nio.file.*;
import java.util.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelectedSlidesTest {
    @TempDir Path folder;
    @Test void manyMatchingSlidesInOneDeckLeaveRoomForOtherDecks() throws Exception {
        var library=folder.resolve("notes");
        var notes=new ArrayList<Path>();
        for(String name:List.of("A","B")){
            var source=folder.resolve(name+".pptx");
            try(var deck=new XMLSlideShow();var out=Files.newOutputStream(source)){
                for(int i=0;i<(name.equals("A")?24:1);i++)deck.createSlide().createTextBox().setText("architecture");
                deck.write(out);
            }
            notes.add(Library.convert(source,library));
        }
        SearchIndex.indexCompleted(library,notes);
        var reply=McpSearch.slideDocuments("architecture",null,null,prompt->"[]",status->{});
        assertEquals(2,reply.candidates().size());
    }
    @Test void candidateDiscoveryThenOnlyCheckedDecksAreInspected() throws Exception {
        var notes=new ArrayList<Path>();
        var library=folder.resolve("notes");
        for(String name:List.of("alpha","beta","excluded")){
            Path source=folder.resolve(name+".pptx");
            try(var deck=new XMLSlideShow();var out=Files.newOutputStream(source)){
                deck.createSlide().createTextBox().setText("architecture "+name);
                deck.write(out);
            }
            notes.add(Library.convert(source,library));
        }
        SearchIndex.indexCompleted(library,notes);
        String request="architecture";
        var found=McpSearch.slideDocuments(request,null,null,prompt->{
            assertFalse(prompt.contains("후보당"));return "[]";
        },status->{});
        assertEquals(3,found.candidates().size());
        assertTrue(found.sources().isEmpty());
        var selected=notes.subList(0,2).stream().map(Path::toString).toList();
        DocumentAgent.Ask judge=prompt->{
            assertTrue(prompt.contains("질문: "+request));
            assertTrue(prompt.contains("architecture alpha") && prompt.contains("architecture beta"));
            assertFalse(prompt.contains("architecture excluded"));
            return "[{\"id\":1,\"grade\":\"yes\",\"reason\":\"구성\",\"quote\":\"architecture\"},{\"id\":2,\"grade\":\"yes\",\"reason\":\"구성\",\"quote\":\"architecture\"}]";
        };
        var slides=McpSearch.selectedSlides(request,found.expanded(),null,selected,judge,status->{});
        assertEquals(2,slides.sources().size());
        assertTrue(slides.sources().stream().allMatch(c->selected.contains(c.proof().md().toString())));
        assertThrows(java.io.IOException.class,()->McpSearch.selectedSlides(request,found.expanded(),null,List.of(),judge,status->{}));
    }
}