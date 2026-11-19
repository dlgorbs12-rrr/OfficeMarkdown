import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import org.jsoup.nodes.Entities;

final class AgentDialog extends JDialog {
    private final JComboBox<DocumentAgent.Provider> providers=new JComboBox<>(DocumentAgent.Provider.values());
    private final JTextArea question=new JTextArea(2,30);
    private final JComboBox<DocumentAgent.Effort> effort=new JComboBox<>();
    private final JComboBox<String> models=new JComboBox<>();
    private final JButton settings=new JButton("에이전트 설정");
    private static final String DEFAULT_MODEL="CLI 기본 모델";
    private boolean loadingEffort;
    private final JButton ask=new JButton("질문하기"),stop=new JButton("중단");
    private final JEditorPane answer=new JEditorPane("text/html","");
    private final JLabel state=new JLabel("질문과 관련 발췌문을 선택한 에이전트로 보냅니다.");
    private final CardLayout resultLayout=new CardLayout();
    private final JPanel resultPages=new JPanel(resultLayout);
    private final JLabel loadingText=LibraryStyle.label("",14,true,LibraryStyle.MUTED);
    private final JProgressBar progress=new JProgressBar();
    private final Path library;
    private List<DocumentAgent.Claim> claims=List.of();
    private SwingWorker<List<DocumentAgent.Claim>,String> task;

