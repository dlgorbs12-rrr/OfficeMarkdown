import java.nio.file.*;
import java.util.Comparator;
import org.junit.jupiter.api.extension.*;

/** Each test gets an independent user index, including searches on background threads. */
public class IsolatedSearchIndex implements BeforeEachCallback, AfterEachCallback {
    private static final String PROPERTY = "officemd.dataDir";
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(IsolatedSearchIndex.class);

    public void beforeEach(ExtensionContext context) throws Exception {
        var store = context.getStore(NS);
        Path directory = Files.createTempDirectory("office-test-index-");
        store.put("directory", directory);
        String previous = System.setProperty(PROPERTY, directory.toString());
        if (previous != null) store.put("previous", previous);
    }

    public void afterEach(ExtensionContext context) throws Exception {
        var store = context.getStore(NS);
        String previous = store.get("previous", String.class);
        if (previous == null) System.clearProperty(PROPERTY);
        else System.setProperty(PROPERTY, previous);
        Path directory = store.get("directory", Path.class);
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
