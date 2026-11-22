import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** A reply owns its selection so earlier questions can be revisited. */
final class FoundDocuments extends JPanel {
    private final List<Library.Hit> docs;
    private final Set<Path> checked=new LinkedHashSet<>();
    private final JButton search=new JButton();
    private final JLabel status=new JLabel();
    private final JTable table;
    private boolean busy;
    private Consumer<List<String>> submit=notes->{};
    void onSearch(Consumer<List<String>> action){submit=action;}
    FoundDocuments(List<Library.Hit> candidates){
        super(new BorderLayout(0,6));
        var unique=new LinkedHashMap<Path,Library.Hit>();
        for(var hit:candidates)unique.putIfAbsent(hit.md(),hit);
        docs=List.copyOf(unique.values());
        var model=new AbstractTableModel(){
            public int getRowCount(){return docs.size();}
            public int getColumnCount(){return 2;}
            public String getColumnName(int c){return c==0?"선택":"문서명";}
            public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}
            public boolean isCellEditable(int r,int c){return !busy && c==0 && eligible(r);}
            public Object getValueAt(int r,int c){var hit=docs.get(r);return c==0?checked.contains(hit.md()):hit.title();}
            public void setValueAt(Object value,int r,int c){
                if(!isCellEditable(r,c))return;
                if(Boolean.TRUE.equals(value))checked.add(docs.get(r).md());else checked.remove(docs.get(r).md());
                fireTableCellUpdated(r,c);updateAction();
            }
        };
        table=new JTable(model);
        table.setRowHeight(28);table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setMaxWidth(45);
        table.getAccessibleContext().setAccessibleName("답변에서 찾은 문서");
        table.setDefaultRenderer(Boolean.class,new javax.swing.table.DefaultTableCellRenderer(){
            final JCheckBox box=new JCheckBox();
            public java.awt.Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){
                box.setHorizontalAlignment(SwingConstants.CENTER);box.setSelected(Boolean.TRUE.equals(value));
                box.setEnabled(model.isCellEditable(row,column));
                box.setBackground(selected?t.getSelectionBackground():t.getBackground());return box;
            }
        });
        var footer=new JPanel(new BorderLayout(0,5));
        footer.add(status,BorderLayout.NORTH);footer.add(search,BorderLayout.SOUTH);
        add(new JScrollPane(table));add(footer,BorderLayout.SOUTH);
        status.setText(docs.isEmpty()?"질문을 보내면 찾은 문서가 표시됩니다.":"슬라이드 검색은 PPTX 문서를 선택하세요.");
        search.addActionListener(e->{if(!checked.isEmpty() && !busy)this.submit.accept(checked.stream().map(Path::toString).toList());});
        updateAction();
    }
    private boolean eligible(int r){return docs.get(r).source().toString().toLowerCase(Locale.ROOT).endsWith(".pptx");}
    void setStatus(String text){status.setText(text);status.setToolTipText(text);}
    void busy(boolean value){busy=value;table.repaint();updateAction();}
    private void updateAction(){
        search.setText("선택한 문서에서 슬라이드 찾기 ("+checked.size()+")");
        search.setEnabled(!busy && !checked.isEmpty());
    }
}