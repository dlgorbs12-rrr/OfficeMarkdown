import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Derived fields for relevance search. Original text remains the evidence shown to callers. */
final class RankedFields {
    private static final Pattern GAP = Pattern.compile("(?<=[가-힣])[\\p{Z}\\s]+(?=[가-힣])");
    private static final Pattern DIVIDER = Pattern.compile("\\|(?:\\s*:?-+:?\\s*\\|)+\\s*");
    private static final Pattern GRID_HEADER = Pattern.compile("\\| 행 \\|(?: [A-Z]+ \\|)+");
    private static final Pattern ADDRESS = Pattern.compile("(?<=\\|)\\s*[A-Z]+\\d+ · ");
    static boolean installed(Connection db) throws SQLException {
        try (var q = db.createStatement(); var r = q.executeQuery("SELECT 1 FROM sqlite_master WHERE name='rank_sections'")) {
            return r.next();
        }
    }

    static void prepare(Connection db) throws SQLException {
        if (installed(db)) return;
        try (var q = db.createStatement()) {
            q.execute("CREATE VIRTUAL TABLE rank_joined USING fts5(value, tokenize='trigram')");
            q.execute("CREATE TABLE rank_headers(first INTEGER PRIMARY KEY,last INTEGER NOT NULL,header TEXT NOT NULL,doc INTEGER REFERENCES docs ON DELETE CASCADE)");
            q.execute("CREATE INDEX rank_header_doc ON rank_headers(doc,first)");
            q.execute("CREATE TABLE rank_sections(doc INTEGER REFERENCES docs ON DELETE CASCADE,section TEXT,digest TEXT,PRIMARY KEY(doc,section))");
            q.execute("CREATE INDEX rank_section_digest ON rank_sections(digest,section)");
            q.execute("CREATE TRIGGER rank_delete AFTER DELETE ON lines BEGIN DELETE FROM rank_joined WHERE rowid=old.id; END");
            indexSelected(db,"SELECT id FROM docs");
        }
    }

    static void indexSelected(Connection db,String select) throws SQLException {
        try (var q=db.createStatement();var rows=q.executeQuery(select)) {
            while(rows.next()) index(db,rows.getLong(1));
        }
    }

    static void index(Connection db, long doc) throws SQLException {
        try (var q=db.prepareStatement("SELECT id,section,text,kind FROM lines WHERE doc=? ORDER BY section,id");
             var joined=db.prepareStatement("INSERT INTO rank_joined(rowid,value) VALUES(?,?)");
             var headers=db.prepareStatement("INSERT INTO rank_headers(first,last,header,doc) VALUES(?,?,?,?)");
             var sections=db.prepareStatement("INSERT OR REPLACE INTO rank_sections VALUES(?,?,?)")) {
            q.setLong(1,doc);
            sections.setLong(1,doc);
            headers.setLong(4,doc);
            var table=new Table();
            MessageDigest digest;
            try { digest=MessageDigest.getInstance("SHA-256"); }
            catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
            String section=null;
            long previous=0,contentLength=0;
            try(var r=q.executeQuery()){
                while(r.next()){
                    long id=r.getLong(1);
                    String next=r.getString(2), text=r.getString(3);
                    var gap=GAP.matcher(text);
                    if(gap.find()) {
                        joined.setLong(1,id);
                        joined.setString(2,gap.replaceAll(""));
                        joined.executeUpdate();
                    }
                    if(r.getInt(4)!=2) continue;
                    if(!next.equals(section)){
                        table.finish(headers);
                        storeDigest(sections,section,digest,contentLength);
                        contentLength=0;
                        section=next;
                    }
                    if(previous+1!=id)table.finish(headers);
                    previous=id;
                    if(!text.startsWith("## "))contentLength+=text.strip().length();
                    digest.update(text.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte)'\n');
                    table.accept(headers,id,text);
                }
            }
            table.finish(headers);
            storeDigest(sections,section,digest,contentLength);
        }
    }

    private static void storeDigest(PreparedStatement q,String section,MessageDigest digest,long length) throws SQLException {
        if(section==null || length<40){digest.reset();return;}
        q.setString(2,section);
        q.setString(3,HexFormat.of().formatHex(digest.digest()));
        q.executeUpdate();
    }

    private static final class Table {
        String pending,header;
        long pendingId,start,end;
        boolean grid;
        void accept(PreparedStatement q,long id,String text) throws SQLException {
            if(!text.startsWith("|")) {finish(q);return;}
            if(pending!=null && DIVIDER.matcher(text).matches()){
                // Blank lines are omitted from the line index. A second divider starts a new table.
                String first=pending;
                pending=null;
                finish(q);
                grid=GRID_HEADER.matcher(first).matches();
                header=grid || first.startsWith("| 셀 주소 |") ? "" : first;
                return;
            }
            flushRow();
            pending=text;
            pendingId=id;
        }
        void flushRow(){
            if(pending==null)return;
            if(grid){
                grid=false;
                String labels=ADDRESS.matcher(pending).replaceAll(" ").replaceFirst("^\\|\\s*\\d+\\s*\\|","|");
                if(labels.codePoints().filter(Character::isLetter).count()>=2
                        && labels.codePoints().noneMatch(Character::isDigit)) header=labels;
            }else if(header!=null && !header.isEmpty()){
                if(start==0)start=pendingId;
                end=pendingId;
            }
            pending=null;
        }
        void finish(PreparedStatement q) throws SQLException {
            flushRow();
            if(start!=0){
                q.setLong(1,start);q.setLong(2,end);q.setString(3,header);q.executeUpdate();
            }
            header=null;grid=false;start=0;
        }
    }
}
