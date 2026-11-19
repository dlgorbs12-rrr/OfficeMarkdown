import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;

public final class OfficeMarkdown {
    private final Path library = SearchIndex.defaultLibrary();
    private AgentDialog agentDialog;
    private final JComboBox<ImageOcr.Mode> ocrMode=new JComboBox<>(ImageOcr.Mode.values());
    private final JFrame frame = new JFrame("문서 찾기 · Office Markdown");
    private final JTextField query = new JTextField(26);
    private final JComboBox<SearchIndex.Scope> scope = new JComboBox<>(SearchIndex.Scope.values());
    private final JComboBox<SearchIndex.Mode> mode = new JComboBox<>(SearchIndex.Mode.values());
    private final JButton search = new JButton("검색"), add = new JButton("파일 추가"), folder = new JButton("폴더 추가");
    private final JButton original = new JButton("원본 열기"), locate = new JButton("파일 위치"), note = new JButton("MD 원문 열기");
    private final DefaultListModel<Library.Hit> model = new DefaultListModel<>();
    private final JList<Library.Hit> results = new JList<>(model);
    private final JTextArea preview = new JTextArea(6, 50);
    private final JLabel status = new JLabel(" ");
    private static final int LIMIT = 500;

    private final JLabel count = LibraryStyle.label("문서 목록", 16, true, LibraryStyle.INK);
    private final JLabel detailTitle = LibraryStyle.label("", 18, true, LibraryStyle.INK);
    private final JLabel sectionLabel = LibraryStyle.label("", 13, true, LibraryStyle.PURPLE);
    private final JTextArea pathText = area();
    private final CardLayout pages = new CardLayout();
    private final JPanel contents = new JPanel(pages);
    private final JLabel emptyTitle = LibraryStyle.label("", 22, true, LibraryStyle.INK);
    private final JLabel emptyHint = LibraryStyle.label("", 14, false, LibraryStyle.MUTED);
    private final JButton emptyButton = new JButton();
    private final JProgressBar progress = new JProgressBar();
    private SwingWorker<List<MarkdownReader.Page>,Void> reader;
    private SwingWorker<String,Void> rendering;
    private final JEditorPane viewer=new JEditorPane("text/html","");
    private final JComboBox<MarkdownReader.Page> chapters=new JComboBox<>();
    private final JTabbedPane tabs=new JTabbedPane();
    private boolean loadingChapters;
    private boolean showImport;
    private List<String> activeWords = List.of();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("defaultFont", LibraryStyle.TYPE);
            UIManager.put("Component.focusColor", LibraryStyle.PURPLE);
            UIManager.put("TextComponent.arc", 12);
            UIManager.put("Component.arc", 10);
            new OfficeMarkdown().show();
        });
    }

    private static JTextArea area() {
        JTextArea text = new JTextArea();
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setFont(LibraryStyle.TYPE); text.setForeground(LibraryStyle.INK);
        return text;
    }

    private void show() {
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(LibraryStyle.CANVAS);
        JPanel head = LibraryStyle.panel(new BorderLayout(16,0), 16);
        head.setOpaque(true); head.setBackground(new Color(0x302846));
        JLabel brand = LibraryStyle.label("Office Markdown",20,true,Color.WHITE);
        brand.setIcon(LibraryStyle.document("",36,new Color(0xB69BFA))); brand.setIconTextGap(10);
        head.add(brand);
        JPanel imports = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,8,0),0);
        JButton shelf = new JButton("Markdown 폴더");
        JButton assistant = new JButton("문서에 질문");
        JButton agentSettings=new JButton("에이전트 설정");
        agentSettings.addActionListener(e->{
            if(AgentSettingsDialog.open(frame,AgentConnection.preferred())!=null && agentDialog!=null)
                agentDialog.selectDefault();
        });
        assistant.addActionListener(e -> {
            if(agentDialog==null || !agentDialog.isDisplayable()) agentDialog=new AgentDialog(frame,library,hit -> {
            model.clear(); activeWords=List.of(hit.excerpt()); model.addElement(hit);
            count.setText("답변 근거"); pages.show(contents,"results"); results.setSelectedIndex(0);
            },hit -> open(hit,false,false));
            agentDialog.setVisible(true);agentDialog.toFront();
        });
        for (JButton b : List.of(agentSettings,assistant,shelf,folder,add)) {
            LibraryStyle.button(b,b==add); imports.add(b);
        }
        head.add(imports,BorderLayout.EAST); body.add(head,BorderLayout.NORTH);

        JPanel workspace = LibraryStyle.panel(new BorderLayout(0,14),22);
        JPanel searchBox = LibraryStyle.card(new BorderLayout(0,10));
        JLabel heading = LibraryStyle.label("문서 검색",22,true,LibraryStyle.INK);
        heading.setLabelFor(query);
        JPanel options=LibraryStyle.panel(new BorderLayout(),0);
        options.add(heading);
        JPanel imageOptions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        JLabel imageLabel=new JLabel("가져오기 · 그림 글자 인식");
        imageLabel.setLabelFor(ocrMode); imageOptions.add(imageLabel); imageOptions.add(ocrMode);
        ocrMode.setToolTipText("PPTX·XLSX를 추가할 때 사용할 OCR 방식");
        options.add(imageOptions,BorderLayout.EAST); searchBox.add(options,BorderLayout.NORTH);
        JPanel bar = LibraryStyle.panel(new BorderLayout(8,0),0);
        query.putClientProperty("JTextField.placeholderText","파일 이름이나 문서 내용을 입력하세요");
        query.putClientProperty("JTextField.showClearButton",true);
        scope.setToolTipText("검색 범위"); scope.getAccessibleContext().setAccessibleName("검색 범위");
        mode.getAccessibleContext().setAccessibleName("검색어 조합");
        mode.setToolTipText("공백으로 나눈 단어를 같은 문서에서 찾는 방식");
        JPanel filters = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,4,0),0);
        filters.add(scope); filters.add(mode);
        bar.add(filters,BorderLayout.WEST); bar.add(query); bar.add(search,BorderLayout.EAST);
        searchBox.add(bar); workspace.add(searchBox,BorderLayout.NORTH);
        for (JButton b : List.of(search,original,locate,note,emptyButton)) LibraryStyle.button(b,b==search || b==original || b==emptyButton);

        JPanel left = LibraryStyle.card(new BorderLayout(0,10));
        left.add(count,BorderLayout.NORTH);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer(LibraryStyle.renderer()); results.setFixedCellHeight(64);
        results.setFixedCellWidth(240);
        results.getAccessibleContext().setAccessibleName("검색 결과");
        JScrollPane items = new JScrollPane(results); items.setBorder(null);
        items.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        left.add(items);

        JPanel right = LibraryStyle.card(new BorderLayout(0,12));
        JPanel names = LibraryStyle.panel(new GridLayout(0,1,0,4),0);
        names.add(sectionLabel); names.add(detailTitle); right.add(names,BorderLayout.NORTH);
        preview.setEditable(false); preview.setLineWrap(true); preview.setWrapStyleWord(true);
        preview.setFont(LibraryStyle.TYPE); preview.setMargin(new Insets(12,12,12,12));
        preview.setBackground(new Color(0xF8F7FB)); preview.setForeground(LibraryStyle.INK);
        JScrollPane reading = new JScrollPane(preview); reading.setBorder(null);
        viewer.setEditable(false); viewer.setBackground(Color.WHITE);
        viewer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true); viewer.setFont(LibraryStyle.TYPE.deriveFont(16f));
        viewer.getAccessibleContext().setAccessibleName("Markdown 문서 본문");
        chapters.getAccessibleContext().setAccessibleName("슬라이드·시트 이동");
        chapters.setMaximumRowCount(16);
        chapters.setPrototypeDisplayValue(new MarkdownReader.Page("슬라이드 또는 시트",1,""));
        chapters.addActionListener(e -> { if(!loadingChapters) renderChapter(); });
        JPanel document=LibraryStyle.panel(new BorderLayout(0,6),0);
        document.add(chapters,BorderLayout.NORTH); document.add(new JScrollPane(viewer));
        tabs.addTab("문서 보기",document); tabs.addTab("검색 근거",reading);
        right.add(tabs);
        JPanel lower = LibraryStyle.panel(new BorderLayout(0,6),0);
        lower.add(LibraryStyle.label("원본 위치",12,true,LibraryStyle.MUTED),BorderLayout.NORTH);
        pathText.setRows(1); pathText.setFont(LibraryStyle.TYPE.deriveFont(12f));
        JScrollPane location = new JScrollPane(pathText); location.setBorder(null); lower.add(location);
        JPanel actions = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,4,0),0);
        for (JButton b : List.of(original,note,locate)) actions.add(b);
        lower.add(actions,BorderLayout.SOUTH); right.add(lower,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,right);
        split.setBorder(null); split.setOpaque(false); split.setDividerSize(10); split.setResizeWeight(.32);
        left.setMinimumSize(new Dimension(215,160)); right.setMinimumSize(new Dimension(325,160));
        contents.setOpaque(false); contents.add(split,"results");
        JPanel blank = LibraryStyle.card(new GridBagLayout());
        JPanel prompt = LibraryStyle.panel(new GridBagLayout(),12);
        GridBagConstraints pos = new GridBagConstraints(); pos.gridx=0; pos.insets=new Insets(5,0,5,0);
        for (JComponent c : List.of(new JLabel(LibraryStyle.document("",64,LibraryStyle.PURPLE)),emptyTitle,emptyHint,emptyButton))
            prompt.add(c,pos);
        blank.add(prompt); contents.add(blank,"empty");
        workspace.add(contents); body.add(workspace);
        JPanel footer = LibraryStyle.panel(new BorderLayout(10,0),0);
        footer.setBorder(new EmptyBorder(0,24,12,24)); status.setForeground(LibraryStyle.MUTED);
        progress.setPreferredSize(new Dimension(90,5)); progress.setVisible(false);
        footer.add(status); footer.add(progress,BorderLayout.EAST); body.add(footer,BorderLayout.SOUTH);

        results.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selection(); });
        results.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount()==2 && SwingUtilities.isLeftMouseButton(e)) {
                    int index=results.locationToIndex(e.getPoint());
                    if(index>=0 && results.getCellBounds(index,index).contains(e.getPoint())) open(false,false);
                }
            }
        });
        key(body,"control F",() -> {query.requestFocusInWindow(); query.selectAll();});
        key(results,"ENTER",() -> open(false,false));
        add.addActionListener(e -> choose(false)); folder.addActionListener(e -> choose(true));
        shelf.addActionListener(e -> {
            try { Files.createDirectories(library); Desktop.getDesktop().open(library.toFile()); }
            catch (Exception ex) { error(ex); }
        });
        scope.addActionListener(e -> refresh(""));
        mode.addActionListener(e -> refresh(""));
        query.addActionListener(e -> { if (search.isEnabled()) refresh(""); });
        query.putClientProperty("JTextField.clearCallback",(java.util.function.Consumer<JTextField>)field -> {
            field.setText(""); if (search.isEnabled()) refresh("");
        });
        search.addActionListener(e -> refresh(""));
        original.addActionListener(e -> open(false,false)); locate.addActionListener(e -> open(false,true));
        note.addActionListener(e -> open(true,false));
        emptyButton.addActionListener(e -> {
            if (showImport) choose(false); else { query.setText(""); refresh(""); query.requestFocusInWindow(); }
        });
        body.setTransferHandler(new TransferHandler() {
            public boolean canImport(TransferSupport support) {
                return add.isEnabled() && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    var list=(List<?>)support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    importFiles(list.stream().filter(java.io.File.class::isInstance).map(java.io.File.class::cast).toList());
                    return true;
                } catch(Exception e) { error(e); return false; }
            }
        });
        for (JComponent target : List.of(contents,results,preview,blank)) target.setTransferHandler(body.getTransferHandler());
        frame.setContentPane(body); frame.setSize(1160,780); frame.setMinimumSize(new Dimension(720,490));
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); frame.setLocationRelativeTo(null);
        frame.setVisible(true); split.setDividerLocation(.32); query.requestFocusInWindow(); refresh("");
    }

    private void key(JComponent target,String key,Runnable action) {
        target.getInputMap(target==results ? JComponent.WHEN_FOCUSED : JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key),key);
        target.getActionMap().put(key,new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private void selection() {
        if (reader!=null) reader.cancel(true);
        if (rendering!=null) rendering.cancel(true);
        loadingChapters=true; chapters.removeAllItems(); loadingChapters=false;
        viewer.setText(""); tabs.setSelectedIndex(0);
        Library.Hit hit = results.getSelectedValue();
        for (JButton b : List.of(original, locate, note)) b.setEnabled(hit != null);
        if (hit==null) {preview.setText(""); pathText.setText(""); detailTitle.setText(""); sectionLabel.setText(""); return;}
        detailTitle.setText(hit.title()); detailTitle.setToolTipText(hit.title());
        sectionLabel.setText(hit.section()); pathText.setText(hit.source().toString()); pathText.setCaretPosition(0);
        preview.setText(hit.excerpt()); highlight(); preview.setCaretPosition(0);
        viewer.setText("문서를 불러오는 중…");
        reader=new SwingWorker<>() {
            protected List<MarkdownReader.Page> doInBackground() throws Exception { return MarkdownReader.read(hit.md()); }
            protected void done() {
                if(isCancelled() || results.getSelectedValue()!=hit) return;
                try {
                    var sections=get();
                    loadingChapters=true;
                    chapters.setModel(new DefaultComboBoxModel<>(sections.toArray(MarkdownReader.Page[]::new)));
                    int index=MarkdownReader.locate(sections,hit);
                    if(index==0 && sections.size()>1 && (hit.section().equals("제목") || hit.section().equals("원본 경로"))) index=1;
                    chapters.setSelectedIndex(index);
                    loadingChapters=false; renderChapter();
                } catch(Exception e) { loadingChapters=false; viewer.setText("문서를 읽을 수 없습니다."); }
            }
        };
        reader.execute();
    }

    private void renderChapter() {
        if(rendering!=null) rendering.cancel(true);
        var page=(MarkdownReader.Page)chapters.getSelectedItem();
        if(page==null) return;
        rendering=new SwingWorker<>() {
            protected String doInBackground() { return MarkdownReader.html(page); }
            protected void done() {
                if(isCancelled() || chapters.getSelectedItem()!=page) return;
                try {
                    viewer.setText(get()); viewer.setCaretPosition(0);
                    var doc=viewer.getDocument();
                    String visible=doc.getText(0,doc.getLength());
                    var paint=new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xE6DAFF));
                    var highlighter=viewer.getHighlighter(); highlighter.removeAllHighlights();
                    int first=-1;
                    for(String word:activeWords) {
                        if(word.isBlank()) continue;
                        var m=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(word),
                                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(visible);
                        while(m.find()) {
                            highlighter.addHighlight(m.start(),m.end(),paint);
                            if(first<0 || m.start()<first) first=m.start();
                        }
                    }
                    if(first>=0) viewer.setCaretPosition(first);
                } catch(Exception e) { viewer.setText("문서를 표시할 수 없습니다."); }
            }
        };
        rendering.execute();
    }

    private void highlight() {
        var marker = preview.getHighlighter();
        marker.removeAllHighlights();
        var paint = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xE6DAFF));
        try {
            for (String word : activeWords) {
                if (word.isEmpty()) continue;
                var match = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(word),
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                        .matcher(preview.getText());
                while (match.find()) marker.addHighlight(match.start(), match.end(), paint);
            }
        } catch (javax.swing.text.BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void open(boolean markdown, boolean reveal) {
        Library.Hit hit = results.getSelectedValue();
        if (hit != null) open(hit,markdown,reveal);
    }

    private void open(Library.Hit hit, boolean markdown, boolean reveal) {
        Path file = markdown ? hit.md() : hit.source();
        try {
            if (!Files.isRegularFile(file)) throw new IOException("파일을 찾을 수 없습니다.\n" + file);
            if (reveal) new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
            else if (markdown) new ProcessBuilder("notepad.exe", file.toString()).start();
            else if (Library.supported(file)) Desktop.getDesktop().open(file.toFile());
            else throw new IOException("PowerPoint 또는 Excel 원본 파일을 선택하세요.");
        } catch (Exception ex) { error(ex); }
    }

    private void error(Exception e) {
        JOptionPane.showMessageDialog(frame, e.getCause() == null ? e.toString() : e.getCause().toString(),
                "문서 찾기", JOptionPane.WARNING_MESSAGE);
    }

    private void busy(boolean value, String text) {
        for (JButton b : List.of(search, add, folder)) b.setEnabled(!value);
        scope.setEnabled(!value); emptyButton.setEnabled(!value); query.setEditable(!value);
        mode.setEnabled(!value);
        progress.setVisible(value); progress.setIndeterminate(value);
        status.setText(text);
    }

    private void refresh(String summary) {
        String needle = query.getText();
        var field = (SearchIndex.Scope) scope.getSelectedItem();
        var matchMode = (SearchIndex.Mode) mode.getSelectedItem();
        busy(true, "색인 확인 및 검색 중…");
        new SwingWorker<List<Library.Hit>, Void>() {
            protected List<Library.Hit> doInBackground() throws Exception { return SearchIndex.search(library, needle, field, matchMode, LIMIT); }
            protected void done() {
                busy(false, "");
                try {
                    var hits = get(); activeWords = matchMode.words(needle); model.clear(); model.addAll(hits);
                    if (!hits.isEmpty()) results.setSelectedIndex(0);
                    boolean all = needle.isBlank();
                    count.setText((all ? "문서 목록" : "검색 결과")+"   "+hits.size()+(hits.size()==LIMIT?"+":""));
                    showImport = all && hits.isEmpty();
                    emptyTitle.setText(showImport ? "첫 문서를 추가하세요" : "검색 결과가 없습니다");
                    emptyHint.setText(showImport ? "PowerPoint·Excel 파일이나 폴더를 이곳에 끌어오세요." : "검색어를 줄이거나 전체 문서 목록을 확인하세요.");
                    emptyButton.setText(showImport ? "파일 선택" : "전체 문서 보기");
                    pages.show(contents,hits.isEmpty()?"empty":"results");
                    status.setText(summary + (hits.isEmpty() ? "Ctrl+F 검색창으로 이동" : "Ctrl+F 검색창으로 이동     ·     결과를 두 번 클릭하면 원본 열기"));
                } catch (Exception e) { error(e); }
            }
        }.execute();
    }

    private void choose(boolean directory) {
        JFileChooser picker = new JFileChooser();
        if (directory) picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        else {
            picker.setMultiSelectionEnabled(true);
            picker.setFileFilter(new FileNameExtensionFilter("PowerPoint 및 Excel", "ppt", "pptx", "xls", "xlsx"));
        }
        if (picker.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        var inputs = directory ? List.of(picker.getSelectedFile()) : Arrays.asList(picker.getSelectedFiles());
        importFiles(inputs);
    }

    private void importFiles(List<java.io.File> inputs) {
        var engine=(ImageOcr.Mode)ocrMode.getSelectedItem();
        try { ImageOcr.validate(engine); } catch(IOException e) { error(e); return; }
        if(engine==ImageOcr.Mode.VISION && JOptionPane.showConfirmDialog(frame,
                "선택한 문서의 그림을 설정된 비전 API로 보냅니다.\n대상: "+
                System.getenv().getOrDefault("OPENAI_BASE_URL","https://api.openai.com/v1")+
                "\n모델: "+System.getenv("OFFICEMD_VISION_MODEL")+"\n계속할까요?",
                "비전 OCR",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        busy(true, "변환 준비 중…");
        new SwingWorker<Void, String>() {
            int converted;
            final List<String> failures = new ArrayList<>();
            void convert(Path path, ImageOcr ocr) {
                publish(path.getFileName().toString());
                try {
                    if (ocr!=null) ocr.warnings.clear();
                    Library.convert(path, library,ocr); converted++;
                    if (ocr!=null && !ocr.warnings.isEmpty())
                        failures.add(path+" · 변환 완료, 일부 그림 OCR 생략\n"+String.join("\n",ocr.warnings));
                }
                catch (Exception e) { failures.add(path + "\n" + e); }
            }
            protected Void doInBackground() throws Exception {
                try (var ocr=engine==ImageOcr.Mode.OFF ? null : new ImageOcr(engine,ImageOcr.scripts())) {
                    convertInputs(ocr);
                }
                return null;
            }
            void convertInputs(ImageOcr ocr) {
                for (var input : inputs) {
                    if (!input.isDirectory()) { convert(input.toPath(),ocr); continue; }
                    try {
                        Files.walkFileTree(input.toPath(), new SimpleFileVisitor<>() {
                            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                                if (attrs.isRegularFile() && Library.supported(path)) convert(path,ocr);
                                return FileVisitResult.CONTINUE;
                            }
                            public FileVisitResult visitFileFailed(Path path, IOException e) {
                                failures.add(path + "\n" + e); return FileVisitResult.CONTINUE;
                            }
                        });
                    } catch (IOException e) { failures.add(input + "\n" + e); }
                }
            }
            protected void process(List<String> names) { status.setText("변환 중 · " + names.getLast()); }
            protected void done() {
                try { get(); } catch (Exception e) { error(e); }
                if (!failures.isEmpty()) {
                    JTextArea log = new JTextArea(String.join("\n\n", failures), 10, 54);
                    log.setEditable(false); log.setLineWrap(true);
                    JOptionPane.showMessageDialog(frame, new JScrollPane(log), "변환 및 OCR 알림 " + failures.size() + "건", JOptionPane.WARNING_MESSAGE);
                }
                refresh("변환 " + converted + "건 · ");
            }
        }.execute();
    }
}
