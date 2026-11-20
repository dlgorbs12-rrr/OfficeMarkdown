import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

final class LatestVersionDialog {
    private record Difference(Path skipped, Path latest) {}

    private LatestVersionDialog() {}

    static boolean confirm(Component parent, LatestVersionSelection.Preview preview) throws IOException {
        if (SwingUtilities.isEventDispatchThread()) return show(parent, preview);
        var dialog = new FutureTask<>(() -> show(parent, preview));
        SwingUtilities.invokeLater(dialog);
        try {
            return dialog.get();
        } catch (InterruptedException e) {
            dialog.cancel(false);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("최신본 미리보기가 중단되었습니다.");
        } catch (ExecutionException e) {
            throw new IOException("최신본 미리보기를 열지 못했습니다.", e.getCause());
        }
    }

    private static boolean show(Component parent, LatestVersionSelection.Preview preview) {
        String summary = "발견 " + preview.discovered() + "개 · 변환 후보 " + preview.selected()
                + "개 · 이전 파일 제외 " + preview.latestSkipped() + "개";
        if (preview.ambiguousGroups() > 0) {
            summary += " · 판단이 모호한 " + preview.ambiguousGroups() + "개 묶음 전체 포함";
        }
        var panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(summary), BorderLayout.NORTH);
        if (!preview.choices().isEmpty()) panel.add(differences(preview), BorderLayout.CENTER);

        Object[] options = {"변환 시작", "취소"};
        return JOptionPane.showOptionDialog(parent, panel, "최신본 미리보기", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]) == JOptionPane.OK_OPTION;
    }

    private static JScrollPane differences(LatestVersionSelection.Preview preview) {
        var rows = new ArrayList<Difference>();
        for (var choice : preview.choices()) {
            for (Path skipped : choice.skipped()) rows.add(new Difference(skipped, choice.latest()));
        }
        rows.sort(Comparator.comparing(Difference::latest).thenComparing(Difference::skipped));
        var table = new JTable(new AbstractTableModel() {
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return 2; }
            @Override public String getColumnName(int column) { return column == 0 ? "변환에서 제외" : "최신본"; }
            @Override public Object getValueAt(int row, int column) {
                return column == 0 ? rows.get(row).skipped() : rows.get(row).latest();
            }
        });
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                super.setValue(value);
                setToolTipText(value.toString());
            }
        });
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(780, 260));
        return new JScrollPane(table);
    }
}
