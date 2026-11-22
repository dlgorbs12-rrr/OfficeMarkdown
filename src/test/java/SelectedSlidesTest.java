import java.nio.file.*;
import java.util.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelectedSlidesTest {
    @TempDir Path folder;
    @Test void presentationControlsSubmitOnlyCurrentlyCheckedDocuments() throws Exception {
        var check=new java.util.concurrent.FutureTask<Void>(()->{
            var a=new Library.Hit(folder.resolve("a.md"),folder.resolve("a.pptx"),"A","","");
            var b=new Library.Hit(folder.resolve("b.md"),folder.resolve("b.PPTX"),"B","","");
            var x=new Library.Hit(folder.resolve("c.md"),folder.resolve("c.docx"),"C","","");
            var panel=new FoundDocuments(List.of(a,b,a,x));
            var choices=FoundDocuments.class.getDeclaredField("choices");choices.setAccessible(true);
            var controls=(Map<?,?>)choices.get(panel);
            assertEquals(Set.of(a.md(),b.md(),x.md()),controls.keySet());
            var first=(javax.swing.JCheckBox)controls.get(a.md());
            var second=(javax.swing.JCheckBox)controls.get(b.md());
            var action=FoundDocuments.class.getDeclaredField("search");action.setAccessible(true);
            var button=(javax.swing.JButton)action.get(panel);
            var requests=new ArrayList<List<String>>();panel.onSearch(requests::add);
            var scope=FoundDocuments.class.getDeclaredField("ask");scope.setAccessible(true);
            var scopeButton=(javax.swing.JButton)scope.get(panel);
            var scopes=new ArrayList<List<String>>();panel.onScope(scopes::add);
            ((javax.swing.JCheckBox)controls.get(x.md())).doClick();scopeButton.doClick();
            assertEquals(List.of(List.of(x.md().toString())),scopes);
            assertFalse(button.isEnabled());
            first.doClick();second.doClick();
            scopeButton.doClick();
            assertEquals(List.of(x.md().toString(),a.md().toString(),b.md().toString()),scopes.getLast());
            assertTrue(requests.isEmpty());
            panel.busy(true);assertFalse(first.isEnabled());assertFalse(button.isEnabled());
            panel.busy(false);button.doClick();first.doClick();button.doClick();second.doClick();
            assertEquals(List.of(List.of(a.md().toString(),b.md().toString()),List.of(b.md().toString())),requests);
            assertFalse(button.isEnabled());
            return null;
        });
        javax.swing.SwingUtilities.invokeAndWait(check);
        check.get();
    }
    @Test void emptyFollowUpClearsTheActiveSelectionButEarlierReplyCanReopenIt() throws Exception {
        var check=new java.util.concurrent.FutureTask<Void>(()->{
            var chat=new AgentDialog(folder,hit->{},hit->{});
            var display=AgentDialog.class.getDeclaredMethod("displayReply",javax.swing.JPanel.class,McpSearch.Reply.class,String.class,String.class,SearchIndex.Focus.class);
            display.setAccessible(true);
            var field=AgentDialog.class.getDeclaredField("foundTab");field.setAccessible(true);
            var tab=(javax.swing.JPanel)field.get(chat);
            var body=new javax.swing.JPanel();
            var hit=new Library.Hit(folder.resolve("a.md"),folder.resolve("a.pptx"),"A","slide","a");
            display.invoke(chat,body,new McpSearch.Reply("A",List.of(),List.of(hit),"A"),"A","",null);
            var previous=(FoundDocuments)tab.getComponent(0);
            var t=FoundDocuments.class.getDeclaredField("choices");t.setAccessible(true);
            var action=FoundDocuments.class.getDeclaredField("search");action.setAccessible(true);
            ((javax.swing.JCheckBox)((Map<?,?>)t.get(previous)).get(hit.md())).doClick();
            display.invoke(chat,new javax.swing.JPanel(),new McpSearch.Reply("B",List.of()),"B","A",null);
            var current=tab.getComponent(0);
            assertNotSame(previous,current);
            assertEquals(0,((Map<?,?>)t.get(current)).size());
            assertFalse(((javax.swing.JButton)action.get(current)).isEnabled());
            ((javax.swing.JButton)body.getComponent(body.getComponentCount()-1)).doClick();
            assertSame(previous,tab.getComponent(0));
            assertTrue(((javax.swing.JButton)action.get(previous)).isEnabled());
            return null;
        });
        javax.swing.SwingUtilities.invokeAndWait(check);
        check.get();
    }
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
    @Test void selectedDecksEachReceiveAReviewAndOneFailureDoesNotHideAnother() throws Exception {
        var library=folder.resolve("notes");
        var selected=new ArrayList<String>();
        for(String name:List.of("crowded","diagram")){
            Path source=folder.resolve(name+".pptx");
            try(var deck=new XMLSlideShow();var out=Files.newOutputStream(source)){
                for(int i=0;i<(name.equals("crowded")?8:1);i++)
                    deck.createSlide().createTextBox().setText(name.equals("crowded")?"architecture":"diagram source");
                deck.write(out);
            }
            var md=Library.convert(source,library);selected.add(md.toString());
        }
        SearchIndex.indexCompleted(library,selected.stream().map(Path::of).toList());
        ReferenceIndex.prepare();
        var result=McpSearch.selectedSlides("architecture","architecture",null,selected,prompt->{
            if(prompt.contains("architecture") && !prompt.contains("diagram source"))throw new IllegalStateException("fixture unavailable");
            return "[{\"id\":1,\"grade\":\"yes\",\"reason\":\"diagram\",\"quote\":\"diagram source\"}]";
        },status->{});
        assertEquals(selected,result.decks().stream().map(McpSearch.DeckResult::note).toList());
        assertTrue(result.decks().getFirst().outcome().contains("확인 실패"));
        assertEquals(4,result.decks().getFirst().candidates().size());
        assertTrue(result.decks().getFirst().slides().isEmpty());
        assertTrue(result.decks().getLast().outcome().contains("앞쪽 장표 1장 검토"));
        assertEquals(List.of(Path.of(selected.getLast())),result.sources().stream().map(c->c.proof().md()).toList());
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
            assertNotEquals(prompt.contains("architecture alpha"),prompt.contains("architecture beta"));
            assertFalse(prompt.contains("architecture excluded"));
            return "[{\"id\":1,\"grade\":\"yes\",\"reason\":\"구성\",\"quote\":\"architecture\"}]";
        };
        var slides=McpSearch.selectedSlides(request,found.expanded(),null,selected,judge,status->{});
        assertEquals(2,slides.sources().size());
        assertTrue(slides.sources().stream().allMatch(c->selected.contains(c.proof().md().toString())));
        assertThrows(java.io.IOException.class,()->McpSearch.selectedSlides(request,found.expanded(),null,List.of(),judge,status->{}));
    }
}