import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class XlsxStreamingStrings implements AutoCloseable {
    private final Path directory;
    private Path offsetsFile, textFile;
    private RandomAccessFile offsets, text;

    XlsxStreamingStrings(Path directory) {
        this.directory = directory;
    }

    void load(InputStream source) throws IOException {
        offsetsFile = Files.createTempFile(directory, ".xlsx-string-offsets-", ".tmp");
        textFile = Files.createTempFile(directory, ".xlsx-string-text-", ".tmp");
        try (var index = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(offsetsFile)));
             var values = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(textFile)))) {
            XlsxStreaming.parse(source, new StringTable(index, values));
        }
        offsets = new RandomAccessFile(offsetsFile.toFile(), "r");
        text = new RandomAccessFile(textFile.toFile(), "r");
    }

    String get(int index) throws IOException {
        long position = (long) index * Long.BYTES;
        if (offsets == null || position < 0 || position >= offsets.length()) {
            throw new IOException("잘못된 XLSX 공유 문자열 번호");
        }
        offsets.seek(position);
        text.seek(offsets.readLong());
        return XlsxStreaming.readText(text);
    }

    @Override
    public void close() throws IOException {
        try (var index = offsets; var values = text) {
            // Close both input files before deleting the backing files on Windows.
        } finally {
            try {
                if (offsetsFile != null) Files.deleteIfExists(offsetsFile);
            } finally {
                if (textFile != null) Files.deleteIfExists(textFile);
            }
        }
    }

    private static final class StringTable extends DefaultHandler {
        private final DataOutputStream index, values;
        private final StringBuilder item = new StringBuilder();
        private long position;
        private int depth;
        private int itemDepth = -1, textDepth = -1, phoneticDepth = -1;

        StringTable(DataOutputStream index, DataOutputStream values) {
            this.index = index;
            this.values = values;
        }

        @Override
        public void startElement(String ns, String name, String qualified, Attributes attributes) {
            depth++;
            if (!XlsxStreaming.MAIN_NS.equals(ns)) return;
            if (name.equals("si") && depth == 2) {
                itemDepth = depth;
                item.setLength(0);
            } else if (itemDepth > 0 && name.equals("rPh")) {
                phoneticDepth = depth;
            } else if (itemDepth > 0 && phoneticDepth < 0 && name.equals("t")) {
                textDepth = depth;
            }
        }

        @Override
        public void characters(char[] chars, int start, int length) throws SAXException {
            if (textDepth > 0) XlsxStreaming.appendText(item, chars, start, length);
        }

        @Override
        public void endElement(String ns, String name, String qualified) throws SAXException {
            if (depth == phoneticDepth) phoneticDepth = -1;
            if (depth == textDepth) textDepth = -1;
            if (depth == itemDepth) {
                byte[] bytes = XlsxStreaming.unescapeExcel(item.toString()).getBytes(StandardCharsets.UTF_8);
                try {
                    index.writeLong(position);
                    values.writeInt(bytes.length);
                    values.write(bytes);
                } catch (IOException e) {
                    throw new SAXException(e);
                }
                position += Integer.BYTES + bytes.length;
                itemDepth = -1;
            }
            depth--;
        }
    }
}
