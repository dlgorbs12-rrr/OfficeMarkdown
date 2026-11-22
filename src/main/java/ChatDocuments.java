import java.awt.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.tree.*;

final class ChatDocuments extends JPanel {
    private record Location(Path converted, String section, PictureAssets.Reference picture, int page, String version) {}
    private record Entry(DocumentAgent.Claim claim) {
        public String toString() {
            var hit = claim.proof();
            return hit.picture() == null ? hit.section() : hit.section() + " · " + hit.picture().location();
        }
    }

    private final LinkedHashMap<Path, LinkedHashMap<Location,List<Entry>>> documents = new LinkedHashMap<>();
    private final JTextField filter = new JTextField();
    private final JLabel count = LibraryStyle.label("", 13, false, LibraryStyle.MUTED);
    private final JLabel empty = LibraryStyle.label("", 14, false, LibraryStyle.MUTED);
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JScrollPane scroll = new JScrollPane(tree);

    ChatDocuments(Consumer<DocumentAgent.Claim> open) {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        var header = LibraryStyle.panel(new BorderLayout(8, 10), 0);
        header.add(LibraryStyle.label("대화 문서", 19, true, LibraryStyle.INK));
        header.add(count, BorderLayout.EAST);
        filter.putClientProperty("JTextField.placeholderText", "문서명, 구역, 인용문 검색");
        filter.getAccessibleContext().setAccessibleName("대화 문서 검색");
        header.add(filter, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(32);
        tree.setFont(LibraryStyle.TYPE);
        tree.getAccessibleContext().setAccessibleName("문서별 미리보기");
        var renderer = new DefaultTreeCellRenderer();
        var icon = LibraryStyle.document("", 23, LibraryStyle.ACCENT);
        renderer.setClosedIcon(icon);
        renderer.setOpenIcon(icon);
        renderer.putClientProperty("html.disable", true);
        tree.setCellRenderer(renderer);
        tree.addTreeSelectionListener(e -> {
            var node = (DefaultMutableTreeNode)tree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof Entry entry) open.accept(entry.claim());
        });
        scroll.setBorder(null);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });
        refresh();
    }

    void append(List<DocumentAgent.Claim> claims) {
        for (var claim : claims) {
            var hit = claim.proof();
            var page = claim.page();
            var key = new Location(hit.md().toAbsolutePath().normalize(), hit.section(), hit.picture(),
                    page == null ? 0 : page.number(), page == null ? "" : page.version());
            var versions = documents.computeIfAbsent(hit.source().toAbsolutePath().normalize(), path -> new LinkedHashMap<>())
                    .computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
            var entry = new Entry(claim);
            if (!versions.contains(entry)) versions.add(entry);
        }
        refresh();
    }

    void clear() {
        documents.clear();
        filter.setText("");
        refresh();
    }

    void focusList() {
        tree.clearSelection();
        if (root.getChildCount() == 0) filter.requestFocusInWindow();
        else tree.requestFocusInWindow();
    }

    private void refresh() {
        var expanded = new java.util.HashSet<Path>();
        for (int i = 0; i < root.getChildCount(); i++) {
            var node = (DefaultMutableTreeNode)root.getChildAt(i);
            if (tree.isExpanded(new TreePath(node.getPath()))) expanded.add(((Group)node.getUserObject()).path());
        }
        root.removeAllChildren();
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        documents.forEach((path, entries) -> {
            String title = entries.values().iterator().next().getFirst().claim().proof().title();
            var node = new DefaultMutableTreeNode(new Group(path, title));
            boolean whole = matches(title + " " + path.getFileName(), needle);
            for (var citations : entries.values()) {
                for (var entry : citations.reversed()) {
                    var hit = entry.claim().proof();
                    if (whole || matches(entry + " " + hit.displayExcerpt() + " " + hit.excerpt(), needle)
                        || entry.claim().evidence().stream().anyMatch(e -> matches(e.quote(), needle))) {
                        node.add(new DefaultMutableTreeNode(entry, false));
                        break;
                    }
                }
            }
            if (!node.isLeaf()) root.add(node);
        });
        model.reload();
        for (int i = 0; i < root.getChildCount(); i++) {
            var node = (DefaultMutableTreeNode)root.getChildAt(i);
            if (!needle.isEmpty() || expanded.contains(((Group)node.getUserObject()).path()))
                tree.expandPath(new TreePath(node.getPath()));
        }
        int visible = root.getChildCount();
        count.setText((needle.isEmpty() ? "" : visible + " / ") + documents.size() + "개");
        remove(scroll);
        remove(empty);
        empty.setText(documents.isEmpty() ? "답변에 인용된 문서가 여기에 모입니다." : "일치하는 문서가 없습니다.");
        add(visible == 0 ? empty : scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private record Group(Path path, String title) {
        public String toString() { return title; }
    }

    private static boolean matches(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
