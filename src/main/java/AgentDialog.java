import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import java.awt.image.BufferedImage;
import org.jsoup.nodes.Entities;

final class AgentDialog extends JDialog {
    private final JComboBox<DocumentAgent.Provider> providers=new JComboBox<>(DocumentAgent.Provider.values());
    private final JComboBox<String> purpose=new JComboBox<>(new String[]{"자동","근거로 답변","페이지·구역 찾기","그림 OCR 찾기","MCP 문서 검색"});
    private final JTextArea question=new JTextArea(2,30);
    private final JComboBox<DocumentAgent.Effort> effort=new JComboBox<>();
    private final JComboBox<String> models=new JComboBox<>();
    private final JButton settings=new JButton("에이전트 설정");
    private static final String DEFAULT_MODEL="CLI 기본 모델";
    private boolean loadingEffort;
    private final JButton ask=new JButton("질문하기"),stop=new JButton("중단");
    private final JEditorPane answer=new JEditorPane("text/html","");
    private final JLabel state=new JLabel("질문과 관련 발췌문을 선택한 에이전트로 보냅니다.");
    private final JPanel conversation=new JPanel();
    private final JScrollPane threadScroll=new JScrollPane(conversation);
    private final Path library;
    private DocumentAgent.Claim selected;
    private final JPanel detail=new JPanel(new BorderLayout());
    private final JSplitPane results=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JButton scopeChip=new JButton("전체 보관함");
    private final Consumer<Library.Hit> openSource;
    private SearchIndex.Focus focus;
    private final JLabel image = new JLabel("", SwingConstants.CENTER);
    private final JButton original = new JButton("원본 파일 열기");
    private final JButton position = new JButton("문서에서 위치 보기");
    private SwingWorker<?,?> previewTask;
    private SwingWorker<List<DocumentAgent.Claim>,String> task;

    boolean isAnswering() { return task != null && !task.isDone(); }

