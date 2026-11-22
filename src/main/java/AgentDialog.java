import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import java.awt.image.BufferedImage;
import org.jsoup.nodes.Entities;

final class AgentDialog extends JPanel {
    private static final Color PAPER=new Color(0xFBF8F3), SAND=new Color(0xF2E6D2);
    private final JComboBox<DocumentAgent.Provider> providers=new JComboBox<>(DocumentAgent.Provider.values());
    private final JTextArea question=new JTextArea(1,22){
        protected void paintComponent(Graphics g){
            super.paintComponent(g);
            if(getDocument().getLength()==0){
                g.setFont(getFont());g.setColor(LibraryStyle.MUTED);
                g.drawString("메시지를 입력하세요…",getInsets().left+2,getInsets().top+getFontMetrics(getFont()).getAscent());
            }
        }
    };
    private final JButton modelButton=new JButton();
    private List<AgentConnection.Model> catalog=List.of();
    private String modelId="";
    private DocumentAgent.Effort reasoning;
    private final JButton newChat=new JButton("새 대화");
    private final java.util.ArrayDeque<String> history=new java.util.ArrayDeque<>();
    private final JButton settings=new JButton("에이전트 설정");
    private static final String DEFAULT_MODEL="CLI 기본 모델";
    private final JButton ask=new JButton("보내기"),stop=new JButton("중단");
    private final JEditorPane answer=new JEditorPane("text/html","");
    private final JLabel state=new JLabel("Office MCP · 메시지를 보내면 연결");
    private final JPanel conversation=new JPanel();
    private final JScrollPane threadScroll=new JScrollPane(conversation);
    private final Path library;
    private DocumentAgent.Claim selected;
    private final JPanel detail=new JPanel(new BorderLayout());
    private final JSplitPane results=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JTabbedPane documentTabs=new JTabbedPane();
    private final ChatTargets targets=new ChatTargets(this::checkedScope,this::previewTarget);
    private final java.util.IdentityHashMap<DocumentAgent.Claim,List<DocumentAgent.Claim>> answerGroups=new java.util.IdentityHashMap<>();
    private final JButton previous=new JButton("이전 출처"),next=new JButton("다음 출처");
    private final JComboBox<Integer> zoom=new JComboBox<>(new Integer[]{50,75,100,125,150,200});
    private final JPanel foundTab=new JPanel(new BorderLayout());
    private SwingWorker<McpSearch.Reply,String> slideTask;
    private FoundDocuments searchingPanel;
    private JDialog previewWindow;
    private BufferedImage previewBitmap;
    private String previewHtml="";
    private record TurnScope(SearchIndex.Focus focus,List<String> notes) {}
    private TurnScope previousScope;

    private final JPanel sidebar=new SearchStudio.Round(new BorderLayout(),20,Color.WHITE,LibraryStyle.LINE,1);
    private final ChatDocuments documents=new ChatDocuments(this::showSelected);
    private record ScopeChoice(String label, SearchIndex.Focus target) {
        @Override public String toString() { return label; }
    }
    private static final ScopeChoice ALL=new ScopeChoice("전체 문서",null);
    private final JComboBox<ScopeChoice> scopes=new JComboBox<>(new ScopeChoice[]{ALL});

    private final JLabel image = new JLabel("", SwingConstants.CENTER);
    private final JButton original = new JButton("원본 파일 열기");
    private final JButton position = new JButton("인용 위치 보기");
    private SwingWorker<?,?> previewTask;
    private SwingWorker<McpSearch.Reply,String> task;

    boolean isAnswering() { return (task != null && !task.isDone()) || slideTask!=null; }

