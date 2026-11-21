import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;

final class MarkdownWindow {
    static void open(Window owner, Path file, Library.Hit hit) {
        var dialog = new JDialog(owner, file.getFileName() + " · Markdown 뷰어");
        var reader = new JEditorPane("text/html", "문서를 읽는 중…");
        reader.setEditable(false);
        var chapters = new JComboBox<MarkdownReader.Page>();
        chapters.getAccessibleContext().setAccessibleName("문서 구역");
        dialog.add(chapters, BorderLayout.NORTH);
        dialog.add(new JScrollPane(reader));
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setSize(900, 680);
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
}
