import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** A reply owns its controls and selection so earlier questions can be revisited. */
final class FoundDocuments extends JPanel {
    private final Set<Path> checked=new LinkedHashSet<>();
    private final Map<Path,JCheckBox> choices=new LinkedHashMap<>();
    private final JButton search=new JButton();
    private final JLabel status=new JLabel();
    private boolean busy;
    private Consumer<List<String>> submit=notes->{};
    void onSearch(Consumer<List<String>> action){submit=action;}

    FoundDocuments(List<Library.Hit> candidates){
        super(new BorderLayout(0,6));
        var rows=new JPanel();
        rows.setLayout(new BoxLayout(rows,BoxLayout.Y_AXIS));
        var seen=new HashSet<Path>();
        for(var hit:candidates){
            if(!seen.add(hit.md()))continue;
            JComponent row;
            if(hit.source().toString().toLowerCase(Locale.ROOT).endsWith(".pptx")){
                var box=new JCheckBox();
                box.putClientProperty("html.disable",true);
                box.setText(hit.title());
                box.addItemListener(e->{
                    if(box.isSelected())checked.add(hit.md());else checked.remove(hit.md());
                    updateAction();
                });
                choices.put(hit.md(),box);
                row=box;
            }else{
                var label=new JLabel();
                label.putClientProperty("html.disable",true);
                label.setText(hit.title());
                label.setToolTipText("이 파일 형식은 슬라이드 검색을 지원하지 않습니다.");
                row=label;
            }
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,Math.max(28,row.getPreferredSize().height)));
            rows.add(row);
        }
        var content=new JPanel(new BorderLayout());
        content.add(rows,BorderLayout.NORTH);
        var footer=new JPanel(new BorderLayout(0,5));
        footer.add(status,BorderLayout.NORTH);footer.add(search,BorderLayout.SOUTH);
        add(new JScrollPane(content));add(footer,BorderLayout.SOUTH);
        status.setText(seen.isEmpty()?"질문을 보내면 찾은 문서가 표시됩니다.":choices.isEmpty()
                ?"이번 답변에는 슬라이드를 찾을 PPTX 문서가 없습니다.":"체크칸이나 PPTX 문서명을 눌러 선택하세요.");
        search.addActionListener(e->{if(!checked.isEmpty() && !busy)submit.accept(checked.stream().map(Path::toString).toList());});
        updateAction();
    }
    void setStatus(String text){status.setText(text);status.setToolTipText(text);}
    void busy(boolean value){
        busy=value;
        choices.values().forEach(box->box.setEnabled(!value));
        updateAction();
    }
    private void updateAction(){
        search.setText("선택한 문서에서 슬라이드 찾기 ("+checked.size()+")");
        search.setEnabled(!busy && !checked.isEmpty());
    }
}