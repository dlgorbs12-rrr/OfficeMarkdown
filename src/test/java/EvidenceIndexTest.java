import java.io.IOException;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceIndexTest {
    @TempDir Path folder;
    final EvidenceIndex.Edge first = new EvidenceIndex.Edge("maps","REQ-1","SC-1","Mapping!A2","Mapping!B2");
    final EvidenceIndex.Edge second = new EvidenceIndex.Edge("maps","REQ-1","SC-2","Mapping!A3","Mapping!B3");

    Path workbook(String name, String text) throws Exception {
        var path = folder.resolve(name);
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(path)) {
            book.createSheet("Mapping").createRow(0).createCell(0).setCellValue(text);
            book.write(out);
        }
        return path;
    }

    Path index(Path source) throws Exception {
        var note = Library.convert(source,folder.resolve("library"));
        SearchIndex.indexCompleted(note.getParent(),List.of(note));
        return note;
    }

    void sql(String statement) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database());
             var stmt = db.createStatement()) { stmt.execute(statement); }
    }

    @Test void versionsRetryDeletionAndExplicitSelection() throws Exception {
        var source=workbook("a.xlsx","one");
        var note=index(source);
        var other=index(workbook("b.xlsx","other"));
        var pending=EvidenceIndex.read(note,"v1");
        assertEquals(EvidenceIndex.State.PENDING,pending.state());
        EvidenceIndex.extract(note,"v1",snapshot->{
            assertArrayEquals(Files.readAllBytes(source),Files.readAllBytes(snapshot));
            return List.of(first,first);
        });
        EvidenceIndex.extract(note,"v1",p->{ throw new AssertionError("unnecessary re-extraction"); });
        var citation = EvidenceIndex.read(note,"v1").version();
        assertEquals(List.of(first),EvidenceIndex.read(citation).edges());
        EvidenceIndex.extract(other,"v1",p->List.of(second));
        assertEquals(Set.of(note),EvidenceIndex.readSelection(Set.of(note),"v1").keySet());
        assertEquals(List.of(first),EvidenceIndex.read(note,"v1").edges());
        assertEquals(List.of(second),EvidenceIndex.read(other,"v1").edges());
        assertEquals(EvidenceIndex.State.STALE,EvidenceIndex.read(note,"v2").state());
        // Equal length/timestamp are insufficient: a changed byte invalidates evidence.
        byte[] bytes=Files.readAllBytes(source);
        var modified=Files.getLastModifiedTime(source);
        bytes[0]^=1;
        Files.write(source,bytes);
        Files.setLastModifiedTime(source,modified);
        assertEquals(EvidenceIndex.State.STALE,EvidenceIndex.read(note,"v1").state());
        assertTrue(EvidenceIndex.read(note,"v1").edges().isEmpty());
        workbook("a.xlsx","two");
        assertEquals(note,index(source));
        var newer=EvidenceIndex.read(note,"v1");
        assertEquals(EvidenceIndex.State.PENDING,newer.state());
        assertNotEquals(pending.version().generation(),newer.version().generation());
        assertNotEquals(pending.version().hash(),newer.version().hash());
        assertEquals(EvidenceIndex.State.STALE,EvidenceIndex.read(citation).state());
        assertTrue(EvidenceIndex.read(citation).edges().isEmpty());
        Files.delete(source);
        assertEquals(EvidenceIndex.State.MISSING,EvidenceIndex.read(note,"v1").state());
        Files.delete(note);
        SearchIndex.synchronize(note.getParent(),ignored->{});
        assertEquals(EvidenceIndex.State.NOT_VISIBLE,EvidenceIndex.read(note,"v1").state());
        assertEquals(List.of(second),EvidenceIndex.read(other,"v1").edges());
    }

    @Test void readerSeesCompleteVersionsAndFailedPublicationRollsBack() throws Exception {
        var note=index(workbook("data.xlsx","one"));
        EvidenceIndex.extract(note,"v1",p->List.of(first));
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(()->{
                EvidenceIndex.extract(note,"v2",p->{
                    started.countDown();
                    try { if (!release.await(5,TimeUnit.SECONDS)) throw new IOException("timeout"); }
                    catch (InterruptedException e) { throw new IOException(e); }
                    return List.of(second);
                });
                return null;
            });
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));
                assertEquals(List.of(first),EvidenceIndex.read(note,"v1").edges());
            } finally { release.countDown(); }
            writer.get(5,TimeUnit.SECONDS);
        }
        assertEquals(List.of(second),EvidenceIndex.read(note,"v2").edges());
        sql("CREATE TRIGGER reject_second BEFORE INSERT ON evidence_edge WHEN NEW.dst='SC-2' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        assertThrows(IOException.class,()->EvidenceIndex.extract(note,"v3",p->List.of(first,second)));
        assertEquals(List.of(second),EvidenceIndex.read(note,"v2").edges());
        assertTrue(EvidenceIndex.read(note,"v3").edges().isEmpty());
    }

    @Test void changeDuringExtractionRetriesOnlyWithMatchingNativeBytes() throws Exception {
        var source=workbook("changed.xlsx","old");
        var note=index(source);
        var calls=new AtomicInteger();
        EvidenceIndex.extract(note,"v1",p->{
            if (calls.incrementAndGet()==1) {
                try { index(workbook("changed.xlsx","new")); }
                catch (Exception e) { throw new IOException(e); }
                return List.of(first);
            }
            return List.of(second);
        });
        assertEquals(2,calls.get());
        assertEquals(List.of(second),EvidenceIndex.read(note,"v1").edges());
    }

    @Test void legacyNotesAndHeldDocumentsNeverPublishEvidence() throws Exception {
        var source=workbook("legacy.xlsx","old");
        var note=index(source);
        EvidenceIndex.extract(note,"v1",p->List.of(first));
        try (var db=DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database());
             var q=db.prepareStatement("INSERT INTO large_pending VALUES(?,?)")) {
            q.setString(1,note.toString()); q.setLong(2,SearchIndex.LARGE_BYTES+1); q.executeUpdate();
        }
        assertEquals(EvidenceIndex.State.NOT_VISIBLE,EvidenceIndex.read(note,"v1").state());
        assertThrows(IOException.class,()->EvidenceIndex.extract(note,"v2",p->List.of(second)));
        sql("DELETE FROM large_pending");
        sql("DROP TABLE evidence_edge");
        sql("DROP TABLE evidence_version");
        assertEquals(EvidenceIndex.State.UNVERSIONED,EvidenceIndex.read(note,"v1").state());
        // Legacy Markdown has no fingerprint. Migration must not bind it to today's source.
        Files.writeString(note,"# old\n\n[원본 파일 열기](<"+source.toUri()+">)\n\n## body\nold");
        SearchIndex.indexCompleted(note.getParent(),List.of(note));
        assertEquals(EvidenceIndex.State.UNVERSIONED,EvidenceIndex.read(note,"v1").state());
        assertThrows(IOException.class,()->EvidenceIndex.extract(note,"v1",p->List.of(first)));
    }
}
