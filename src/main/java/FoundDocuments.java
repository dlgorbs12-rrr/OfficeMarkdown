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
    private final Set<Path> presentations=new HashSet<>();
    private final JButton ask=new JButton("질문 범위로 사용");
    private Consumer<List<String>> useScope=notes->{};
    void onScope(Consumer<List<String>> action){useScope=action;}
    private final JButton search=new JButton();
    private final JLabel status=new JLabel();
    private boolean busy;
    private Consumer<List<String>> submit=notes->{};
    void onSearch(Consumer<List<String>> action){submit=action;}

    FoundDocuments(List<Library.Hit> candidates){
        super(new BorderLayout(0,6));
        var rows=new JPanel();
        rows.setLayout(new BoxLayout(rows,BoxLayout.Y_AXIS));
        for(var hit:candidates){
            if(choices.containsKey(hit.md()))continue;
            if(hit.source().toString().toLowerCase(Locale.ROOT).endsWith(".pptx"))presentations.add(hit.md());
            var box=new JCheckBox();
            box.putClientProperty("html.disable",true);
            box.setText(hit.title());
            box.setToolTipText(hit.source().toString());
            box.addItemListener(e->{
                if(box.isSelected())checked.add(hit.md());else checked.remove(hit.md());
                updateAction();
            });
            choices.put(hit.md(),box);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,Math.max(28,box.getPreferredSize().height)));
            rows.add(box);
        }
        var content=new JPanel(new BorderLayout());
        content.add(rows,BorderLayout.NORTH);
        var footer=new JPanel(new BorderLayout(0,5));
        footer.add(status,BorderLayout.NORTH);var actions=new JPanel(new java.awt.GridLayout(0,1,0,3));
        actions.add(ask);actions.add(search);footer.add(actions,BorderLayout.SOUTH);
        add(new JScrollPane(content));add(footer,BorderLayout.SOUTH);
        status.setText(choices.isEmpty()?"질문을 보내면 찾은 문서가 표시됩니다.":"슬라이드 검색에는 선택한 PPTX만 사용합니다.");
        ask.addActionListener(e->useScope.accept(checked.stream().map(Path::toString).toList()));
        search.addActionListener(e->submit.accept(checked.stream().filter(presentations::contains).map(Path::toString).toList()));
        updateAction();
    }
    void setStatus(String text){status.setText(text);status.setToolTipText(text);}
    void busy(boolean value){
        busy=value;
        choices.values().forEach(box->box.setEnabled(!value));
        updateAction();
    }
    private void updateAction(){
        long slides=checked.stream().filter(presentations::contains).count();
        ask.setText("질문 범위로 사용 · 문서 "+checked.size()+"개");
        ask.setEnabled(!busy && !checked.isEmpty());
        search.setText("슬라이드 찾기 · PPTX "+slides+"개");
        search.setEnabled(!busy && slides>0);
    }
}