    AgentDialog(JFrame parent,Path library,Consumer<Library.Hit> preview,Consumer<Library.Hit> openOriginal){
        super(parent,"문서에 질문",false);
        this.library=library;
        providers.setSelectedItem(AgentConnection.preferred());
        JPanel body=LibraryStyle.panel(new BorderLayout(0,12),18);
        body.setOpaque(true);body.setBackground(LibraryStyle.CANVAS);
        JPanel top=LibraryStyle.panel(new BorderLayout(0,12),0);
        JPanel heading=LibraryStyle.panel(new BorderLayout(),0);
        JLabel title=LibraryStyle.label("문서에 질문",23,true,LibraryStyle.INK);
        title.setIcon(LibraryStyle.document("",36,LibraryStyle.PURPLE));title.setIconTextGap(10);
        heading.add(title);heading.add(settings,BorderLayout.EAST);
        LibraryStyle.button(settings,false);top.add(heading,BorderLayout.NORTH);
        JPanel controls=LibraryStyle.card(new GridLayout(1,3,14,0));
        models.setEditable(true);
        models.setToolTipText("목록에서 선택하거나 CLI에서 사용하는 모델 ID를 입력하세요.");
        controls.add(field("에이전트",providers));controls.add(field("모델 선택",models));
        controls.add(field("추론 깊이",effort));top.add(controls);
        body.add(top,BorderLayout.NORTH);
        effort.setToolTipText("검색어 추출과 답변 작성에 적용합니다. 지원 단계는 현재 CLI 모델에 따라 다릅니다.");
        providers.addActionListener(e->loadEffort());
        loadEffort();
        models.addActionListener(e->{
            if(!loadingEffort){
                String id=selectedModel();
                try{DocumentAgent.modelArguments(id); AgentConnection.model((DocumentAgent.Provider)providers.getSelectedItem(),id);}
                catch(IllegalArgumentException ignored){/* Display validation when submitting. */}
            }
        });
        effort.addActionListener(e->{
            if(!loadingEffort && effort.getSelectedItem() instanceof DocumentAgent.Effort choice)
                AgentConnection.effort((DocumentAgent.Provider)providers.getSelectedItem(),choice);
        });
        settings.addActionListener(e->{
            var chosen=AgentSettingsDialog.open(this,(DocumentAgent.Provider)providers.getSelectedItem());
            if(chosen!=null)selectDefault();
        });
        JPanel result=LibraryStyle.card(new BorderLayout(0,8));
        result.add(LibraryStyle.label("답변과 출처",16,true,LibraryStyle.INK),BorderLayout.NORTH);
        answer.setEditable(false);answer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
        answer.setFont(LibraryStyle.TYPE);
        answer.setText("<html><body style='color:#645f73'><p>질문을 입력하면 관련 문서를 찾아 답변과 인용문을 함께 보여줍니다.</p></body></html>");
        JScrollPane results=new JScrollPane(answer);results.setBorder(BorderFactory.createEmptyBorder());
        JPanel loading=LibraryStyle.panel(new GridBagLayout(),0);
        loading.setOpaque(true);loading.setBackground(Color.WHITE);
        JPanel indicator=LibraryStyle.panel(new BorderLayout(0,10),0);
        loadingText.setHorizontalAlignment(SwingConstants.CENTER);
        indicator.add(loadingText,BorderLayout.NORTH);indicator.add(progress);loading.add(indicator);
        resultPages.add(results,"answer");resultPages.add(loading,"loading");
        result.add(resultPages);body.add(result);
        answer.addHyperlinkListener(e->{
            if(e.getEventType()!=HyperlinkEvent.EventType.ACTIVATED) return;
            String link=e.getDescription();
            for(int i=0;i<claims.size();i++){
                if(("#source-"+i).equals(link)){openOriginal.accept(claims.get(i).proof());return;}
                if(("#preview-"+i).equals(link)){
                    preview.accept(claims.get(i).proof());setVisible(false);parent.toFront();return;
                }
            }
        });
        JPanel composer=LibraryStyle.card(new BorderLayout(0,8));
        composer.add(field("질문",question),BorderLayout.CENTER);
        question.setLineWrap(true);question.setWrapStyleWord(true);question.setFont(LibraryStyle.TYPE);
        question.setMargin(new Insets(6,8,6,8));
        question.setToolTipText("예: AD 어댑터 설정 절차를 알려줘");
        JPanel footer=LibraryStyle.panel(new BorderLayout(6,0),0);
        state.setFont(LibraryStyle.TYPE.deriveFont(12f));state.setForeground(LibraryStyle.MUTED);
        footer.add(state);
        JPanel actions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        actions.add(LibraryStyle.label("Ctrl+Enter 전송",12,false,LibraryStyle.MUTED));actions.add(stop);actions.add(ask);
        footer.add(actions,BorderLayout.EAST);composer.add(footer,BorderLayout.SOUTH);
        body.add(composer,BorderLayout.SOUTH);setContentPane(body);
        stop.setVisible(false);stop.addActionListener(e->{if(task!=null)task.cancel(true);});
        ask.addActionListener(e->runQuestion());
        question.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"),"submit");
        question.getActionMap().put("submit",new AbstractAction(){
            public void actionPerformed(java.awt.event.ActionEvent e){if(ask.isEnabled())runQuestion();}
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter(){
            public void windowClosed(java.awt.event.WindowEvent e){if(task!=null)task.cancel(true);}
        });
        LibraryStyle.button(ask,true);setMinimumSize(new Dimension(680,480));setSize(860,620);setLocationRelativeTo(parent);
    }

    private JPanel field(String name,JComponent control){
        JPanel field=LibraryStyle.panel(new BorderLayout(0,5),0);
        JLabel label=LibraryStyle.label(name,13,true,LibraryStyle.MUTED);
        label.setLabelFor(control);field.add(label,BorderLayout.NORTH);
        field.add(control==question?new JScrollPane(question):control);
        return field;
    }
    private String selectedModel(){
        String value=java.util.Objects.toString(models.getEditor().getItem(),"").strip();
        return value.equals(DEFAULT_MODEL)?"":value;
    }

    void selectDefault(){if(ask.isEnabled())providers.setSelectedItem(AgentConnection.preferred());}

    private void loadEffort(){
        var provider=(DocumentAgent.Provider)providers.getSelectedItem();
        loadingEffort=true;
        try{
            DefaultComboBoxModel<String> list=new DefaultComboBoxModel<>();
            list.addElement(DEFAULT_MODEL);AgentConnection.models(provider).forEach(list::addElement);
            models.setModel(list);
            String saved=AgentConnection.model(provider);
            models.setSelectedItem(saved.isEmpty()?DEFAULT_MODEL:saved);
            effort.setModel(new DefaultComboBoxModel<>(DocumentAgent.Effort.choices(provider)));
            effort.setSelectedItem(AgentConnection.effort(provider));
        }finally{loadingEffort=false;}
    }

    private void runQuestion(){
        String text=question.getText().strip();if(text.isEmpty()){state.setText("질문을 입력하세요.");question.requestFocusInWindow();return;}
        var provider=(DocumentAgent.Provider)providers.getSelectedItem();
        String model=selectedModel();
        try{DocumentAgent.modelArguments(model);}catch(IllegalArgumentException e){state.setText(e.getMessage());models.requestFocusInWindow();return;}
        AgentConnection.model(provider,model);
        try{provider.executable();}catch(java.io.IOException e){
            state.setText("에이전트 설정에서 CLI를 먼저 연결하세요.");
            if(AgentSettingsDialog.open(this,provider)!=null)selectDefault();
            return;
        }
        var choice=(DocumentAgent.Effort)effort.getSelectedItem();
        question.setText("");
        claims=List.of();answer.setText("");
        loadingText.setText("문서를 찾는 중…");progress.setIndeterminate(true);
        resultLayout.show(resultPages,"loading");
        ask.setEnabled(false);stop.setVisible(true);models.setEnabled(false);settings.setEnabled(false);
        state.setText("질문을 보냈습니다.");providers.setEnabled(false);effort.setEnabled(false);
        task=new SwingWorker<>(){
            protected List<DocumentAgent.Claim> doInBackground() throws Exception{
                return DocumentAgent.answer(library,text,p->DocumentAgent.invoke(provider,model,choice,p),this::publish);
            }
            protected void process(List<String> messages){if(task==this && !isDone())loadingText.setText(messages.getLast());}
            protected void done(){
                try{
                    claims=get();StringBuilder html=new StringBuilder("<html><body style='margin:12px'>");
                    for(int i=0;i<claims.size();i++){
                        var c=claims.get(i);var proof=c.proof();
                        html.append("<p><b>").append(Entities.escape(c.answer())).append("</b></p><blockquote>")
                            .append(Entities.escape(proof.excerpt())).append("</blockquote><p><a href='#source-").append(i)
                            .append("'>원본 열기 · ").append(Entities.escape(proof.title())).append("</a>　<a href='#preview-")
                            .append(i).append("'>인용 위치 보기 · ").append(Entities.escape(proof.section())).append("</a></p><hr>");
                    }
                    if(claims.isEmpty())html.append("<p>변환된 문서에서 답변을 뒷받침하는 근거를 확인하지 못했습니다.</p>");
                    answer.setText(html.append("</body></html>").toString());answer.setCaretPosition(0);
                    state.setText(provider+" · "+(model.isEmpty()?DEFAULT_MODEL:model)+" · "+choice+" · 확인된 인용 "+claims.size()+"건");
                }catch(java.util.concurrent.CancellationException e){
                    state.setText("질문 중단");answer.setText("질문을 중단했습니다.");
                    if(question.getText().isEmpty())question.setText(text);
                }
                catch(Exception e){state.setText("답변을 완료하지 못했습니다.");answer.setText("<html>"+Entities.escape((e.getCause()==null?e:e.getCause()).toString())+"</html>");}
                finally{
                    progress.setIndeterminate(false);resultLayout.show(resultPages,"answer");
                    ask.setEnabled(true);stop.setVisible(false);models.setEnabled(true);settings.setEnabled(true);
                    providers.setEnabled(true);effort.setEnabled(true);
                }
            }
        };
        task.execute();
    }
}
