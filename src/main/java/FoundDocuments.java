import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.swing.*;

/** A reply owns its controls and selection so earlier questions can be revisited. */
final class FoundDocuments extends JPanel {
    private static final String SELECTION_HINT="슬라이드 검색에는 선택한 PPTX만 사용합니다.";
    private final Set<Path> checked=new LinkedHashSet<>();
    private final Map<Path,JCheckBox> choices=new LinkedHashMap<>();
    private final Set<Path> presentations=new HashSet<>();
    private final JButton ask=new JButton("질문 범위로 사용");
    private Consumer<List<String>> useScope=notes->{};
    void onScope(Consumer<List<String>> action){useScope=action;}
    private final JButton search=new JButton();
    private final JLabel status=new JLabel();
    private final JPanel outcomes=new JPanel();
    private JLabel emptyHint;
    private boolean busy;
    private Consumer<List<String>> submit=notes->{};
    void onSearch(Consumer<List<String>> action){submit=action;}

    FoundDocuments(List<Library.Hit> candidates){
        super(new BorderLayout(0,6));
        setBackground(Color.WHITE);
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
                clearDeckResults();setStatus(SELECTION_HINT);updateAction();
            });
            choices.put(hit.md(),box);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,Math.max(28,box.getPreferredSize().height)));
            rows.add(box);
        }
        var content=new JPanel(new BorderLayout());
        outcomes.setLayout(new BoxLayout(outcomes,BoxLayout.Y_AXIS));
        content.add(rows,BorderLayout.NORTH);content.add(outcomes,BorderLayout.CENTER);
        var footer=new JPanel(new BorderLayout(0,5));
        // Default panels paint the LAF grey; keep the whole tab on the white sidebar card.
        for(var panel:List.of(rows,content,outcomes,footer))panel.setOpaque(false);
        footer.add(status,BorderLayout.NORTH);var actions=new JPanel(new java.awt.GridLayout(0,1,0,3));
        actions.setOpaque(false);
        actions.add(ask);actions.add(search);footer.add(actions,BorderLayout.SOUTH);
        if(choices.isEmpty()){
            // Nothing found yet: a centered hint instead of disabled actions pinned to the bottom.
            var empty=new JPanel(new java.awt.GridBagLayout());
            empty.setOpaque(false);
            var hint=emptyHint=new JLabel("<html><div style='text-align:center'>질문을 보내면 답변에 쓰인 문서가 여기에 표시됩니다.<br>"
                    +"골라서 질문 범위로 쓰거나 슬라이드를 더 찾을 수 있습니다.</div></html>",SwingConstants.CENTER);
            hint.setFont(LibraryStyle.TYPE.deriveFont(13f));
            hint.setForeground(LibraryStyle.MUTED);
            empty.add(hint);
            add(empty);
        }else{
            var scroll=new JScrollPane(content);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(Color.WHITE);
            add(scroll);add(footer,BorderLayout.SOUTH);
            LibraryStyle.button(ask,false);LibraryStyle.button(search,true);
            status.setFont(LibraryStyle.TYPE.deriveFont(12f));
            status.setForeground(LibraryStyle.MUTED);
        }
        status.setText(choices.isEmpty()?"질문을 보내면 찾은 문서가 표시됩니다.":SELECTION_HINT);
        ask.addActionListener(e->useScope.accept(checked.stream().map(Path::toString).toList()));
        search.addActionListener(e->submit.accept(checked.stream().filter(presentations::contains).map(Path::toString).toList()));
        updateAction();
    }
    private void clearDeckResults(){outcomes.removeAll();outcomes.revalidate();outcomes.repaint();}
    void showDeckResults(List<McpSearch.DeckResult> decks,Consumer<DocumentAgent.Claim> open,Consumer<List<ReferenceIndex.Page>> inspect){
        clearDeckResults();
        for(var deck:decks){
            var label=new JLabel();label.putClientProperty("html.disable",true);
            label.setText(choices.get(Path.of(deck.note())).getText()+" · "+deck.outcome());
            label.setToolTipText(label.getText());outcomes.add(label);
            if(deck.slides().isEmpty() && !deck.candidates().isEmpty()){
                var manual=new JButton("원본 후보 "+deck.candidates().size()+"장 직접 보기");
                manual.addActionListener(event->inspect.accept(deck.candidates()));outcomes.add(manual);
            }
            for(var slide:deck.slides()){
                var button=new JButton(slide.proof().section()+" 열기");
                button.addActionListener(event->open.accept(slide));outcomes.add(button);
            }
        }
        outcomes.revalidate();outcomes.repaint();
    }
    void setStatus(String text){
        status.setText(text);status.setToolTipText(text);
        // The empty panel hides the status line, so its centered hint carries the message instead.
        if(emptyHint!=null && !text.equals("질문을 보내면 찾은 문서가 표시됩니다."))emptyHint.setText(text);
    }
    void busy(boolean value){
        busy=value;
        if(value)clearDeckResults();
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