    AgentDialog(JFrame parent,Path library,Consumer<Library.Hit> preview,Consumer<Library.Hit> openOriginal){
        super(parent,"문서에 질문",false);
        this.library=library;this.openSource=openOriginal;
        providers.setSelectedItem(AgentConnection.preferred());
        JPanel body=LibraryStyle.panel(new BorderLayout(0,12),18);
        body.setOpaque(true);body.setBackground(LibraryStyle.CANVAS);
        JPanel top=LibraryStyle.panel(new BorderLayout(0,12),0);
        JPanel heading=LibraryStyle.panel(new BorderLayout(),0);
        JLabel title=LibraryStyle.label("문서에 질문",23,true,LibraryStyle.INK);
        title.setIcon(LibraryStyle.document("",36,LibraryStyle.PURPLE));title.setIconTextGap(10);
        heading.add(title);
        LibraryStyle.button(settings,false);top.add(heading,BorderLayout.NORTH);
        JPanel controls=LibraryStyle.card(new GridLayout(1,4,10,0));
        models.setEditable(true);
        models.setToolTipText("목록에서 선택하거나 CLI에서 사용하는 모델 ID를 입력하세요.");
        controls.add(settings);controls.add(field("에이전트",providers));controls.add(field("모델 선택",models));
        controls.add(field("추론 깊이",effort));
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

        answer.setEditable(false);answer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
        answer.setFont(LibraryStyle.TYPE);
        answer.setText("<html><body style='color:#645f73'><p>질문을 입력하면 관련 문서를 찾아 답변과 인용문을 함께 보여줍니다.</p></body></html>");
        conversation.setLayout(new BoxLayout(conversation,BoxLayout.Y_AXIS));
        conversation.setBackground(Color.WHITE);
        threadScroll.setPreferredSize(new Dimension(430,320));

        var closePreview=new JButton("미리보기 닫기");
        closePreview.addActionListener(e->hidePreview());
        var detailHeader=new JPanel(new BorderLayout());
        detailHeader.add(closePreview,BorderLayout.EAST);detailHeader.add(image,BorderLayout.SOUTH);
        detail.add(detailHeader,BorderLayout.NORTH);
        detail.add(new JScrollPane(answer),BorderLayout.CENTER);
        var buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(position);buttons.add(original);detail.add(buttons,BorderLayout.SOUTH);
        original.setEnabled(false);position.setEnabled(false);

        original.addActionListener(e->{var c=selected;if(c!=null)openOriginal.accept(c.proof());});
        position.addActionListener(e->{
            var c=selected;
            if(c!=null) preview.accept(c.proof());
        });
        detail.setBorder(BorderFactory.createTitledBorder("선택한 출처 미리보기"));
        results.setLeftComponent(threadScroll);hidePreview();
        results.setResizeWeight(0.52);
        result.add(results);body.add(result);
        JPanel composer=LibraryStyle.card(new BorderLayout(0,8));
        var options=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        var toggle=new JToggleButton("모델 설정");
        controls.setVisible(false);
        toggle.addActionListener(e->{controls.setVisible(toggle.isSelected());composer.revalidate();});
        scopeChip.setEnabled(false);scopeChip.addActionListener(e->setFocus(null,false));
        options.add(scopeChip);options.add(purpose);options.add(toggle);
        var toolBar=new JPanel(new BorderLayout());toolBar.add(options,BorderLayout.NORTH);toolBar.add(controls);
        composer.add(toolBar,BorderLayout.NORTH);
        composer.add(new JScrollPane(question),BorderLayout.CENTER);
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
        stop.setVisible(false);stop.addActionListener(e->{if(task!=null)task.cancel(true);if(previewTask!=null)previewTask.cancel(true);});
        ask.addActionListener(e->runQuestion());
        question.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"),"submit");
        question.getActionMap().put("submit",new AbstractAction(){
            public void actionPerformed(java.awt.event.ActionEvent e){if(ask.isEnabled())runQuestion();}
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter(){
            public void windowClosed(java.awt.event.WindowEvent e){if(task!=null)task.cancel(true);if(previewTask!=null)previewTask.cancel(true);}
        });
        LibraryStyle.button(ask,true);setMinimumSize(new Dimension(680,480));setSize(1050,720);setLocationRelativeTo(parent);
    }

    private JPanel beginTurn(String text) {
        var turn=new JPanel(new BorderLayout(0,6));
        turn.setBackground(Color.WHITE);
        turn.setBorder(BorderFactory.createEmptyBorder(8,8,12,8));
        var bubble=new JTextArea(text);
        bubble.setEditable(false);bubble.setLineWrap(true);bubble.setWrapStyleWord(true);
        bubble.setFont(LibraryStyle.TYPE);bubble.setBackground(new Color(0xEDE8FB));
        bubble.setBorder(BorderFactory.createEmptyBorder(7,10,7,10));
        bubble.setColumns(Math.min(38,Math.max(8,text.length())));
        var questionRow=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,0,0),0);
        questionRow.add(bubble);turn.add(questionRow,BorderLayout.NORTH);
        turn.setAlignmentX(Component.LEFT_ALIGNMENT);
        if(focus!=null) bubble.setToolTipText(scopeChip.getText());
        conversation.add(turn);
        turnStatus(turn,"검색 중…");
        SwingUtilities.invokeLater(()->threadScroll.getVerticalScrollBar().setValue(threadScroll.getVerticalScrollBar().getMaximum()));
        return turn;
    }

    private void turnStatus(JPanel turn,String text){
        var layout=(BorderLayout)turn.getLayout();
        var previous=layout.getLayoutComponent(BorderLayout.SOUTH);
        if(previous!=null)turn.remove(previous);
        turn.add(LibraryStyle.label(text,12,false,LibraryStyle.MUTED),BorderLayout.SOUTH);
        turn.setMaximumSize(new Dimension(Integer.MAX_VALUE,turn.getPreferredSize().height));
        conversation.revalidate();conversation.repaint();
    }

    private void showSources(JPanel turn,List<DocumentAgent.Claim> claims,boolean answers) {
        var messages=new JPanel(new BorderLayout(0,4));
        if(answers){
            var text=new JTextArea(String.join("\n\n",claims.stream().map(DocumentAgent.Claim::answer).distinct().toList()));
            text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);
            text.setFont(LibraryStyle.TYPE);text.setColumns(30);messages.add(text,BorderLayout.NORTH);
        }
        var cards=new JPanel();
        cards.setLayout(new BoxLayout(cards,BoxLayout.Y_AXIS));
        var more=new JButton();
        Runnable next=new Runnable(){
            int shown;
            public void run(){
                cards.remove(more);
                int end=Math.min(shown+3,claims.size());
                while(shown<end)cards.add(sourceCard(claims.get(shown++)));
                if(shown<claims.size()){more.setText("다른 출처 보기 ("+(claims.size()-shown)+")");cards.add(more);}
                turnStatus(turn,"출처 "+claims.size()+"건");
            }
        };
        more.addActionListener(e->next.run());
        messages.add(cards);turn.add(messages,BorderLayout.CENTER);next.run();
        turnStatus(turn,"출처 "+claims.size()+"건");
        SwingUtilities.invokeLater(()->threadScroll.getVerticalScrollBar().setValue(threadScroll.getVerticalScrollBar().getMaximum()));
    }

    static String relevanceHtml(DocumentAgent.Claim claim,boolean expanded){
        if(claim.evidence().isEmpty()){
            return "<b>이 출처가 뒷받침하는 답변</b> "+Entities.escape(claim.answer())
                    +"<br>인용 원문　“"+Entities.escape(claim.proof().excerpt())+"”";
        }
        var rows=claim.evidence();
        var html=new StringBuilder();
        if(!claim.referenceUse().isBlank())html.append("<b>참고할 부분</b>　").append(Entities.escape(claim.referenceUse())).append("<br>");
        html.append("<b>이 결과를 찾은 이유</b>　");
        var groups=rows.stream().collect(java.util.stream.Collectors.groupingBy(SearchIndex.Evidence::field,
                java.util.LinkedHashMap::new,java.util.stream.Collectors.mapping(SearchIndex.Evidence::term,
                        java.util.stream.Collectors.toList())));
        var reasons=new java.util.ArrayList<String>();
        groups.forEach((field,terms)->{
            String where=switch(field){case "구역 원문" -> "이 구역"; case "그림 OCR" -> "그림 속 글자"; default -> field;};
            reasons.add(where+"에서 "+String.join(", ",terms.stream().distinct().map(t->"‘"+Entities.escape(t)+"’").toList()));
        });
        if(rows.stream().allMatch(e->e.term().isEmpty()))html.append(Entities.escape(claim.answer()));
        else html.append(String.join(", ",reasons)).append(" 확인");
        int limit=expanded?rows.size():Math.min(3,rows.size());
        for(var e:rows.subList(0,limit)){
            String quote=e.quote().strip();
            int start=quote.toLowerCase(java.util.Locale.ROOT).indexOf(e.term());
            String shown=Entities.escape(quote);
            if(start>=0 && start+e.term().length()<=quote.length())
                shown=Entities.escape(quote.substring(0,start))+"<b>"+Entities.escape(quote.substring(start,start+e.term().length()))
                        +"</b>"+Entities.escape(quote.substring(start+e.term().length()));
            html.append("<br>").append(e.field()).append("　“").append(shown).append("”");
        }
        if(limit<rows.size())html.append("<br>미리보기에서 근거 ").append(rows.size()-limit).append("개 더 보기");
        if(!claim.missing().isEmpty())html.append("<br>일치가 확인되지 않은 검색 구절　")
                .append(Entities.escape(String.join(" · ",claim.missing())));
        return html.toString();
    }

    private JPanel sourceCard(DocumentAgent.Claim claim){
        var hit=claim.proof();
        var card=LibraryStyle.card(new BorderLayout(8,4));
        var folder=hit.source().getParent();
        var title=new JButton("<html><b>"+Entities.escape(hit.title())+"</b><br>"
                +Entities.escape((folder==null?"":folder.toString())+" · "+hit.section())+"<br>"
                +relevanceHtml(claim,false)+"</html>");
        title.setHorizontalAlignment(SwingConstants.LEFT);title.setContentAreaFilled(false);
        title.addActionListener(e->showSelected(claim));card.add(title);
        var actions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        var view=new JButton("미리보기");view.addActionListener(e->showSelected(claim));actions.add(view);
        var open=new JButton("원본 열기");open.addActionListener(e->openSource.accept(hit));actions.add(open);
        var page=new JButton("이 구역에서 계속 찾기");
        page.addActionListener(e->{setFocus(hit,true);question.requestFocusInWindow();});actions.add(page);
        var doc=new JButton("이 문서에서 찾기");
        doc.addActionListener(e->{setFocus(hit,false);question.requestFocusInWindow();});actions.add(doc);
        card.add(actions,BorderLayout.SOUTH);
        if(hit.picture()!=null){
            new SwingWorker<BufferedImage,Void>(){
                protected BufferedImage doInBackground(){return PictureAssets.preview(hit.md(),hit.picture(),true);}
                protected void done(){try{var icon=get();if(icon!=null)title.setIcon(new ImageIcon(icon));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();}
                    catch(java.util.concurrent.ExecutionException ignored){}}
            }.execute();
        }
        return card;
    }

    private void setFocus(Library.Hit hit,boolean page){
        focus=hit==null?null:new SearchIndex.Focus(hit.md(),page?hit.section():null);
        String label=hit==null?"전체 보관함":hit.title()+(page?" › "+hit.section():"");
        String shortName=hit!=null && hit.title().length()>30 ? hit.title().substring(0,29)+"…" : hit==null?label:hit.title();
        scopeChip.setText(shortName+(hit!=null && page?" › "+hit.section():"")+(hit==null?"":"  ×"));
        scopeChip.setEnabled(hit!=null);scopeChip.setToolTipText(hit==null?label:"전체 보관함으로 전환 · "+label);
    }

    private void hidePreview(){
        if(previewTask!=null)previewTask.cancel(true);
        results.setRightComponent(null);results.setDividerSize(0);
    }

    private record Detail(String html, BufferedImage picture) {}

    private void showSelected(DocumentAgent.Claim claim) {
        selected=claim;
        if(previewTask!=null) previewTask.cancel(true);
        image.setIcon(null);
        original.setEnabled(claim!=null);position.setEnabled(claim!=null);
        if(claim==null){answer.setText("");return;}
        var hit=claim.proof();
        setFocus(hit,true);
        results.setRightComponent(detail);results.setDividerSize(8);results.setDividerLocation(0.52);
        String heading="<html><body><h3>"+Entities.escape(hit.title())+"</h3><p>"
                +Entities.escape(hit.source().toString())+"<br>"+Entities.escape(hit.section())+"</p>";
        String evidence=heading+"<p>"+relevanceHtml(claim,true)+"</p>";
        answer.setText(evidence+"</body></html>");answer.setCaretPosition(0);
        var loader=new SwingWorker<Detail,Void>() {
            protected Detail doInBackground() throws Exception {
                if(hit.picture()!=null){
                    var bitmap=PictureAssets.preview(hit.md(),hit.picture(),false);
                    return new Detail(bitmap==null ? "<p>그림 파일을 읽을 수 없습니다.</p>" : "",bitmap);
                }
                var page=MarkdownReader.previewPage(hit.md(),hit);
                return new Detail(page==null ? "<p>현재 변환본에서 해당 구역을 찾지 못했습니다.</p>"
                        : org.jsoup.Jsoup.parse(MarkdownReader.html(page)).body().html(),null);
            }
            protected void done(){
                if(isCancelled() || selected!=claim)return;
                try{
                    Detail detail=get();
                    if(detail.picture()!=null){
                        var bitmap=detail.picture();
                        double scale=Math.min(1,Math.min(480d/bitmap.getWidth(),220d/bitmap.getHeight()));
                        image.setIcon(new ImageIcon(bitmap.getScaledInstance(Math.max(1,(int)(bitmap.getWidth()*scale)),
                                Math.max(1,(int)(bitmap.getHeight()*scale)),Image.SCALE_SMOOTH)));
                    }
                    answer.setText(evidence+detail.html()+"</body></html>");
                } catch(Exception ex){answer.setText(evidence+"<p>저장된 미리보기를 읽을 수 없습니다.</p></body></html>");}
                answer.setCaretPosition(0);
            }
        };
        previewTask=loader;loader.execute();
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
        int selectedPurpose=purpose.getSelectedIndex();
        var activeFocus=focus;
        purpose.setEnabled(false);
        question.setText("");
        JPanel turn=beginTurn(text);


        ask.setEnabled(false);stop.setVisible(true);models.setEnabled(false);settings.setEnabled(false);
        state.setText("질문을 보냈습니다.");providers.setEnabled(false);effort.setEnabled(false);
        task=new SwingWorker<>(){
            int action=selectedPurpose;
            protected List<DocumentAgent.Claim> doInBackground() throws Exception{
                DocumentAgent.Ask agent=p->DocumentAgent.invoke(provider,model,choice,p);
                if(action==4)return McpSearch.ask(provider,library,text,model,choice,activeFocus,this::publish);
                if(action==0 && PmReference.surveyReference(text))action=2;
                if(action==0){
                    String intent=agent.run("다음 질문의 목적을 한 단어로 답하세요. 개별 그림이나 화면을 찾으면 IMAGE, 문서나 장표, PM이 작성에 참고할 WBS·어댑터 설계 사례를 찾으면 FIND, 설명이나 요약이면 ANSWER. 질문: "+text).strip();
                    action=switch(intent){case "IMAGE"->3;case "FIND"->2;default->1;};
                }
                if(action!=1) return DocumentAgent.locate(library,text,action==3,agent,this::publish,activeFocus);
                return DocumentAgent.answer(library,text,agent,this::publish,activeFocus);
            }
            protected void process(List<String> messages){if(task==this && !isDone())turnStatus(turn,messages.getLast());}
            protected void done(){
                try{
                    var claims=get();
                    if(!claims.isEmpty()) showSources(turn,claims,action==1);
                    else if(action==2 && PmReference.surveyReference(text))
                        turnStatus(turn,"현재 검색 범위에서 조건에 맞는 사전조사서를 찾지 못했습니다. 다른 보관함 또는 원본 추가 여부를 확인하세요.");
                    else turnStatus(turn,action==1 ? "답변을 뒷받침하는 근거를 찾지 못했습니다." : "현재 색인에서 일치하는 위치를 찾지 못했습니다.");
                    state.setText(provider+" · "+(model.isEmpty()?DEFAULT_MODEL:model)+" · "+choice+(action==1?" · 확인된 인용 ":" · 검색 결과 ")+claims.size()+"건");
                }catch(java.util.concurrent.CancellationException e){
                    state.setText("질문 중단");turnStatus(turn,"질문을 중단했습니다.");
                    if(question.getText().isEmpty())question.setText(text);
                }
                catch(Exception e){state.setText("답변을 완료하지 못했습니다.");turnStatus(turn,(e.getCause()==null?e:e.getCause()).toString());}
                finally{

                    ask.setEnabled(true);stop.setVisible(false);models.setEnabled(true);settings.setEnabled(true);
                    purpose.setEnabled(true);providers.setEnabled(true);effort.setEnabled(true);
                }
            }
        };
        task.execute();
    }
}
