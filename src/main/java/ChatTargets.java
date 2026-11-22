import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** Checkboxes govern retrieval; the title opens the document independently of that selection. */
final class ChatTargets extends JPanel {
    record Doc(String md,String source,String title) {}
    private final List<Doc> rows=new ArrayList<>();
    private final LinkedHashSet<String> checked=new LinkedHashSet<>();
    private final Runnable changed;
    private final JTextField filter=new JTextField();
    private final JLabel status=LibraryStyle.label("",12,false,LibraryStyle.MUTED);
    private final AbstractTableModel model=new AbstractTableModel(){
        public int getRowCount(){return rows.size();}
        public int getColumnCount(){return 2;}
        public String getColumnName(int c){return c==0?"선택":"문서명";}
        public Class<?> getColumnClass(int c){return c==0?Boolean.class:String.class;}
        public boolean isCellEditable(int r,int c){return c==0;}
        public Object getValueAt(int r,int c){var doc=rows.get(r);return c==0?checked.contains(doc.md()):doc.title();}
        public void setValueAt(Object value,int r,int c){
            if(Boolean.TRUE.equals(value))checked.add(rows.get(r).md());else checked.remove(rows.get(r).md());
            fireTableCellUpdated(r,c);count();changed.run();
        }
    };
    private final JTable table=new JTable(model);
    private int total;
    private SwingWorker<List<Doc>,Void> loading;
    ChatTargets(Runnable changed,Consumer<Doc> preview){
        super(new BorderLayout(4,6)); this.changed=changed;
        setBackground(Color.WHITE);setBorder(BorderFactory.createEmptyBorder(8,0,0,0));
        filter.putClientProperty("JTextField.placeholderText","문서 이름 찾기");
        filter.putClientProperty("JTextField.showClearButton",true);
        filter.getAccessibleContext().setAccessibleName("질문 범위 문서 이름 찾기");
        table.getColumnModel().getColumn(0).setMaxWidth(44);
        table.setRowHeight(46);table.setShowGrid(false);table.setShowHorizontalLines(true);
        table.setGridColor(LibraryStyle.SOFT_LINE);table.setIntercellSpacing(new Dimension(0,1));
        ((javax.swing.table.DefaultTableCellRenderer)table.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(SwingConstants.LEFT);
        // Same-named files are told apart by the folder shown under the title.
        table.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer(){
            public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int r,int c){
                super.getTableCellRendererComponent(t,value,selected,false,r,c);
                var doc=rows.get(t.convertRowIndexToModel(r));
                setText("<html><div style='color:#25251f'>"+LibraryStyle.escape(doc.title())+"</div>"
                        +"<div style='font-size:9pt;color:#706e62'>"+LibraryStyle.escape(folder(doc.source()))+"</div></html>");
                setToolTipText(doc.source());
                setVerticalAlignment(SwingConstants.CENTER);
                return this;
            }
        });
        var sorter=new TableRowSorter<>(model);
        sorter.setSortable(0,false);table.setRowSorter(sorter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            void apply(){String text=filter.getText().strip();sorter.setRowFilter(text.isEmpty()?null:RowFilter.regexFilter("(?iu)"+java.util.regex.Pattern.quote(text),1));}
            public void insertUpdate(javax.swing.event.DocumentEvent e){apply();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){apply();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){apply();}
        });
        table.addMouseListener(new java.awt.event.MouseAdapter(){
            public void mouseClicked(java.awt.event.MouseEvent e){
                int r=table.rowAtPoint(e.getPoint()),c=table.columnAtPoint(e.getPoint());
                if(r>=0 && c>=0 && table.convertColumnIndexToModel(c)==1)
                    preview.accept(rows.get(table.convertRowIndexToModel(r)));
            }
        });
        add(filter,BorderLayout.NORTH);add(new JScrollPane(table));add(status,BorderLayout.SOUTH);
    }
    List<String> selection(){return List.copyOf(checked);}
    void select(List<String> notes){checked.clear();checked.addAll(notes);filter.setText("");model.fireTableDataChanged();count();}
    private void count(){status.setText(total==0?"문서 없음":"선택 "+checked.size()+" / 문서 "+total+"개");}
    static String folder(String source){
        if(source==null||source.isBlank())return "";
        try{
            var parent=Path.of(source).getParent();
            return parent==null?"":OfficeMarkdown.breadcrumb(Path.of(source));
        }catch(RuntimeException e){return "";}
    }
    void refresh(){
        if(loading!=null)loading.cancel(true);
        loading=new SwingWorker<>(){
            protected List<Doc> doInBackground() throws Exception{
                return SearchIndex.readIndex(db->{
                    var docs=new ArrayList<Doc>();
                    try(var st=db.createStatement();var rs=st.executeQuery("SELECT md,source,title FROM visible_docs ORDER BY title,md")){
                        while(rs.next())docs.add(new Doc(rs.getString(1),rs.getString(2),rs.getString(3)));
                    }
                    return docs;
                });
            }
            protected void done(){
                if(this!=loading)return;
                try{var data=get();rows.clear();
                    for(var doc:data)if(checked.contains(doc.md()))rows.add(doc);
                    for(var doc:data)if(!checked.contains(doc.md()))rows.add(doc);total=data.size();model.fireTableDataChanged();count();}
                catch(Exception ex){status.setText("검색 대상 목록을 읽을 수 없습니다.");}
            }
        };loading.execute();
    }
}