    AgentDialog(Path library,Consumer<Library.Hit> preview,Consumer<Library.Hit> openOriginal){
        super(new BorderLayout());
        this.library=library;
        providers.setSelectedItem(AgentConnection.preferred());
        JPanel body=LibraryStyle.panel(new BorderLayout(0,12),16);
        body.setOpaque(true);body.setBackground(PAPER);
        JPanel top=LibraryStyle.panel(new BorderLayout(0,8),0);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,LibraryStyle.LINE),BorderFactory.createEmptyBorder(0,0,12,0)));
        top.add(LibraryStyle.label("에이전트 대화",24,true,LibraryStyle.INK));
        var headerActions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,10,0),0);
        headerActions.add(state);headerActions.add(newChat);
        top.add(headerActions,BorderLayout.EAST);
        newChat.setEnabled(false);
        newChat.addActionListener(e->{
            cancelSlideSearch();resetFound();
            conversation.removeAll();
            history.clear();
            documents.clear();answerGroups.clear();targets.select(List.of());previousScope=null;
            hidePreview();
            scopes.setSelectedItem(ALL);
            updateScopes(null);
            question.setText("");
            state.setText("Office MCP · 메시지를 보내면 연결");
            showWelcome();
            newChat.setEnabled(false);
            focusQuestion();
        });
        LibraryStyle.button(settings,false);
        body.add(top,BorderLayout.NORTH);
        providers.addActionListener(e->loadModelSettings());
        loadModelSettings();
        modelButton.setIcon(chevron(true));
        modelButton.setHorizontalTextPosition(SwingConstants.LEFT);
        modelButton.setToolTipText("모델 및 검색 설정");
        modelButton.addActionListener(e->showModelPicker());
        settings.addActionListener(e->{
            var chosen=AgentSettingsDialog.open(SwingUtilities.getWindowAncestor(this),(DocumentAgent.Provider)providers.getSelectedItem());
            if(chosen!=null)selectDefault();
        });
        JPanel result=LibraryStyle.panel(new BorderLayout(),0);
        results.setBorder(null);results.setBackground(PAPER);

        answer.setBackground(Color.WHITE);answer.setEditable(false);answer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
        answer.setFont(LibraryStyle.TYPE);
        answer.setText("<html><body style='color:#645f73'><p>질문을 입력하면 관련 문서를 찾아 답변과 인용문을 함께 보여줍니다.</p></body></html>");
        conversation.setLayout(new BoxLayout(conversation,BoxLayout.Y_AXIS));
        conversation.setBackground(PAPER);
        threadScroll.getViewport().setBackground(PAPER);
        threadScroll.getVerticalScrollBar().setUnitIncrement(18);
        threadScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){
            public void componentResized(java.awt.event.ComponentEvent e){
                for(Component turn:conversation.getComponents())turn.invalidate();
                conversation.revalidate();
            }
        });
        showWelcome();
        threadScroll.setPreferredSize(new Dimension(430,320));
        threadScroll.setBorder(null);

        var detailHeader=LibraryStyle.panel(new BorderLayout(0,4),0);
        var navigation=LibraryStyle.panel(new BorderLayout(),0);
        var browse=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
        browse.add(previous);browse.add(next);browse.add(new JLabel("확대/축소 (%)"));browse.add(zoom);
        zoom.setSelectedItem(100);zoom.addActionListener(e->renderPreview());
        previous.addActionListener(e->stepPreview(-1));next.addActionListener(e->stepPreview(1));
        navigation.add(browse,BorderLayout.WEST);
        detailHeader.add(navigation,BorderLayout.NORTH);
        var toolbar=LibraryStyle.panel(new BorderLayout(3,0),0);
        toolbar.add(LibraryStyle.label("미리보기",13,true,LibraryStyle.INK),BorderLayout.WEST);
        var documentActions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,3,0),0);
        for (var button:List.of(position,original)) {
            LibraryStyle.button(button,false);
            button.setFont(LibraryStyle.TYPE.deriveFont(12f));
            button.putClientProperty("JButton.buttonType",null);
            button.putClientProperty("FlatLaf.style","arc: 8; minimumWidth: 0");
            button.setMargin(new Insets(2,5,2,5));
        }
        documentActions.add(position);
        documentActions.add(original);
        toolbar.add(documentActions,BorderLayout.EAST);
        detailHeader.add(toolbar,BorderLayout.CENTER);
        detail.setOpaque(false);
        detail.add(detailHeader,BorderLayout.NORTH);
        var pageBody=new JPanel(new BorderLayout());
        pageBody.add(image,BorderLayout.NORTH);pageBody.add(answer);
        var readerScroll=new JScrollPane(pageBody);
        readerScroll.setBorder(BorderFactory.createMatteBorder(1,0,1,0,LibraryStyle.LINE));
        readerScroll.getViewport().setBackground(Color.WHITE);
        readerScroll.getVerticalScrollBar().setUnitIncrement(18);
        detail.add(readerScroll,BorderLayout.CENTER);
        original.setEnabled(false);position.setEnabled(false);

        original.addActionListener(e->{var c=selected;if(c!=null)actOnCurrent(c,openOriginal);});
        position.addActionListener(e->{
            var c=selected;
            if(c!=null)actOnCurrent(c,preview);
        });
        sidebar.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        sidebar.setPreferredSize(new Dimension(360,320));
        sidebar.setMinimumSize(new Dimension(245,120));
        documentTabs.addTab("대화에서 찾은 문서",foundTab);
        resetFound();
        documentTabs.addTab("대화 출처",documents);
        documentTabs.addTab("질문 범위",targets);
        documentTabs.addChangeListener(e->{if(documentTabs.getSelectedComponent()==targets)targets.refresh();});
        sidebar.add(documentTabs);

        var chatColumn=LibraryStyle.panel(new BorderLayout(0,8),0);
        chatColumn.add(threadScroll);
        results.setLeftComponent(chatColumn);
        results.setRightComponent(sidebar);
        results.setDividerSize(8);
        results.setResizeWeight(0.65);
        result.add(results);body.add(result);
        var composer=new SearchStudio.Round(new BorderLayout(0,3),14,Color.WHITE,LibraryStyle.LINE,1);
        composer.setBorder(BorderFactory.createEmptyBorder(6,9,6,9));
        var scopeRow=LibraryStyle.panel(new BorderLayout(6,0),0);
        var scopeLabel=LibraryStyle.label("검색 범위",12,true,LibraryStyle.MUTED);
        scopeLabel.setLabelFor(scopes);
        scopes.setFont(LibraryStyle.TYPE.deriveFont(12f));
        scopes.setBackground(new Color(0xEEF2E7));
        scopes.setPrototypeDisplayValue(new ScopeChoice("이 구역만 · 운영 가이드.xlsx › 검토 절차",null));
        scopes.getAccessibleContext().setAccessibleName("검색 범위");
        scopes.addActionListener(e->{
            var choice=currentScope();
            if(choice!=null)scopes.setToolTipText(choice.label());
            if(!updatingScopes && ALL.equals(choice) && !targets.selection().isEmpty()){
                targets.select(List.of());
                SwingUtilities.invokeLater(()->{if(ALL.equals(currentScope()))updateScopes(selected==null?null:selected.proof());});
            }
        });
        updateScopes(null);
        scopeRow.add(scopeLabel,BorderLayout.WEST);
        scopeRow.add(scopes,BorderLayout.CENTER);
        var scopeLine=LibraryStyle.panel(new BorderLayout(),0);
        scopeLine.add(scopeRow,BorderLayout.WEST);
        composer.add(scopeLine,BorderLayout.NORTH);
        var inputScroll=new JScrollPane(question);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        composer.add(inputScroll,BorderLayout.CENTER);
        question.getAccessibleContext().setAccessibleName("에이전트 메시지");
        question.setLineWrap(true);question.setWrapStyleWord(true);question.setFont(LibraryStyle.TYPE.deriveFont(16f));
        question.setBorder(BorderFactory.createEmptyBorder(5,4,4,4));
        question.setToolTipText("Enter 전송 · Shift+Enter 줄바꿈");
        JPanel footer=LibraryStyle.panel(new BorderLayout(6,0),0);
        state.setFont(LibraryStyle.TYPE.deriveFont(12f));state.setForeground(LibraryStyle.MUTED);


        JPanel actions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        actions.add(modelButton);actions.add(stop);actions.add(ask);
        footer.add(actions,BorderLayout.EAST);composer.add(footer,BorderLayout.SOUTH);
        chatColumn.add(composer,BorderLayout.SOUTH);
        var canvas=new JPanel(new BorderLayout()){
            public void doLayout(){
                super.doLayout();
                int width=Math.min(getWidth(),1280);
                body.setBounds((getWidth()-width)/2,0,width,getHeight());
            }
        };
        canvas.setBackground(PAPER);canvas.add(body);add(canvas);
        stop.setVisible(false);stop.addActionListener(e->{if(task!=null)task.cancel(true);if(previewTask!=null)previewTask.cancel(true);});
        ask.addActionListener(e->runQuestion());
        question.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"),javax.swing.text.DefaultEditorKit.insertBreakAction);
        question.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"submit");
        question.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"),"submit");
        question.getActionMap().put("submit",new AbstractAction(){
            public void actionPerformed(java.awt.event.ActionEvent e){if(ask.isEnabled())runQuestion();}
        });
        LibraryStyle.button(ask,true);
        ask.putClientProperty("JButton.buttonType",null);
        ask.putClientProperty("FlatLaf.style","arc: 8");
        ask.setText("보내기 ↑");ask.setToolTipText("보내기 · Enter");
        ask.getAccessibleContext().setAccessibleName("보내기");
        ask.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD,13f));
        ask.setMargin(new Insets(3,9,3,9));
        ask.setBackground(SearchStudio.HOT);
        LibraryStyle.button(newChat,false);
        LibraryStyle.button(modelButton,false);
        modelButton.setContentAreaFilled(false);
        modelButton.setMargin(new Insets(3,5,3,5));
    }

    private static Icon chevron(boolean up){
        return new Icon(){
            public int getIconWidth(){return 12;}
            public int getIconHeight(){return 12;}
            public void paintIcon(Component c,Graphics graphics,int x,int y){
                var g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.4f));g.setColor(LibraryStyle.MUTED);
                int edge=y+(up?8:4),middle=y+(up?4:8);
                g.drawPolyline(new int[]{x+2,x+6,x+10},new int[]{edge,middle,edge},3);
                g.dispose();
            }
        };
    }

    void focusQuestion() { question.requestFocusInWindow(); }

    private final JLabel welcome=LibraryStyle.label("에이전트와 어떤 작업을 할까요?",18,true,LibraryStyle.INK);

    private void showWelcome(){
        welcome.setBorder(BorderFactory.createEmptyBorder(24,16,12,16));
        conversation.add(welcome);
        conversation.revalidate();
        conversation.repaint();
    }

    private JPanel beginTurn(String text) {
        conversation.remove(welcome);
        var turn=new JPanel(new BorderLayout(0,14)){
            public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        };
        turn.setOpaque(false);
        turn.setBorder(BorderFactory.createEmptyBorder(8,8,12,8));
        var bubble=new JTextArea(text){
            public Dimension getPreferredSize(){
                int limit=Math.max(96,threadScroll.getViewport().getWidth()*2/3-40);
                int widest=getText().lines().mapToInt(line->getFontMetrics(getFont()).stringWidth(line)).max().orElse(0);
                int width=Math.min(limit,widest+3);
                var view=getUI().getRootView(this);
                view.setSize(width,Short.MAX_VALUE);
                return new Dimension(width,(int)Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))+2);
            }
        };
        bubble.setEditable(false);bubble.setLineWrap(true);bubble.setWrapStyleWord(true);
        bubble.setFont(LibraryStyle.TYPE.deriveFont(16f));bubble.setOpaque(false);
        bubble.setBorder(null);
        var balloon=new SearchStudio.Round(new BorderLayout(),20,SAND,null,0);
        balloon.setBorder(BorderFactory.createEmptyBorder(9,14,9,14));balloon.add(bubble);
        var questionRow=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,0,0),0);
        questionRow.add(balloon);turn.add(questionRow,BorderLayout.NORTH);
        turn.setAlignmentX(Component.LEFT_ALIGNMENT);
        if(currentScope().target()!=null) bubble.setToolTipText(currentScope().label());
        conversation.add(turn);
        turnStatus(turn,"에이전트 시작 중…");
        SwingUtilities.invokeLater(()->threadScroll.getVerticalScrollBar().setValue(threadScroll.getVerticalScrollBar().getMaximum()));
        return turn;
    }

    private void turnStatus(JPanel turn,String text){
        var layout=(BorderLayout)turn.getLayout();
        var previous=layout.getLayoutComponent(BorderLayout.SOUTH);
        if(previous!=null)turn.remove(previous);
        if(!text.isEmpty())turn.add(LibraryStyle.label(text,12,false,LibraryStyle.MUTED),BorderLayout.SOUTH);
        turn.revalidate();
        conversation.revalidate();conversation.repaint();
    }

    private void showFound(FoundDocuments panel){
        foundTab.removeAll();foundTab.add(panel);foundTab.revalidate();foundTab.repaint();
        documentTabs.setSelectedComponent(foundTab);
    }
    private void resetFound(){showFound(new FoundDocuments(List.of()));}
    private void cancelSlideSearch(){
        if(slideTask!=null){slideTask.cancel(true);slideTask=null;}
        if(searchingPanel!=null){searchingPanel.busy(false);searchingPanel.setStatus("검색이 중단되었습니다.");searchingPanel=null;}
    }
    private void findSelectedSlides(FoundDocuments panel,String prompt,String context,String expanded,SearchIndex.Focus focus,List<String> notes){
        cancelSlideSearch();
        var reader=ReferenceSearch.reader((DocumentAgent.Provider)providers.getSelectedItem(),modelId,reasoning,usage->{});
        DocumentAgent.Ask contextual=context.isBlank()?reader:instruction->reader.run("이전 대화 (현재 질문의 생략된 조건 확인용):\n"+context+"\n현재 작업:\n"+instruction);
        searchingPanel=panel;panel.busy(true);panel.setStatus("선택한 문서에서 장표 확인 중…");
        slideTask=new SwingWorker<>(){
            protected McpSearch.Reply doInBackground() throws Exception{
                return McpSearch.selectedSlides(prompt,expanded,focus,notes,contextual,this::publish);
            }
            protected void process(List<String> updates){if(slideTask==this)panel.setStatus(updates.getLast());}
            protected void done(){
                if(slideTask!=this)return;
                try{
                    var reply=get();var slides=reply.sources();
                    if(slides.isEmpty()){panel.setStatus(reply.text());return;}
                    for(var claim:slides)answerGroups.put(claim,slides);
                    documents.append(slides);
                    panel.setStatus("참고 슬라이드 "+slides.size()+"장을 찾았습니다.");
                    showSelected(slides.getFirst());
                }catch(Exception ex){panel.setStatus("슬라이드 검색 실패 · "+(ex.getCause()==null?ex:ex.getCause()).getMessage());}
                finally{panel.busy(false);searchingPanel=null;slideTask=null;}
            }
        };slideTask.execute();
    }

    private void displayReply(JPanel body,McpSearch.Reply reply,String prompt,String context,SearchIndex.Focus scope){
        var citations=new java.util.LinkedHashMap<String,DocumentAgent.Claim>();
        for(var source:reply.sources())citations.put(source.answer(),source);
        var message=new JEditorPane("text/html",MarkdownReader.chatHtml(reply.text(),citations.keySet())){
            public Dimension getPreferredSize(){
                int width=Math.max(120,threadScroll.getViewport().getWidth()-82);
                var view=getUI().getRootView(this);
                view.setSize(width,Short.MAX_VALUE);
                return new Dimension(width,(int)Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))+5);
            }
        };
        message.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
        message.setFont(LibraryStyle.TYPE.deriveFont(16f));
        message.setEditable(false);message.setOpaque(false);message.setBorder(null);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.addHyperlinkListener(e->{
            if(e.getEventType()==javax.swing.event.HyperlinkEvent.EventType.ACTIVATED){
                var source=citations.get(e.getDescription().replaceFirst("^#",""));
                if(source!=null)showSelected(source);
            }
        });
        body.add(message);
        documents.append(reply.sources());
        if(!reply.sources().isEmpty()){
            for(var claim:reply.sources()){
                answerGroups.put(claim,reply.sources());
                var large=new JButton(claim.proof().title()+" · "+claim.proof().section()+"  크게 보기");
                large.putClientProperty("html.disable",true);
                large.addActionListener(e->showSelected(claim));body.add(large);
            }
        }
        if(reply.candidates().isEmpty()){
            var empty=new FoundDocuments(List.of());empty.setStatus("이번 답변에서 찾은 문서가 없습니다.");showFound(empty);
        }
        if(!reply.candidates().isEmpty()){
            var panel=new FoundDocuments(reply.candidates());
            panel.onSearch(notes->findSelectedSlides(panel,prompt,context,reply.expanded(),scope,notes));
            var reopen=new JButton("이 답변에서 찾은 문서 보기");
            reopen.addActionListener(e->showFound(panel));body.add(reopen);showFound(panel);
        }
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

    private ScopeChoice currentScope() {
        return (ScopeChoice)scopes.getSelectedItem();
    }

    private boolean updatingScopes;
    private ScopeChoice selectedChoice(){return new ScopeChoice("선택한 문서 "+targets.selection().size()+"개",null);}
    private boolean isChecked(ScopeChoice choice){return !ALL.equals(choice) && choice!=null && choice.target()==null;}
    private void checkedScope(){
        updateScopes(selected==null?null:selected.proof());
        scopes.setSelectedItem(selectedChoice());
    }
    void useResults(List<String> notes,String draft){
        targets.select(notes);targets.refresh();checkedScope();
        documentTabs.setSelectedComponent(targets);question.setText(draft);focusQuestion();
    }
    private void previewTarget(ChatTargets.Doc doc){
        var hit=new Library.Hit(Path.of(doc.md()),Path.of(doc.source()),doc.title(),"","");
        showSelected(new DocumentAgent.Claim("문서 미리보기",hit));
    }
    private void updateScopes(Library.Hit hit) {
        var active=currentScope();
        var options=new java.util.LinkedHashSet<ScopeChoice>();
        options.add(ALL);
        options.add(selectedChoice());
        if(isChecked(active))active=selectedChoice();
        if(active!=null)options.add(active);
        if(hit!=null) {
            String file=hit.source().getFileName().toString();
            options.add(new ScopeChoice("문서 전체 · "+file,new SearchIndex.Focus(hit.md(),null)));
            if(!hit.section().isBlank() && !(hit.section().equals("제목") && hit.picture()==null))
                options.add(new ScopeChoice("이 구역만 · "+file+" › "+hit.section(),new SearchIndex.Focus(hit.md(),hit.section())));
        }
        var model=new DefaultComboBoxModel<>(options.toArray(ScopeChoice[]::new));
        model.setSelectedItem(active);
        updatingScopes=true;
        try{scopes.setModel(model);}finally{updatingScopes=false;}
        scopes.setToolTipText(active.label());
    }

    private void hidePreview(){
        if(previewTask!=null)previewTask.cancel(true);
        selected=null;
        updateScopes(null);
        if(previewWindow!=null)previewWindow.setVisible(false);
        documents.focusList();
    }

    private record Detail(String html, BufferedImage picture) {}

    private void actOnCurrent(DocumentAgent.Claim claim,Consumer<Library.Hit> action){
        if(claim.page()==null){action.accept(claim.proof());return;}
        new SwingWorker<Boolean,Void>(){
            protected Boolean doInBackground() throws Exception{return ReferenceIndex.current(claim.page());}
            protected void done(){
                if(selected!=claim)return;
                try{if(get()){action.accept(claim.proof());return;}}catch(Exception ignored){}
                JOptionPane.showMessageDialog(AgentDialog.this,"현재 버전의 원본을 확인할 수 없습니다. 다시 검색하세요.");
            }
        }.execute();
    }

    private void showSelected(DocumentAgent.Claim claim) {
        selected=claim;
        updateScopes(claim==null?null:claim.proof());
        if(previewTask!=null) previewTask.cancel(true);
        image.setIcon(null);
        original.setEnabled(claim!=null);position.setEnabled(claim!=null);
        if(claim==null){answer.setText("");return;}
        var hit=claim.proof();
        if(previewWindow==null){
            previewWindow=new JDialog(SwingUtilities.getWindowAncestor(this),"자료 미리보기",Dialog.ModalityType.MODELESS);
            previewWindow.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            previewWindow.addWindowListener(new java.awt.event.WindowAdapter(){
                public void windowClosing(java.awt.event.WindowEvent event){documents.focusList();}
            });
            previewWindow.setContentPane(detail);previewWindow.setSize(940,700);
            previewWindow.setLocationRelativeTo(this);
        }
        previewWindow.setTitle(hit.title()+" · "+hit.section());
        if(!previewWindow.isVisible())previewWindow.setVisible(true);
        previewWindow.toFront();
        var group=answerGroups.getOrDefault(claim,List.of(claim));
        int at=group.indexOf(claim);previous.setEnabled(at>0);next.setEnabled(at>=0 && at+1<group.size());
        previewBitmap=null;previewHtml="";
        String heading="<html><head><style>body{font-family:'Malgun Gothic';font-size:11pt;color:#25251f;margin:10px}"
                +"h2{font-size:15pt;margin:0 0 5px} .section{color:#b74323;font-weight:bold}"
                +".path{color:#706e62;font-size:9pt} blockquote{background:#fbf8f3;margin:10px 0;padding:8px}"
                +"</style></head><body><h2>"+Entities.escape(hit.title())+"</h2><div class='section'>"
                +Entities.escape(hit.section())+"</div><div class='path'>"+Entities.escape(hit.source().toString())+"</div>";
        String excerpt=claim.page()!=null && !claim.evidence().isEmpty() ? claim.evidence().getFirst().quote() : hit.excerpt();
        String evidence=heading+"<blockquote>"+Entities.escape(excerpt)+"</blockquote>";
        answer.setText(evidence+"</body></html>");answer.setCaretPosition(0);
        var loader=new SwingWorker<Detail,Void>() {
            protected Detail doInBackground() throws Exception {
                if(claim.page()!=null){
                    return new Detail("<p>"+Entities.escape(claim.referenceUse())+"</p>",ReferenceWiki.preview(claim.page()));
                }
                if(hit.picture()!=null){
                    var bitmap=PictureAssets.preview(hit.md(),hit.picture(),false);
                    return new Detail(bitmap==null ? "<p>그림 파일을 읽을 수 없습니다.</p>" : "",bitmap);
                }
                var page=hit.section().isBlank()?MarkdownReader.firstPage(hit.md()):MarkdownReader.previewPage(hit.md(),hit);
                return new Detail(page==null ? "<p>현재 변환본에서 해당 구역을 찾지 못했습니다.</p>"
                        : org.jsoup.Jsoup.parse(MarkdownReader.html(page,hit.picture(),hit.md())).body().html(),null);
            }
            protected void done(){
                if(isCancelled() || selected!=claim)return;
                try{
                    Detail detail=get();
                    previewBitmap=detail.picture();
                    previewHtml=evidence+detail.html()+"</body></html>";
                    renderPreview();
                } catch(Exception ex){answer.setText(evidence+"<p>저장된 미리보기를 읽을 수 없습니다.</p></body></html>");}
                answer.setCaretPosition(0);
            }
        };
        previewTask=loader;loader.execute();
    }

    private void stepPreview(int delta){
        var group=answerGroups.get(selected);
        if(group==null)return;
        int index=group.indexOf(selected)+delta;
        if(index>=0 && index<group.size())showSelected(group.get(index));
    }
    private void renderPreview(){
        int percent=(Integer)zoom.getSelectedItem();
        if(previewBitmap!=null){
            int w=Math.max(1,previewBitmap.getWidth()*percent/100),h=Math.max(1,previewBitmap.getHeight()*percent/100);
            image.setIcon(new ImageIcon(previewBitmap.getScaledInstance(w,h,Image.SCALE_SMOOTH)));
        }
        if(!previewHtml.isEmpty())answer.setText(previewHtml.replace("font-size:11pt","font-size:"+(11.0*percent/100)+"pt"));
        answer.setCaretPosition(0);
    }

    private JPanel field(String name,JComponent control){
        JPanel field=LibraryStyle.panel(new BorderLayout(0,5),0);
        JLabel label=LibraryStyle.label(name,13,true,LibraryStyle.MUTED);
        label.setLabelFor(control);field.add(label,BorderLayout.NORTH);
        field.add(control==question?new JScrollPane(question):control);
        return field;
    }
    private DocumentAgent.Provider provider(){
        return (DocumentAgent.Provider)providers.getSelectedItem();
    }

    void selectDefault(){if(ask.isEnabled())providers.setSelectedItem(AgentConnection.preferred());}

    private void loadModelSettings(){
        settings.setText(provider()+" 연결 설정");
        catalog=AgentConnection.models(provider());
        modelId=AgentConnection.model(provider());
        reasoning=AgentConnection.effort(provider());
        updateModelButton();
    }

    private static String shortEffort(DocumentAgent.Effort choice){
        return choice.toString().split(" · ",2)[0];
    }

    private void updateModelButton(){
        String name=catalog.stream().filter(m->m.id().equals(modelId)).map(AgentConnection.Model::name)
                .findFirst().orElse(modelId.isEmpty()?provider()+" 기본 모델":modelId);
        modelButton.setText((name.length()>30?name.substring(0,29)+"…":name)+" · "+shortEffort(reasoning));
        modelButton.setToolTipText(name+" · "+reasoning+" (모델 및 검색 설정)");
    }

    private void showModelPicker(){
        var popup=new JPopupMenu();
        var panel=LibraryStyle.panel(new BorderLayout(0,8),10);
        var search=new JTextField();
        search.putClientProperty("JTextField.placeholderText","모델 검색 또는 모델 ID 입력");
        search.getAccessibleContext().setAccessibleName("모델 검색");
        panel.add(search,BorderLayout.NORTH);
        var rows=new DefaultListModel<AgentConnection.Model>();
        var list=new JList<>(rows);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view,m,index,highlighted,focused)->{
            var label=new DefaultListCellRenderer();
            label.getListCellRendererComponent(view,m.name(),index,highlighted,focused);
            label.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));
            String check=m.id().equals(modelId)?"✓　":"";
            label.setText("<html><b>"+check+Entities.escape(m.name())+"</b>"
                    +(m.description().isBlank()?"":"<br>"+Entities.escape(m.description()))+"</html>");
            label.setToolTipText(m.description().isBlank()?m.id():m.description());
            return label;
        });
        Runnable filter=()->{
            String query=search.getText().strip();
            String needle=query.toLowerCase(java.util.Locale.ROOT);
            rows.clear();
            var options=new java.util.ArrayList<AgentConnection.Model>();
            options.add(new AgentConnection.Model("",DEFAULT_MODEL,""));
            options.addAll(catalog);
            for(var m:options){
                if((m.name()+" "+m.id()).toLowerCase(java.util.Locale.ROOT).contains(needle))rows.addElement(m);
            }
            if(!query.isEmpty() && options.stream().noneMatch(m->m.id().equalsIgnoreCase(query)||m.name().equalsIgnoreCase(query))){
                try{
                    DocumentAgent.modelArguments(query);
                    rows.addElement(new AgentConnection.Model(query,query,"직접 입력한 모델 ID 사용"));
                }catch(IllegalArgumentException ignored){/* Only valid custom IDs can be applied. */}
            }
            if(!rows.isEmpty())list.setSelectedIndex(0);
            for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(modelId)){list.setSelectedIndex(i);break;}
            list.ensureIndexIsVisible(list.getSelectedIndex());
        };
        filter.run();
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){filter.run();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){filter.run();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){filter.run();}
        });
        Runnable apply=()->{
            var choice=list.getSelectedValue();
            if(choice==null)return;
            modelId=choice.id();
            AgentConnection.model(provider(),modelId);
            if(!modelId.isEmpty() && catalog.stream().noneMatch(m->m.id().equals(modelId)))catalog=AgentConnection.models(provider());
            updateModelButton();
            list.repaint();
        };
        list.addMouseListener(new java.awt.event.MouseAdapter(){
            public void mouseClicked(java.awt.event.MouseEvent e){
                int i=list.locationToIndex(e.getPoint());
                if(SwingUtilities.isLeftMouseButton(e) && i>=0 && list.getCellBounds(i,i).contains(e.getPoint()))apply.run();
            }
        });
        var choose=new AbstractAction(){
            public void actionPerformed(java.awt.event.ActionEvent e){apply.run();popup.setVisible(false);focusQuestion();}
        };
        search.addActionListener(choose);
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"choose");
        list.getActionMap().put("choose",choose);
        search.getInputMap().put(KeyStroke.getKeyStroke("DOWN"),"browse");
        search.getActionMap().put("browse",new AbstractAction(){
            public void actionPerformed(java.awt.event.ActionEvent e){list.requestFocusInWindow();}
        });
        var scroll=new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(385,165));
        panel.add(scroll);
        var bottom=LibraryStyle.panel(new BorderLayout(0,6),0);
        var levels=LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,3,0),0);
        levels.add(new JLabel("추론 수준"));
        var group=new ButtonGroup();
        for(var value:DocumentAgent.Effort.choices(provider())){
            var button=new JToggleButton(shortEffort(value),value==reasoning);
            button.setMargin(new Insets(3,5,3,5));
            group.add(button);levels.add(button);
            button.addActionListener(e->{
                reasoning=value;AgentConnection.effort(provider(),value);updateModelButton();
            });
        }
        bottom.add(levels,BorderLayout.NORTH);
        var advanced=new JButton("에이전트 연결 설정…");
        advanced.addActionListener(e->{
            popup.setVisible(false);
            var options=LibraryStyle.panel(new GridLayout(0,1,0,8),4);
            options.add(field("에이전트",providers));options.add(settings);
            JOptionPane.showMessageDialog(this,options,"검색 설정",JOptionPane.PLAIN_MESSAGE);
        });
        bottom.add(advanced);
        panel.add(bottom,BorderLayout.SOUTH);
        popup.add(panel);
        var size=popup.getPreferredSize();
        popup.show(modelButton,modelButton.getWidth()-size.width,-size.height-4);
        SwingUtilities.invokeLater(search::requestFocusInWindow);
    }

    private void runQuestion(){
        String text=question.getText().strip();
        if(text.length()>1200){state.setText("질문은 1200자까지 입력할 수 있습니다.");return;}
        if(text.isEmpty()){state.setText("질문을 입력하세요.");question.requestFocusInWindow();return;}
        SearchIndex.Focus scope=currentScope().target();
        List<String> notes=isChecked(currentScope())?targets.selection():null;
        if(notes!=null && notes.isEmpty()){state.setText("검색할 문서를 선택하거나 전체 문서로 전환하세요.");return;}
        var provider=(DocumentAgent.Provider)providers.getSelectedItem();
        String model=modelId;
        try{provider.executable();}catch(java.io.IOException e){
            state.setText("에이전트 설정에서 CLI를 먼저 연결하세요.");
            if(AgentSettingsDialog.open(SwingUtilities.getWindowAncestor(this),provider)!=null)selectDefault();
            return;
        }
        var choice=reasoning;
        question.setText("");
        var selection=new TurnScope(scope,notes);
        if(!selection.equals(previousScope))history.clear();
        previousScope=selection;
        String context=String.join("\n",history);
        JPanel turn=beginTurn(text);
        var transcript=LibraryStyle.panel(null,4);
        transcript.setLayout(new BoxLayout(transcript,BoxLayout.Y_AXIS));
        var agentRow=LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,8,0),0);
        agentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        var badge=new SearchStudio.Round(new GridBagLayout(),9,SearchStudio.CHARCOAL,null,0);
        badge.setPreferredSize(new Dimension(29,29));
        badge.add(LibraryStyle.label(provider.toString().substring(0,1),15,true,Color.WHITE));
        agentRow.add(badge);agentRow.add(LibraryStyle.label(provider.toString(),17,true,LibraryStyle.INK));
        agentRow.setToolTipText(model.isEmpty()?DEFAULT_MODEL:model);
        transcript.add(agentRow);transcript.add(Box.createVerticalStrut(6));
        var activity=new JToggleButton("Office MCP · 연결 중");
        activity.setIcon(chevron(false));activity.setSelectedIcon(chevron(true));
        activity.setContentAreaFilled(false);activity.setBorderPainted(false);
        activity.setFont(LibraryStyle.TYPE.deriveFont(12f));activity.setForeground(LibraryStyle.MUTED);
        activity.setMargin(new Insets(2,0,2,6));
        activity.setAlignmentX(Component.LEFT_ALIGNMENT);
        activity.setEnabled(false);activity.setHorizontalAlignment(SwingConstants.LEFT);
        var eventsPanel=LibraryStyle.panel(new GridLayout(0,1,0,3),6);
        eventsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);eventsPanel.setVisible(false);
        activity.addActionListener(e->{eventsPanel.setVisible(activity.isSelected());turn.revalidate();conversation.revalidate();});
        var response=LibraryStyle.panel(null,0);
        response.setLayout(new BoxLayout(response,BoxLayout.Y_AXIS));
        response.setBorder(BorderFactory.createEmptyBorder(0,37,0,0));
        response.setAlignmentX(Component.LEFT_ALIGNMENT);
        response.add(activity);response.add(eventsPanel);response.add(Box.createVerticalStrut(8));
        transcript.add(response);
        turn.add(transcript,BorderLayout.CENTER);
        newChat.setEnabled(false);
        ask.setEnabled(false);stop.setVisible(true);modelButton.setEnabled(false);settings.setEnabled(false);
        state.setText("Office MCP 연결 중…");providers.setEnabled(false);
        task=new SwingWorker<>(){
            protected McpSearch.Reply doInBackground() throws Exception{
                return McpSearch.chat(provider,library,text,context,model,choice,scope,notes,this::publish);
            }
            int calls;
            protected void process(List<String> events){
                if(task!=this || isCancelled())return;
                for(String event:events){
                    eventsPanel.add(LibraryStyle.label(event,12,false,LibraryStyle.MUTED));
                    if(event.startsWith("Office MCP · "))calls++;
                }
                activity.setEnabled(true);activity.setText("Office MCP · 도구 호출 "+calls+"회");
                state.setText(events.getLast());
                turnStatus(turn,"답변 작성 중…");
            }
            protected void done(){
                try{
                    var reply=get();
                    displayReply(response,reply,text,context,scope);
                    String exchange="사용자: "+text+"\n에이전트: "+reply.text();
                    history.addLast(exchange.substring(0,Math.min(exchange.length(),4000)));
                    while(history.size()>4)history.removeFirst();
                    turnStatus(turn,"");
                    state.setText("Office MCP · 답변 완료");
                    SwingUtilities.invokeLater(()->threadScroll.getVerticalScrollBar().setValue(turn.getY()));
                }catch(java.util.concurrent.CancellationException e){
                    state.setText("질문 중단");turnStatus(turn,"질문을 중단했습니다.");
                    if(question.getText().isEmpty())question.setText(text);
                }
                catch(Exception e){state.setText("답변을 완료하지 못했습니다.");turnStatus(turn,(e.getCause()==null?e:e.getCause()).toString());}
                finally{

                    newChat.setEnabled(true);ask.setEnabled(true);stop.setVisible(false);modelButton.setEnabled(true);settings.setEnabled(true);
                    providers.setEnabled(true);
                }
            }
        };
        task.execute();
    }
}
