import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/** Local, derived OCR for searching existing pictures without rewriting any Markdown or picture assets. */
final class PictureSearchIndex {
    record Stats(int reused,int recognized,int empty,int failed){}
    private record Row(long id,Path note,PictureAssets.Reference ref,String nativeText,String cached){}
    private static final class Group {
        String text;
        final List<Row> pending=new ArrayList<>();
        final List<Row> occurrences=new ArrayList<>();
    }

    static Stats enrich(ImageOcr paddle,Consumer<String> status)throws Exception{
        var rows=SearchIndex.readIndex(db->{
            var list=new ArrayList<Row>();
            try(var q=db.createStatement();var r=q.executeQuery("""
                    SELECT p.id,d.md,p.path,p.location,p.text,p.search_ocr FROM searchable_pictures p
                    JOIN visible_docs d ON d.id=p.doc ORDER BY p.id
                    """)){
                while(r.next())list.add(new Row(r.getLong(1),Path.of(r.getString(2)),
                        new PictureAssets.Reference(r.getString(3),r.getString(4)),r.getString(5),r.getString(6)));
            }
            return list;
        });
        if(rows.isEmpty())return new Stats(0,0,0,0);
        var groups=new LinkedHashMap<String,Group>();
        var digest=MessageDigest.getInstance("SHA-256");
        int failed=0,reused=0,recognized=0,empty=0;
        for(var row:rows){
            SavedPictureDocument.checkInterrupted();
            try{
                var file=PictureAssets.pictureFile(row.note,row.ref);
                if(file==null){failed++;continue;}
                try(var in=new DigestInputStream(Files.newInputStream(file),digest)){in.transferTo(OutputStream.nullOutputStream());}
                var group=groups.computeIfAbsent(HexFormat.of().formatHex(digest.digest()),unused->new Group());
                group.occurrences.add(row);
                String text=row.nativeText.isBlank()?row.cached:row.nativeText;
                if(text!=null && !OcrRepair.suspected(text) && (group.text==null || text.length()>group.text.length()))
                    group.text=text;
                if(row.nativeText.isBlank() && row.cached==null)group.pending.add(row);
            }catch(IOException e){digest.reset();failed++;}
        }
        // Hold a write transaction only while publishing a completed group, never during recognition.
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+SearchIndex.database());var setup=db.createStatement()){
            setup.execute("PRAGMA busy_timeout=15000");
            setup.execute("PRAGMA foreign_keys=ON");
            setup.execute("CREATE TABLE IF NOT EXISTS picture_search_ocr(id INTEGER PRIMARY KEY REFERENCES pictures(id) ON DELETE CASCADE,text TEXT NOT NULL)");
            setup.execute("CREATE TABLE IF NOT EXISTS picture_fingerprints(id INTEGER PRIMARY KEY REFERENCES pictures(id) ON DELETE CASCADE,digest TEXT NOT NULL)");
            db.setAutoCommit(false);
            try(var key=db.prepareStatement("""
                    INSERT INTO picture_fingerprints(id,digest) SELECT p.id,? FROM pictures p JOIN docs d ON d.id=p.doc
                    WHERE p.id=? AND d.md=? AND p.path=? ON CONFLICT(id) DO UPDATE SET digest=excluded.digest WHERE digest<>excluded.digest
                    """)){
                for(var group:groups.entrySet()){
                    for(var row:group.getValue().occurrences){
                        key.setString(1,group.getKey());key.setLong(2,row.id);key.setString(3,row.note.toString());
                        key.setString(4,row.ref.relativePath());key.executeUpdate();
                    }
                }
                db.commit();
            }
            try(var save=db.prepareStatement("""
                    INSERT OR IGNORE INTO picture_search_ocr(id,text)
                    SELECT p.id,? FROM pictures p JOIN docs d ON d.id=p.doc WHERE p.id=? AND d.md=? AND p.path=? AND p.text=''
                    """)){
                int current=0;
                for(var group:groups.values()){
                    current++;
                    if(group.pending.isEmpty())continue;
                    SavedPictureDocument.checkInterrupted();
                    String value=group.text;
                    boolean copied=value!=null;
                    if(value==null){
                        if(paddle==null)continue;
                        var first=group.pending.getFirst();
                        status.accept("그림 검색 보강 · "+current+"/"+groups.size());
                        try{
                            Path file=PictureAssets.pictureFile(first.note,first.ref);
                            if(file==null){failed+=group.pending.size();continue;}
                            byte[] bytes=Files.readAllBytes(file);
                            String name=file.getFileName().toString();
                            var image=OfficePicture.decode(bytes,OfficePicture.type(bytes,name.substring(name.lastIndexOf('.')+1)));
                            if(image==null){failed+=group.pending.size();continue;}
                            value=paddle.text(bytes,image);
                        }catch(IOException ex){failed+=group.pending.size();continue;}
                    }
                    int saved=0;
                    for(var row:group.pending){
                        save.setString(1,value);save.setLong(2,row.id);save.setString(3,row.note.toString());
                        save.setString(4,row.ref.relativePath());saved+=save.executeUpdate();
                    }
                    db.commit();
                    if(value.isBlank())empty+=saved;
                    else if(copied)reused+=saved;
                    else recognized+=saved;
                }
            }
        }
        return new Stats(reused,recognized,empty,failed);
    }
}
