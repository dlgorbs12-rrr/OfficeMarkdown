import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class MarkdownWindow {
    /** Converted notes end in "-" plus a 20-digit hex hash; the reader only needs the document name. */
    static String title(Path file, Library.Hit hit) {
        if (hit != null && !hit.title().isBlank()) return hit.title();
        return file.getFileName().toString().replaceFirst("-[0-9a-f]{20}\\.md$", "");
    }

    static void open(Window owner, Path file, Library.Hit hit) {
        var dialog = new JDialog(owner, title(file, hit) + " — Markdown 뷰어");
        var reader = new JEditorPane("text/html", "문서를 읽는 중…");
        reader.setEditable(false);
        reader.setBackground(Color.WHITE);
        reader.setBorder(new EmptyBorder(8, 12, 8, 12));
        reader.getAccessibleContext().setAccessibleName("Markdown 문서 본문");
        var chapters = new JComboBox<MarkdownReader.Page>();
        chapters.getAccessibleContext().setAccessibleName("문서 구역");
        chapters.setMaximumRowCount(16);

        var previous = new JButton("‹"); var next = new JButton("›");
        previous.setToolTipText("이전 구역"); next.setToolTipText("다음 구역");
        previous.addActionListener(e -> { if (chapters.getSelectedIndex() > 0) chapters.setSelectedIndex(chapters.getSelectedIndex() - 1); });
        next.addActionListener(e -> { if (chapters.getSelectedIndex() < chapters.getItemCount() - 1) chapters.setSelectedIndex(chapters.getSelectedIndex() + 1); });
        for (JButton b : List.of(previous, next)) { LibraryStyle.button(b, false); b.setMargin(new Insets(3, 9, 3, 9)); }

        var toolbar = LibraryStyle.panel(new BorderLayout(8, 0), 0);
        toolbar.setOpaque(true);
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LibraryStyle.SOFT_LINE), new EmptyBorder(8, 12, 8, 12)));
        var navigation = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT, 4, 0), 0);
        navigation.add(previous); navigation.add(chapters); navigation.add(next);
        toolbar.add(navigation, BorderLayout.WEST);
        if (hit != null) {
            var actions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0), 0);
            var locate = new JButton("파일 위치"); var original = new JButton("원본 열기");
            LibraryStyle.button(locate, false); LibraryStyle.button(original, true);
            locate.addActionListener(e -> reveal(dialog, hit.source(), true));
            original.addActionListener(e -> reveal(dialog, hit.source(), false));
            actions.add(locate); actions.add(original);
            toolbar.add(actions, BorderLayout.EAST);
        }

        var scroll = new JScrollPane(reader);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        var content = new JPanel(new BorderLayout());
        content.setBackground(Color.WHITE);
        content.add(toolbar, BorderLayout.NORTH);
        content.add(scroll);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(960, 720);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);

        new SwingWorker<List<MarkdownReader.Page>, Void>() {
            protected List<MarkdownReader.Page> doInBackground() throws Exception {
                return MarkdownReader.read(file);
            }
            protected void done() {
                if (!dialog.isDisplayable()) return;
                try {
                    var pages = get();
                    chapters.setModel(new DefaultComboBoxModel<>(pages.toArray(MarkdownReader.Page[]::new)));
                    chapters.addActionListener(event -> {
                        var page = (MarkdownReader.Page) chapters.getSelectedItem();
                        previous.setEnabled(chapters.getSelectedIndex() > 0);
                        next.setEnabled(chapters.getSelectedIndex() < chapters.getItemCount() - 1);
                        if (page == null) return;
                        new SwingWorker<String, Void>() {
                            protected String doInBackground() {
                                return MarkdownReader.html(page, hit == null ? null : hit.picture(), file);
                            }
                            protected void done() {
                                if (!dialog.isDisplayable() || chapters.getSelectedItem() != page) return;
                                try {
                                    reader.setText(get());
                                    reader.setCaretPosition(0);
                                    if (hit != null && hit.picture() != null) reader.scrollToReference(MarkdownReader.PICTURE_ANCHOR);
                                } catch (Exception ex) { reader.setText("이 구역을 표시할 수 없습니다."); }
                            }
                        }.execute();
                    });
                    chapters.setSelectedIndex(hit == null ? 0 : MarkdownReader.locate(pages, hit));
                } catch (Exception ex) { reader.setText("Markdown 파일을 읽을 수 없습니다."); }
            }
        }.execute();
    }

    private static void reveal(Component parent, Path source, boolean folder) {
        // Same checks as the main window's actions.
        try {
            if (!java.nio.file.Files.isRegularFile(source)) throw new java.io.IOException("파일을 찾을 수 없습니다.\n" + source);
            if (folder) new ProcessBuilder("explorer.exe", "/select,", source.toString()).start();
            else if (Library.supported(source)) Desktop.getDesktop().open(source.toFile());
            else throw new java.io.IOException("지원하는 원본 문서를 선택하세요.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "문서를 열 수 없습니다.\n" + source + "\n" + e.getLocalizedMessage(),
                    "문서 열기", JOptionPane.WARNING_MESSAGE);
        }
    }
}
