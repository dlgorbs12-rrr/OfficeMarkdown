import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;

class ChatDocumentsTest {
    @Test void groupsAcrossRepliesSearchesEarlierQuotesAndResets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var opened = new ArrayList<DocumentAgent.Claim>();
            var panel = new ChatDocuments(opened::add);
            var first = claim("a", "구역 1", "초기 인용문");
            var second = claim("a", "구역 2", "다음 인용문");
            panel.append(List.of(first, second));
            panel.append(List.of(first, claim("a", "구역 1", "최신 인용문"), claim("b", "구역 1", "다른 문서")));
            var tree = find(panel, JTree.class);
            var root = (DefaultMutableTreeNode)tree.getModel().getRoot();
            assertEquals(2, root.getChildCount());
            assertEquals(2, root.getChildAt(0).getChildCount());
            var filter = find(panel, JTextField.class);
            filter.setText("초기");
            assertEquals(1, root.getChildCount());
            assertEquals(1, root.getChildAt(0).getChildCount());
            tree.setSelectionRow(1);
            assertEquals(List.of(first), opened);
            filter.setText("없는 구절");
            assertEquals(0, root.getChildCount());
            filter.setText("");
            assertEquals(2, root.getChildCount());
            panel.clear();
            assertEquals(0, root.getChildCount());
        });
    }

    private static DocumentAgent.Claim claim(String folder, String section, String excerpt) {
        var dir = Path.of(folder);
        return new DocumentAgent.Claim("E1", new Library.Hit(dir.resolve("보고서.md"),
                dir.resolve("보고서.pdf"), "보고서", section, excerpt));
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (var child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                var found = find(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
