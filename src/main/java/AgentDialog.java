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
    private final JTextArea question=new JTextArea(2,30){
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
    private final JButton scopeChip=new JButton("전체 문서");

    private SearchIndex.Focus focus;
    private final JLabel image = new JLabel("", SwingConstants.CENTER);
    private final JButton original = new JButton("원본 파일 열기");
    private final JButton position = new JButton("문서에서 위치 보기");
    private SwingWorker<?,?> previewTask;
    private SwingWorker<McpSearch.Reply,String> task;

    boolean isAnswering() { return task != null && !task.isDone(); }

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
            conversation.removeAll();
            history.clear();
            hidePreview();
            setFocus(null,false);
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

        answer.setEditable(false);answer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
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

        var closePreview=new JButton("미리보기 닫기");
        closePreview.addActionListener(e->hidePreview());
        var detailHeader=new JPanel(new BorderLayout());
        detailHeader.add(closePreview,BorderLayout.EAST);detailHeader.add(image,BorderLayout.SOUTH);
        detail.add(detailHeader,BorderLayout.NORTH);
        detail.add(new JScrollPane(answer),BorderLayout.CENTER);
        var buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var scopeDoc=new JButton("이 문서에서 대화");
        scopeDoc.addActionListener(e->{if(selected!=null)setFocus(selected.proof(),false);hidePreview();focusQuestion();});
        var scopeSection=new JButton("이 구역에서 대화");
        scopeSection.addActionListener(e->{if(selected!=null)setFocus(selected.proof(),true);hidePreview();focusQuestion();});
        buttons.setLayout(new GridLayout(0,2,4,4));
        buttons.add(scopeDoc);buttons.add(scopeSection);buttons.add(position);buttons.add(original);detail.add(buttons,BorderLayout.SOUTH);
        original.setEnabled(false);position.setEnabled(false);

        original.addActionListener(e->{var c=selected;if(c!=null)actOnCurrent(c,openOriginal);});
        position.addActionListener(e->{
            var c=selected;
            if(c!=null)actOnCurrent(c,preview);
        });
        detail.setBorder(BorderFactory.createTitledBorder("선택한 출처 미리보기"));
        results.setLeftComponent(threadScroll);hidePreview();
        results.setResizeWeight(0.52);
        result.add(results);body.add(result);
        var composer=new SearchStudio.Round(new BorderLayout(0,8),22,Color.WHITE,LibraryStyle.LINE,2);
        composer.setBorder(BorderFactory.createEmptyBorder(10,14,12,14));
        scopeChip.setEnabled(false);
        scopeChip.addActionListener(e->setFocus(null,false));
        var inputScroll=new JScrollPane(question);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        composer.add(inputScroll,BorderLayout.CENTER);
        question.getAccessibleContext().setAccessibleName("에이전트 메시지");
        question.setLineWrap(true);question.setWrapStyleWord(true);question.setFont(LibraryStyle.TYPE.deriveFont(16f));
        question.setBorder(BorderFactory.createEmptyBorder(5,4,4,4));
        question.setToolTipText("Enter 전송 · Shift+Enter 줄바꿈");
        JPanel footer=LibraryStyle.panel(new BorderLayout(6,0),0);
        state.setFont(LibraryStyle.TYPE.deriveFont(12f));state.setForeground(LibraryStyle.MUTED);
        footer.add(scopeChip,BorderLayout.WEST);

        JPanel actions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        actions.add(modelButton);actions.add(stop);actions.add(ask);
        footer.add(actions,BorderLayout.EAST);composer.add(footer,BorderLayout.SOUTH);
        body.add(composer,BorderLayout.SOUTH);
        var canvas=new JPanel(new BorderLayout()){
            public void doLayout(){
                super.doLayout();
                int width=Math.min(getWidth(),results.getRightComponent()==null?960:1280);
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
        ask.setText("↑");ask.setToolTipText("보내기 · Enter");
        ask.getAccessibleContext().setAccessibleName("보내기");
        ask.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD,20f));
        ask.setMargin(new Insets(0,0,0,0));ask.setPreferredSize(new Dimension(38,38));
        ask.setBackground(new Color(0xBF5A10));
        LibraryStyle.button(newChat,false);
        LibraryStyle.button(modelButton,false);
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
        if(focus!=null) bubble.setToolTipText(scopeChip.getText());
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

    private void displayReply(JPanel body,McpSearch.Reply reply){
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
        for(var source:reply.sources()){
            var link=new JButton("["+source.answer()+"] "+source.proof().title()+" · "+source.proof().section());
            link.setIcon(LibraryStyle.document("",20,new Color(0x777A46)));
            LibraryStyle.button(link,false);link.setBackground(new Color(0xF3EFE5));
            link.putClientProperty("html.disable",true);
            link.addActionListener(e->showSelected(source));
            body.add(link);
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

    private void setFocus(Library.Hit hit,boolean page){
        if (hit!=null && hit.section().equals("제목") && hit.picture()==null) page=false;
        focus=hit==null?null:new SearchIndex.Focus(hit.md(),page?hit.section():null);
        String label=hit==null?"전체 문서":hit.title()+(page?" › "+hit.section():"");
        String shortName=hit!=null && hit.title().length()>30 ? hit.title().substring(0,29)+"…" : hit==null?label:hit.title();
        scopeChip.setText(shortName+(hit!=null && page?" › "+hit.section():"")+(hit==null?"":"  ×"));
        scopeChip.setEnabled(hit!=null);scopeChip.setToolTipText(hit==null?label:"색인된 전체 문서으로 전환 · "+label);
    }

    private void hidePreview(){
        if(previewTask!=null)previewTask.cancel(true);
        results.setRightComponent(null);results.setDividerSize(0);revalidate();
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
        if(previewTask!=null) previewTask.cancel(true);
        image.setIcon(null);
        original.setEnabled(claim!=null);position.setEnabled(claim!=null);
        if(claim==null){answer.setText("");return;}
        var hit=claim.proof();
        results.setRightComponent(detail);results.setDividerSize(8);results.setDividerLocation(0.52);revalidate();
        String heading="<html><body><h3>"+Entities.escape(hit.title())+"</h3><p>"
                +Entities.escape(hit.source().toString())+"<br>"+Entities.escape(hit.section())+"</p>";
        String evidence=heading+"<p>"+Entities.escape(hit.excerpt())+"</p>";
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
                var page=MarkdownReader.previewPage(hit.md(),hit);
                return new Detail(page==null ? "<p>현재 변환본에서 해당 구역을 찾지 못했습니다.</p>"
                        : org.jsoup.Jsoup.parse(MarkdownReader.html(page,hit.picture(),hit.md())).body().html(),null);
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
        var provider=(DocumentAgent.Provider)providers.getSelectedItem();
        String model=modelId;
        try{provider.executable();}catch(java.io.IOException e){
            state.setText("에이전트 설정에서 CLI를 먼저 연결하세요.");
            if(AgentSettingsDialog.open(SwingUtilities.getWindowAncestor(this),provider)!=null)selectDefault();
            return;
        }
        var choice=reasoning;
        question.setText("");
        String context=String.join("\n",history);
        SearchIndex.Focus scope=focus;
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
                return McpSearch.chat(provider,library,text,context,model,choice,scope,this::publish);
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
                    displayReply(response,reply);
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
