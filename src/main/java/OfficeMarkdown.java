import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class OfficeMarkdown {
    private final Path library;

    public OfficeMarkdown() { this(SearchIndex.defaultLibrary()); }
    private OfficeMarkdown(Path library) {
        this.library = library;
        this.hero = SearchStudio.heading(headline);
    }
    private AgentDialog agentDialog;
    private final JPanel importOptions = LibraryStyle.panel(new GridLayout(0, 1, 0, 6), 4);
    private final JToggleButton keywordMode = new JToggleButton("키워드 검색", true);
    private final JToggleButton imageMode = new JToggleButton("이미지 검색");
    private final JToggleButton agentMode = new JToggleButton("에이전트 대화");
    private final JButton addDocuments = new JButton("문서 추가");
    private final JComboBox<ImageOcr.Mode> ocrMode=new JComboBox<>(ImageOcr.Mode.values());
    private final JFrame frame = new JFrame("검색 보관함 · Office Markdown");
    private final JTextField query = new JTextField(26);
    private final ReferencePanel referencePanel = new ReferencePanel(hit -> open(hit, false, false));
    private final JComboBox<SearchIndex.Scope> scope = new JComboBox<>(SearchIndex.Scope.values());
    private final JComboBox<SearchIndex.Mode> mode = new JComboBox<>(SearchIndex.Mode.values());
    private final JButton search = new JButton("검색"), add = new JButton("파일 추가"), folder = new JButton("폴더 추가");
    private final JButton enrichPictures=new JButton("그림 검색 색인 보강");
    private final JButton findBrokenOcr = new JButton("OCR 깨짐 점검");
    private final JButton repairAll = new JButton("후보 전체 정밀 OCR");
    private final JButton stopRepair = new JButton("현재 문서 후 중단");
    private volatile boolean stopRequested;
    private final JButton resumeRepair=new JButton("남은 문서 계속");
    private List<Path> remainingNotes=List.of();
    private List<Library.Hit> remainingPictures=List.of();
    private final JButton roleButton = new JButton("문서 역할");
    private final JButton repairOcr = new JButton("선택 그림 재인식");
    private final JButton largeOptions = new JButton("대용량 색인 관리");
    private final JButton ocrOnly = new JButton("OCR만 실행");
    private final JCheckBox latestOnly = new JCheckBox("폴더별 최신본만");
    private final JCheckBox reconvertSameName = new JCheckBox("같은 파일명 다시 변환", true);
    private static final int AUTOMATIC_WORKERS = 0;
    private final JComboBox<Integer> conversionWorkers = new JComboBox<>(new Integer[]{1, 2, AUTOMATIC_WORKERS});
    private final JButton original = new JButton("원본 열기"), locate = new JButton("파일 위치"), note = new JButton("Markdown 뷰어");
    private final JButton pictureLocation = new JButton("MD 위치 보기");
    private final DefaultListModel<Library.Hit> model = new DefaultListModel<>();
    private final JList<Library.Hit> results = new JList<>(model);
    private final JList<Library.Hit> gallery = new JList<>(model);
    private final JLabel galleryTitle = SearchStudio.sectionTitle();
    private final JLabel headline = SearchStudio.headline("보관함에서","무엇을 찾을까요?");
    private final JPanel hero;
    private final JButton backToGallery = new JButton("카드 목록");
    private boolean readingCard;
    private final JPanel recentQueries = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,5,0),0);
    private final LinkedHashSet<String> recentTerms = new LinkedHashSet<>();
    private boolean pictureInput;
    private SearchIndex.Scope keywordScope = SearchIndex.Scope.ALL;
    private String keywordDraft = "", pictureDraft = "";
    private final JTextArea preview = new JTextArea(6, 50);
    private final JLabel status = new JLabel(" ");
    private final JLabel indexStatus = new JLabel(" ");
    private final JLabel updateStatus = new JLabel();
    private Path lastConversion;
    private final JButton conversionResult=new JButton("변환 결과 보기");
    private final JButton syncIndex = new JButton("색인 새로 고침");
    private static final int LIMIT = 500;

    private final JLabel count = LibraryStyle.label("문서 목록", 16, true, LibraryStyle.INK);
    private final JLabel detailTitle = LibraryStyle.label("", 18, true, LibraryStyle.INK);
    private final JLabel sectionLabel = LibraryStyle.label("", 13, true, LibraryStyle.ACCENT);
    private final JTextArea pathText = area();
    private final CardLayout pages = new CardLayout();
    private final JPanel contents = new JPanel(pages);
    private final JLabel emptyTitle = LibraryStyle.label("", 22, true, LibraryStyle.INK);
    private final JLabel emptyHint = LibraryStyle.label("", 14, false, LibraryStyle.MUTED);
    private final JButton emptyButton = new JButton();
    private final JProgressBar progress = new JProgressBar();
    private SwingWorker<List<MarkdownReader.Page>,Void> reader;
    private SwingWorker<String,Void> rendering;
    private SwingWorker<Icon,Void> loadingPicture;
    private SwingWorker<Void,Thumbnail> loadingThumbnails;
    private SwingWorker<List<Library.Hit>,Void> searchWorker;
    private SwingWorker<Void,SearchIndex.Progress> indexWorker;
    private final Map<Library.Hit,Icon> thumbnails = new HashMap<>();
    private final Map<Library.Hit,Icon> covers = new HashMap<>();
    private static final int COVER_PREVIEWS = 60;
    private final JLabel picturePreview = LibraryStyle.label("", 14, false, LibraryStyle.MUTED);
    private final JEditorPane viewer=new JEditorPane("text/html","");
    private final JComboBox<MarkdownReader.Page> chapters=new JComboBox<>();
    private final JTabbedPane tabs=new JTabbedPane();
    private boolean loadingChapters;
    private boolean showImport;
    private enum ResultView { DOCUMENT, PICTURE, BROKEN_OCR }
    private ResultView resultView = ResultView.DOCUMENT;
    private boolean working;
    private boolean startupSyncStarted;
    private SearchRequest lastSearch;
    private SearchRequest displayedSearch;
    private String lastSearchSummary = "";
    private List<String> activeWords = List.of();

    private record Thumbnail(Library.Hit hit, Icon icon, boolean cover) {}
    private record SearchRequest(String needle, SearchIndex.Scope scope, SearchIndex.Mode mode, boolean brokenOcr) {}

    public static void main(String[] args) {
        Path library = args.length == 2 && args[0].equals("--library")
                ? Path.of(args[1]).toAbsolutePath().normalize() : SearchIndex.defaultLibrary();
        SwingUtilities.invokeLater(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            UIManager.put("defaultFont", LibraryStyle.TYPE);
            UIManager.put("Component.focusColor", LibraryStyle.ACCENT);
            UIManager.put("TextComponent.arc", 12);
            UIManager.put("Component.arc", 10);
            new OfficeMarkdown(library).show();
        });
    }

    private static JTextArea area() {
        JTextArea text = new JTextArea();
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true);
        text.setFont(LibraryStyle.TYPE); text.setForeground(LibraryStyle.INK);
        return text;
    }

    private void show() {
        var iconUrl = OfficeMarkdown.class.getResource("/OfficeMarkdown.png");
        if (iconUrl != null) frame.setIconImage(new ImageIcon(iconUrl).getImage());
        var updates = new UpdateDialog(frame, () -> working || indexWorker != null || referencePanel.isWorking() ||
                (agentDialog != null && agentDialog.isAnswering()),
                () -> frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING)),
                message -> {
                    updateStatus.setText(message);
                    updateStatus.setVisible(message != null && !message.isBlank());
                });
        JMenu updateMenu = new JMenu("업데이트");
        JMenuItem checkUpdate = new JMenuItem("업데이트 확인");
        checkUpdate.addActionListener(e -> updates.checkManually());
        updateMenu.add(checkUpdate);
        updateMenu.add(updates.autoCheckItem());
        JMenuBar menus = new JMenuBar();
        menus.add(updateMenu);
        frame.setJMenuBar(menus);
        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(LibraryStyle.CANVAS);
        JPanel head = LibraryStyle.panel(new BorderLayout(16,0), 16);
        head.setOpaque(true); head.setBackground(Color.WHITE);
        JButton brand = new JButton("OfficeMarkdown");
        brand.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD,19f));
        if (iconUrl != null) brand.setIcon(new ImageIcon(frame.getIconImage().getScaledInstance(32,32,Image.SCALE_SMOOTH)));
        brand.setBorderPainted(false);
        brand.setContentAreaFilled(false);
        brand.setToolTipText("보관함 처음으로");
        brand.addActionListener(e -> {
            if (working) return;
            readingCard = false;
            keywordDraft = "";
            agentMode.doClick();
            query.setText("");
            refresh("");
        });
        head.add(brand,BorderLayout.WEST);
        JPanel imports = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,8,0),0);
        var views = new CardLayout();
        var searchViews = new JPanel(views);
        searchViews.setOpaque(false);
        JButton agentSettings=new JButton("에이전트 연결 설정");
        agentSettings.addActionListener(e->{
            if(AgentSettingsDialog.open(frame,AgentConnection.preferred())!=null && agentDialog!=null) {
                agentDialog.selectDefault();
                referencePanel.reset();
            }
        });
        agentDialog = new AgentDialog(library,hit -> {
            if (working) {
                JOptionPane.showMessageDialog(agentDialog,"현재 변환이 끝나면 문서 위치를 열 수 있습니다.");
                return;
            }
            scope.setSelectedItem(hit.picture() == null ? SearchIndex.Scope.ALL : SearchIndex.Scope.IMAGE);
            (hit.picture() == null ? keywordMode : imageMode).setSelected(true);
            views.show(searchViews,"local");
            referencePanel.clearSearch();
            updateReferenceContext();
            cancelSearch();
            readingCard = true;
            hero.setVisible(false);
            backToGallery.setVisible(false);
            recentQueries.setVisible(false);
            lastSearch = null;
            displayedSearch = null;
            status.setText("답변 근거");
            setResultView(hit.picture()==null ? ResultView.DOCUMENT : ResultView.PICTURE);
            model.clear(); activeWords=List.of(hit.excerpt()); model.addElement(hit);
            count.setText("답변 근거"); pages.show(contents,"results"); results.setSelectedIndex(0);
            },hit -> open(hit,false,false,agentDialog));
        var toolsMenu = new JMenu("보관함");
        menus.add(toolsMenu);
        var openFolder = toolsMenu.add("Markdown 폴더");
        toolsMenu.add("Markdown 파일 보기").addActionListener(e->chooseMarkdown(null));
        toolsMenu.add("저품질 OCR 후보 찾기").addActionListener(e->findBrokenOcr.doClick());
        toolsMenu.add("여러 문서 한글 정밀 OCR").addActionListener(e->{if(!working) choosePrecisionNotes();});
        var register = new JButton("MCP 설정");
        register.addActionListener(e -> McpRegistration.open(frame, register));
        var addMenu = new JPopupMenu();
        for (var button : List.of(add, folder)) {
            addMenu.add(button.getText()).addActionListener(e -> button.doClick());
        }
        addDocuments.addActionListener(e -> addMenu.show(addDocuments, 0, addDocuments.getHeight()));
        for (JButton b : List.of(register,addDocuments)) {
            LibraryStyle.button(b,b==addDocuments); imports.add(b);
        }
        addDocuments.setBackground(SearchStudio.CHARCOAL);
        head.add(imports,BorderLayout.EAST); body.add(head,BorderLayout.NORTH);

        JPanel workspace = LibraryStyle.panel(new BorderLayout(0,14),22);
        JPanel searchBox = LibraryStyle.panel(new BorderLayout(0,8),4);
        headline.setLabelFor(query);
        var track = new SearchStudio.Round(new FlowLayout(FlowLayout.CENTER, 2, 0), 36, SearchStudio.TRACK, null, 0);
        track.setBorder(new EmptyBorder(4,4,4,4));
        var group = new ButtonGroup();
        for (var button : List.of(keywordMode, imageMode, agentMode)) {
            button.putClientProperty("JButton.buttonType","roundRect");
            button.putClientProperty("FlatLaf.style","arc: 999; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;"
                    + " background: #ebe8dc; hoverBackground: #e2ddcc; pressedBackground: #dcd6c2;"
                    + (button==agentMode?" selectedBackground: #b65612; selectedForeground: #ffffff;":" selectedBackground: #ffffff; selectedForeground: #25251f;")
                    + " borderColor: #ebe8dc;"
                    + " focusedBorderColor: #ebe8dc");
            button.setMargin(new Insets(6,14,6,14));
            button.setFont(LibraryStyle.TYPE.deriveFont(13f));
            button.addItemListener(e -> button.setFont(LibraryStyle.TYPE.deriveFont(
                    button.isSelected() ? Font.BOLD : Font.PLAIN, 13f)));
            group.add(button);
            track.add(button);
        }
        keywordMode.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD, 13f));
        var modes = LibraryStyle.panel(new GridBagLayout(), 0);
        modes.add(track);
        head.add(modes,BorderLayout.CENTER);
        agentMode.addActionListener(e -> {
            views.show(searchViews,"agent");
            updateReferenceContext();
            agentDialog.focusQuestion();
        });
        keywordMode.addActionListener(e -> {
            views.show(searchViews,"local");
            if (scope.getSelectedItem() == SearchIndex.Scope.IMAGE) scope.setSelectedItem(keywordScope);
            else if (lastSearch == null) refresh(""); // Leave the single agent citation and restore search results.
            updateReferenceContext();
            query.requestFocusInWindow();
        });
        imageMode.addActionListener(e -> {
            views.show(searchViews,"local");
            scope.setSelectedItem(SearchIndex.Scope.IMAGE);
            updateReferenceContext();
            query.requestFocusInWindow();
        });
        JPanel imageOptions=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        JLabel imageLabel=new JLabel("그림 글자 인식");
        imageLabel.setLabelFor(ocrMode); imageOptions.add(imageLabel); imageOptions.add(ocrMode);
        ocrMode.setToolTipText("그림을 인식할 때 사용할 OCR 방식");
        JLabel parallelLabel = new JLabel("동시 변환");
        parallelLabel.setLabelFor(conversionWorkers);
        conversionWorkers.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focused) {
                Object label = Objects.equals(value, AUTOMATIC_WORKERS) ? "자동(CPU·메모리)" : value;
                return super.getListCellRendererComponent(list, label, index, selected, focused);
            }
        });
        conversionWorkers.setPrototypeDisplayValue(AUTOMATIC_WORKERS);
        imageOptions.add(parallelLabel);
        imageOptions.add(conversionWorkers);

        ocrOnly.setToolTipText("변환본에 저장된 그림의 OCR만 실행합니다.");
        ocrMode.setSelectedItem(ImageOcr.Mode.OFF);
        ocrMode.addActionListener(e -> updateWorkerChoice());
        updateWorkerChoice();
        JPanel batchOptions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        reconvertSameName.setToolTipText("끄면 폴더 위치와 관계없이 같은 이름과 확장자의 변환 결과가 있는 파일을 건너뜁니다.");
        latestOnly.setToolTipText("같은 폴더의 버전·날짜별 문서를 비교해 변환 대상을 확인합니다.");
        batchOptions.add(latestOnly);
        batchOptions.add(reconvertSameName);

        importOptions.add(imageOptions);
        importOptions.add(batchOptions);
        searchBox.add(hero, BorderLayout.NORTH);
        JPanel bar = new SearchStudio.Round(new BorderLayout(8,0), 14, Color.WHITE, new Color(0xD6D2C6), 5);
        query.putClientProperty("JTextField.placeholderText","파일 이름이나 문서 내용을 입력하세요");
        query.putClientProperty("JTextField.showClearButton",true);
        query.setFont(LibraryStyle.TYPE.deriveFont(17f));
        query.putClientProperty("FlatLaf.style","borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; background: #ffffff");
        scope.setToolTipText("검색 범위"); scope.getAccessibleContext().setAccessibleName("검색 범위");
        mode.getAccessibleContext().setAccessibleName("검색어 조합");
        mode.setToolTipText("공백으로 나눈 단어를 같은 문서에서 찾는 방식");
        JPanel filters = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,4,0),0);
        filters.add(scope); filters.add(mode);
        JPanel searchActions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        findBrokenOcr.setToolTipText("저장된 그림에서 깨짐이 의심되는 글자 인식 결과를 찾습니다.");
        searchActions.add(search);
        bar.setBorder(new EmptyBorder(6,8,11,8));
        bar.add(query);
        searchActions.add(filters,0);
        bar.add(searchActions,BorderLayout.EAST);
        searchBox.add(bar);
        recentQueries.setVisible(false);
        var searchOptions = LibraryStyle.panel(new BorderLayout(0, 4), 0);
        searchOptions.add(referencePanel.controls(), BorderLayout.NORTH);
        searchOptions.add(recentQueries, BorderLayout.SOUTH);
        searchBox.add(searchOptions, BorderLayout.SOUTH);
        var localSearch = LibraryStyle.panel(new BorderLayout(0, 10),0);
        localSearch.add(searchBox, BorderLayout.NORTH);
        localSearch.add(contents);
        localSearch.add(referencePanel, BorderLayout.SOUTH);
        searchViews.add(localSearch, "local");
        searchViews.add(agentDialog, "agent");
        for (JButton b : List.of(search,findBrokenOcr,repairOcr,ocrOnly,largeOptions,original,locate,note,pictureLocation,emptyButton))
            LibraryStyle.button(b,b==search || b==original || b==emptyButton);
        search.putClientProperty("JButton.buttonType", null);
        search.putClientProperty("FlatLaf.style", "arc: 10; borderWidth: 0; focusWidth: 0; background: #c34422; hoverBackground: #a6371b");
        search.setToolTipText("검색 (Enter)");

        JPanel left = LibraryStyle.card(new BorderLayout(0,10));
        JPanel resultHeader = LibraryStyle.panel(new BorderLayout(0,6),0);
        resultHeader.add(count,BorderLayout.NORTH);

        repairOcr.setVisible(false);
        repairOcr.setToolTipText("목록에서 선택한 그림을 로컬 PaddleOCR로 다시 읽습니다. Ctrl 또는 Shift로 여러 그림을 선택할 수 있습니다.");
        var resultTools = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,4,0),0);
        resultTools.add(backToGallery);
        resultTools.add(repairOcr);
        resultTools.add(repairAll);
        repairAll.setVisible(false);
        repairAll.addActionListener(e->startRepair(List.of(),List.of(),true));
        stopRepair.setVisible(false);
        stopRepair.addActionListener(e->{stopRequested=true;stopRepair.setEnabled(false);});
        imports.add(stopRepair);
        resumeRepair.setVisible(false);
        resumeRepair.addActionListener(e->startRepair(remainingPictures,remainingNotes,false));
        imports.add(resumeRepair);
        resultHeader.add(resultTools, BorderLayout.SOUTH);
        backToGallery.setVisible(false);
        backToGallery.addActionListener(e -> {
            readingCard = false;
            showGallery();
            gallery.requestFocusInWindow();
        });
        left.setBackground(SearchStudio.CHARCOAL);
        count.setForeground(Color.WHITE);
        results.setBackground(SearchStudio.CHARCOAL);
        left.add(resultHeader,BorderLayout.NORTH);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer(LibraryStyle.renderer(thumbnails::get)); results.setFixedCellHeight(70);
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
        preview.setBackground(new Color(0xFBF9F3)); preview.setForeground(LibraryStyle.INK);
        JScrollPane reading = new JScrollPane(preview); reading.setBorder(null);
        viewer.setEditable(false); viewer.setBackground(Color.WHITE);
        viewer.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true); viewer.setFont(LibraryStyle.TYPE.deriveFont(16f));
        viewer.getAccessibleContext().setAccessibleName("Markdown 문서 본문");
        chapters.getAccessibleContext().setAccessibleName("슬라이드·시트 이동");
        chapters.setMaximumRowCount(16);
        chapters.setPrototypeDisplayValue(new MarkdownReader.Page("슬라이드 또는 시트",1,""));
        chapters.addActionListener(e -> { if(!loadingChapters) renderChapter(); });
        JPanel document=LibraryStyle.panel(new BorderLayout(0,6),0);
        var viewerScroll = new JScrollPane(viewer);
        viewerScroll.setBorder(BorderFactory.createMatteBorder(1,0,0,0,LibraryStyle.LINE));
        document.add(chapters,BorderLayout.NORTH); document.add(viewerScroll);
        tabs.addTab("문서 보기",document); tabs.addTab("검색 근거",reading);
        picturePreview.setHorizontalAlignment(SwingConstants.CENTER);
        picturePreview.setVerticalAlignment(SwingConstants.CENTER);
        picturePreview.getAccessibleContext().setAccessibleName("검색된 그림 미리보기");
        tabs.addTab("그림 미리보기",new JScrollPane(picturePreview));
        tabs.setEnabledAt(2,false);
        tabs.setOpaque(false);
        tabs.putClientProperty("FlatLaf.style","tabType: card; tabHeight: 34; tabArc: 8; cardTabArc: 8;"
                + " background: #ffffff; selectedBackground: #292b24; selectedForeground: #ffffff;"
                + " hoverColor: #eee9da; contentAreaColor: #d9d6c8; cardTabSelectionHeight: 0; showTabSeparators: false");
        right.add(tabs);
        JPanel lower = LibraryStyle.panel(new BorderLayout(0,6),0);
        lower.add(LibraryStyle.label("원본 위치",12,true,LibraryStyle.MUTED),BorderLayout.NORTH);
        pathText.setRows(1); pathText.setFont(LibraryStyle.TYPE.deriveFont(12f));
        JScrollPane location = new JScrollPane(pathText); location.setBorder(null); lower.add(location);
        JPanel actions = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,4,0),0);
        for (JButton b : List.of(original,pictureLocation,note,roleButton,locate)) actions.add(b);
        pictureLocation.setVisible(false);
        lower.add(actions,BorderLayout.SOUTH); right.add(lower,BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,left,right);
        split.setBorder(null); split.setOpaque(false); split.setDividerSize(8); split.setResizeWeight(.32);
        split.putClientProperty("FlatLaf.style","gripDotCount: 0");
        left.setMinimumSize(new Dimension(215,160)); right.setMinimumSize(new Dimension(325,160));
        gallery.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        gallery.setVisibleRowCount(-1);
        gallery.setFixedCellWidth(245);
        gallery.setFixedCellHeight(152);
        gallery.setBackground(LibraryStyle.CANVAS);
        gallery.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gallery.setCellRenderer(SearchStudio.covers(hit -> covers.getOrDefault(hit, thumbnails.get(hit))));
        gallery.getAccessibleContext().setAccessibleName("보관함 카드");
        gallery.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int i = gallery.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && i >= 0
                        && gallery.getCellBounds(i,i).contains(event.getPoint())) readCard(i);
            }
        });
        key(gallery,"ENTER",() -> readCard(gallery.getSelectedIndex()));
        var galleryPage = LibraryStyle.panel(new BorderLayout(0,8),0);
        galleryPage.add(galleryTitle,BorderLayout.NORTH);
        var galleryScroll = new JScrollPane(gallery);
        galleryScroll.setBorder(null);
        galleryScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){
            @Override public void componentResized(java.awt.event.ComponentEvent event){
                int width = galleryScroll.getViewport().getExtentSize().width;
                if (width > 0) gallery.setFixedCellWidth(width / Math.clamp(width/260,1,3));
            }
        });
        galleryPage.add(galleryScroll);
        contents.add(galleryPage,"gallery");
        contents.setOpaque(false); contents.add(split,"results");
        JPanel blank = LibraryStyle.card(new GridBagLayout());
        JPanel prompt = LibraryStyle.panel(new GridBagLayout(),12);
        GridBagConstraints pos = new GridBagConstraints(); pos.gridx=0; pos.insets=new Insets(5,0,5,0);
        for (JComponent c : List.of(new JLabel(LibraryStyle.document("",64,LibraryStyle.ACCENT)),emptyTitle,emptyHint,emptyButton))
            prompt.add(c,pos);
        blank.add(prompt); contents.add(blank,"empty");
        workspace.add(searchViews); body.add(workspace);
        JPanel footer = LibraryStyle.panel(new BorderLayout(0,4),0);
        footer.setBorder(new EmptyBorder(0,24,12,24)); status.setForeground(LibraryStyle.MUTED);
        JPanel statusLine = LibraryStyle.panel(new BorderLayout(10,0),0);
        progress.setPreferredSize(new Dimension(170,18)); progress.setVisible(false);
        statusLine.add(status); statusLine.add(progress,BorderLayout.EAST); footer.add(statusLine,BorderLayout.NORTH);
        JPanel indexLine = LibraryStyle.panel(new BorderLayout(10,0),0);
        indexStatus.setForeground(LibraryStyle.MUTED);
        syncIndex.setToolTipText("Markdown 폴더의 변경 내용을 색인에 반영합니다.");
        LibraryStyle.button(syncIndex, false);
        syncIndex.addActionListener(e -> {
            referencePanel.clearSearch();
            startIndexSync();
        });
        JPanel indexActions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0), 0);
        var maintenance = new JPopupMenu();
        for (var action : List.of(enrichPictures,ocrOnly,findBrokenOcr,largeOptions,syncIndex)) {
            var item = maintenance.add(action.getText());
            action.addPropertyChangeListener("enabled",e -> item.setEnabled(action.isEnabled()));
            item.setEnabled(action.isEnabled());
            item.addActionListener(e -> action.doClick());
        }
        var manage = new JButton("보관함 관리");
        manage.addActionListener(e -> maintenance.show(manage,0,-maintenance.getPreferredSize().height));
        for (JButton b : List.of(agentSettings, manage)) {
            b.putClientProperty("JButton.buttonType","borderless");
            b.setForeground(LibraryStyle.MUTED);
            b.setFont(LibraryStyle.TYPE.deriveFont(12f));
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        conversionResult.setVisible(false);
        conversionResult.addActionListener(e->{
            if(lastConversion==null)chooseMarkdown(null);
            else MarkdownWindow.open(frame,lastConversion,null);
        });
        indexActions.add(conversionResult);
        indexActions.add(agentSettings);
        indexActions.add(LibraryStyle.label("·",12,false,LibraryStyle.MUTED));
        indexActions.add(manage);

        largeOptions.addActionListener(e -> manageLarge());
        ocrOnly.addActionListener(e -> chooseOcrNotes());
        indexLine.add(indexStatus); indexLine.add(indexActions,BorderLayout.EAST); footer.add(indexLine,BorderLayout.SOUTH);
        updateStatus.setForeground(LibraryStyle.MUTED);
        updateStatus.setVisible(false);
        footer.add(updateStatus, BorderLayout.CENTER);
        body.add(footer,BorderLayout.SOUTH);

        results.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selection(); });
        results.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount()==2 && SwingUtilities.isLeftMouseButton(e)) {
                    int index=results.locationToIndex(e.getPoint());
                    if(index>=0 && results.getCellBounds(index,index).contains(e.getPoint())) open(true,false);
                }
            }
        });
        key(body,"control F",() -> {
            if (agentMode.isSelected()) { agentDialog.focusQuestion(); return; }
            query.requestFocusInWindow();
            query.selectAll();
        });
        key(results,"ENTER",() -> open(true,false));
        add.addActionListener(e -> choose(false)); folder.addActionListener(e -> choose(true));
        openFolder.addActionListener(e -> {
            try { Files.createDirectories(library); Desktop.getDesktop().open(library.toFile()); }
            catch (Exception ex) { error(ex); }
        });
        scope.addActionListener(e -> {
            referencePanel.clearSearch();
            boolean nextPicture = scope.getSelectedItem() == SearchIndex.Scope.IMAGE;
            if (!nextPicture) keywordScope = (SearchIndex.Scope)scope.getSelectedItem();
            if (nextPicture != pictureInput) {
                if (pictureInput) pictureDraft = query.getText();
                else keywordDraft = query.getText();
                pictureInput = nextPicture;
                query.setText(nextPicture ? pictureDraft : keywordDraft);
            }
            if (!agentMode.isSelected())
                (scope.getSelectedItem() == SearchIndex.Scope.IMAGE ? imageMode : keywordMode).setSelected(true);
            updateReferenceContext();
            query.putClientProperty("JTextField.placeholderText",scope.getSelectedItem() == SearchIndex.Scope.IMAGE
                    ? "그림 속 글자나 그림 설명을 입력하세요" : "파일 이름이나 문서 내용을 입력하세요");
            if (search.isEnabled() && !agentMode.isSelected()) refresh("");
        });
        mode.addActionListener(e -> {
            referencePanel.clearSearch();
            if (search.isEnabled()) refresh("");
        });
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { referencePanel.clearSearch(); }
            public void removeUpdate(DocumentEvent e) { referencePanel.clearSearch(); }
            public void changedUpdate(DocumentEvent e) { referencePanel.clearSearch(); }
        });
        query.addActionListener(e -> { if (search.isEnabled()) submitQuery(); });
        query.putClientProperty("JTextField.clearCallback",(java.util.function.Consumer<JTextField>)field -> {
            field.setText(""); if (search.isEnabled()) refresh("");
        });
        search.addActionListener(e -> submitQuery());
        findBrokenOcr.addActionListener(e -> {
            imageMode.doClick();
            refreshBrokenOcr("");
        });
        enrichPictures.addActionListener(e->enrichPictureSearch());
        repairOcr.addActionListener(e -> repairSelectedOcr());
        original.addActionListener(e -> open(false,false)); locate.addActionListener(e -> open(false,true));
        roleButton.addActionListener(e->editRole());
        note.addActionListener(e -> open(true,false));
        pictureLocation.addActionListener(e -> {
            var hit = selectedHit();
            if (hit == null || hit.picture() == null) return;
            tabs.setSelectedIndex(0);
            var current = (MarkdownReader.Page) chapters.getSelectedItem();
            if (current == null) return;
            for (int i = 0; i < chapters.getItemCount(); i++) {
                if (MarkdownReader.containsPicture(chapters.getItemAt(i),hit.picture())) {
                    if (i == chapters.getSelectedIndex()) scrollToPicture();
                    else chapters.setSelectedIndex(i);
                    break;
                }
            }
        });
        emptyButton.addActionListener(e -> {
            if (showImport) choose(false);
            else {
                query.setText("");
                if (scope.getSelectedItem() == SearchIndex.Scope.IMAGE) {
                    keywordDraft="";
                    scope.setSelectedItem(SearchIndex.Scope.ALL);
                } else refresh("");
                query.requestFocusInWindow();
            }
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
        for (JComponent target : List.of(contents,results,gallery,preview,blank)) target.setTransferHandler(body.getTransferHandler());
        frame.setContentPane(body); frame.setSize(1160,780); frame.setMinimumSize(new Dimension(720,490));
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE); frame.setLocationRelativeTo(null);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { referencePanel.reset(); }
        });
        frame.setVisible(true); split.setDividerLocation(.32); agentMode.doClick(); refresh("");
        updates.checkOnStartup();
    }

    private void key(JComponent target,String key,Runnable action) {
        target.getInputMap(target==results || target==gallery ? JComponent.WHEN_FOCUSED : JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key),key);
        target.getActionMap().put(key,new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private Library.Hit selectedHit() {
        int lead = results.getLeadSelectionIndex();
        if (lead >= 0 && lead < model.size() && results.isSelectedIndex(lead)) return model.get(lead);
        return results.getSelectedValue();
    }

    private void selection() {
        if (reader!=null) reader.cancel(true);
        if (rendering!=null) rendering.cancel(true);
        loadingChapters=true; chapters.removeAllItems(); loadingChapters=false;
        if (loadingPicture!=null) loadingPicture.cancel(true);
        picturePreview.setIcon(null); picturePreview.setText("");
        viewer.setText("");
        Library.Hit hit = selectedHit();
        boolean picture = hit != null && hit.picture() != null;
        repairOcr.setEnabled(resultView != ResultView.DOCUMENT && !working && picture);
        tabs.setEnabledAt(2,picture);
        tabs.setSelectedIndex(picture ? 2 : 0);
        pictureLocation.setVisible(picture);
        for (JButton b : List.of(original, locate, note)) b.setEnabled(hit != null);
        if (hit==null) {preview.setText(""); pathText.setText(""); detailTitle.setText(""); sectionLabel.setText(""); return;}
        detailTitle.setText(hit.title()); detailTitle.setToolTipText(hit.title());
        String location=picture ? "그림 " + hit.picture().number() + " · " + hit.picture().location() : hit.section();
        if(picture && resultView==ResultView.BROKEN_OCR)location+=" · "+OcrRepair.qualityReason(hit.excerpt(),hit.picture().location());
        sectionLabel.setText(location);
        pathText.setText(hit.source().toString()); pathText.setCaretPosition(0);
        preview.setText(hit.displayExcerpt()); highlight(); preview.setCaretPosition(0);
        viewer.setText("문서를 불러오는 중…");
        if (picture) loadPicture(hit);
        roleButton.setText("문서 역할");
        reader=new SwingWorker<>() {
            private String role;
            protected List<MarkdownReader.Page> doInBackground() throws Exception {
                try {role=SearchIndex.documentRole(hit.md());} catch(IOException ignored){role="확인 불가";}
                return MarkdownReader.read(hit.md());
            }
            protected void done() {
                if(isCancelled() || selectedHit()!=hit) return;
                try {
                    var sections=get();
                    roleButton.setText("역할 · "+role);
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
        var hit = selectedHit();
        var picture = hit == null ? null : hit.picture();
        rendering=new SwingWorker<>() {
            protected String doInBackground() { return MarkdownReader.html(page,picture,hit==null?null:hit.md()); }
            protected void done() {
                if(isCancelled() || chapters.getSelectedItem()!=page) return;
                try {
                    viewer.setText(get()); viewer.setCaretPosition(0);
                    var doc=viewer.getDocument();
                    String visible=doc.getText(0,doc.getLength());
                    var paint=new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xF7D898));
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
                    if (picture != null) scrollToPicture();
                } catch(Exception e) { viewer.setText("문서를 표시할 수 없습니다."); }
            }
        };
        rendering.execute();
    }

    private void scrollToPicture() {
        if (!(viewer.getDocument() instanceof javax.swing.text.html.HTMLDocument doc)) return;
        var anchors = doc.getIterator(javax.swing.text.html.HTML.Tag.A);
        while (anchors.isValid()) {
            if (MarkdownReader.PICTURE_ANCHOR.equals(anchors.getAttributes().getAttribute(javax.swing.text.html.HTML.Attribute.NAME))) {
                viewer.setCaretPosition(Math.min(anchors.getStartOffset(),doc.getLength()));
                SwingUtilities.invokeLater(() -> viewer.scrollToReference(MarkdownReader.PICTURE_ANCHOR));
                return;
            }
            anchors.next();
        }
    }

    private void loadPicture(Library.Hit hit) {
        picturePreview.setText("그림을 불러오는 중…");
        loadingPicture = new SwingWorker<>() {
            protected Icon doInBackground() {
                var image = PictureAssets.preview(hit.md(),hit.picture(),false);
                return image == null ? null : new ImageIcon(image);
            }
            protected void done() {
                if (isCancelled() || selectedHit() != hit) return;
                Icon icon = null;
                try { icon = get(); }
                catch (java.util.concurrent.ExecutionException ignored) {}
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                picturePreview.setIcon(icon);
                picturePreview.setText(icon == null ? "그림 미리보기를 표시할 수 없습니다." : "");
            }
        };
        loadingPicture.execute();
    }

    private void loadThumbnails(List<Library.Hit> hits) {
        if (loadingThumbnails != null) loadingThumbnails.cancel(true);
        thumbnails.clear();
        covers.clear();
        var pictures = hits.stream().filter(hit -> hit.picture() != null).toList();
        if (pictures.isEmpty()) return;
        loadingThumbnails = new SwingWorker<>() {
            protected Void doInBackground() {
                int index = 0;
                for (var hit : pictures) {
                    if (isCancelled()) break;
                    var image = PictureAssets.preview(hit.md(),hit.picture(),true);
                    if (image != null) publish(new Thumbnail(hit,new ImageIcon(image),false));
                    // Gallery cards are wider than list rows, so the first cards use the larger preview.
                    if (index++ < COVER_PREVIEWS) {
                        var cover = PictureAssets.preview(hit.md(),hit.picture(),false);
                        if (cover != null) publish(new Thumbnail(hit,new ImageIcon(cover),true));
                    }
                }
                return null;
            }
            protected void process(List<Thumbnail> icons) {
                if (isCancelled()) return;
                for (var icon : icons) (icon.cover() ? covers : thumbnails).put(icon.hit(),icon.icon());
                results.repaint();
                gallery.repaint();
            }
        };
        loadingThumbnails.execute();
    }

    private void highlight() {
        var marker = preview.getHighlighter();
        marker.removeAllHighlights();
        var paint = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xF7D898));
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
        Library.Hit hit = selectedHit();
        if (hit != null) open(hit,markdown,reveal);
    }

    private void open(Library.Hit hit, boolean markdown, boolean reveal) {
        open(hit, markdown, reveal, frame);
    }

    private void open(Library.Hit hit, boolean markdown, boolean reveal, Component owner) {
        Path file = markdown ? hit.md() : hit.source();
        try {
            if (!Files.isRegularFile(file)) throw new IOException("파일을 찾을 수 없습니다.\n" + file);
            if (reveal) new ProcessBuilder("explorer.exe", "/select,", file.toString()).start();
            else if (markdown) MarkdownWindow.open(frame,file,hit);
            else if (Library.supported(file)) Desktop.getDesktop().open(file.toFile());
            else throw new IOException("지원하는 원본 문서를 선택하세요.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner,"문서를 열 수 없습니다.\n" + file + "\n" + ex.getLocalizedMessage(),
                    "문서 열기", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void editRole(){
        var hit=selectedHit();
        if(hit==null || working)return;
        var choices=new ArrayList<String>();
        choices.add("자동 분류");
        for(var kind:PmReference.Kind.values())choices.add(kind.label);
        choices.add("미분류");
        var choice=JOptionPane.showInputDialog(frame,hit.title(),"문서 역할 변경",JOptionPane.PLAIN_MESSAGE,
                null,choices.toArray(),null);
        if(choice==null)return;
        new SwingWorker<String,Void>(){
            protected String doInBackground()throws Exception{
                SearchIndex.setDocumentRole(hit.md(),choice.toString());
                return SearchIndex.documentRole(hit.md());
            }
            protected void done(){
                try{String role=get();if(hit.equals(selectedHit()))roleButton.setText("역할 · "+role);}
                catch(Exception ex){error(ex);}
            }
        }.execute();
    }

    private void error(Exception e) {
        JOptionPane.showMessageDialog(frame, e.getCause() == null ? e.toString() : e.getCause().toString(),
                "문서 찾기", JOptionPane.WARNING_MESSAGE);
    }

    private void busy(boolean value, String text) {
        working = value;
        if (value) cancelSearch();
        updateReferenceContext();
        for (JButton b : List.of(search, add, folder, addDocuments, findBrokenOcr, emptyButton)) b.setEnabled(!value);
        var hit = selectedHit();
        repairOcr.setEnabled(resultView != ResultView.DOCUMENT && !value && hit != null && hit.picture() != null);
        enrichPictures.setEnabled(!value);
        resumeRepair.setEnabled(!value);
        keywordMode.setEnabled(!value); imageMode.setEnabled(!value);
        scope.setEnabled(!value); query.setEditable(!value); mode.setEnabled(!value);
        latestOnly.setEnabled(!value);
        largeOptions.setEnabled(!value && indexWorker == null);
        ocrMode.setEnabled(!value); reconvertSameName.setEnabled(!value);
        syncIndex.setEnabled(!value && indexWorker == null);
        updateWorkerChoice();
        progress.setIndeterminate(value); progress.setStringPainted(false); progress.setVisible(value);
        status.setToolTipText(null);
        status.setText(text);
    }

    private void updateWorkerChoice() {
        var engine = (ImageOcr.Mode) ocrMode.getSelectedItem();
        ocrOnly.setEnabled(!working);
        boolean canRunTwo = engine == ImageOcr.Mode.WINDOWS || engine == ImageOcr.Mode.OFF;
        if (canRunTwo) {
            setWorkerChoices(1, 2, AUTOMATIC_WORKERS);
        } else if (engine == ImageOcr.Mode.PADDLE) {
            setWorkerChoices(1, AUTOMATIC_WORKERS);
        } else {
            setWorkerChoices(1);
        }
        conversionWorkers.setEnabled(!working && conversionWorkers.getItemCount() > 1);
        conversionWorkers.setToolTipText(engine == ImageOcr.Mode.VISION ? "비전 API는 한 번에 한 문서를 처리합니다."
                : "자동을 선택하면 사용 가능한 CPU와 메모리에 맞춰 동시 변환 한도를 조정합니다.");
    }

    private void setWorkerChoices(Integer... choices) {
        // Each mode has a distinct number of choices. Keep the model while changing the busy state.
        if (conversionWorkers.getItemCount() == choices.length) return;
        var previous = conversionWorkers.getSelectedItem();
        conversionWorkers.setModel(new DefaultComboBoxModel<>(choices));
        if (Arrays.asList(choices).contains(previous)) conversionWorkers.setSelectedItem(previous);
    }

    private void setResultView(ResultView view) {
        resultView = view;
        boolean pictures = view != ResultView.DOCUMENT;
        repairOcr.setVisible(pictures);
        repairAll.setVisible(view==ResultView.BROKEN_OCR);
        results.setSelectionMode(pictures ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                : ListSelectionModel.SINGLE_SELECTION);
        tabs.setTitleAt(1, pictures ? "인식 문구" : "검색 근거");
    }

    private boolean galleryRequest() {
        return displayedSearch != null && !displayedSearch.brokenOcr()
                && (displayedSearch.needle().isBlank() || displayedSearch.scope()==SearchIndex.Scope.IMAGE);
    }

    private void readCard(int index) {
        if (index < 0 || index >= model.size()) return;
        readingCard = true;
        hero.setVisible(false);
        backToGallery.setVisible(true);
        recentQueries.setVisible(false);
        results.setSelectedIndex(index);
        pages.show(contents,"results");
        results.ensureIndexIsVisible(index);
        results.requestFocusInWindow();
    }

    private void showGallery() {
        SearchStudio.sectionTitle(galleryTitle, resultView == ResultView.PICTURE ? "보관함의 그림" : "보관함의 문서",
                model.size(), model.size() == LIMIT);
        hero.setVisible(displayedSearch != null && displayedSearch.needle().isBlank());
        recentQueries.setVisible(hero.isVisible() && !recentTerms.isEmpty());
        pages.show(contents,"gallery");
    }

    private void showHits(List<Library.Hit> hits, List<Library.Hit> previous) {
        Set<Library.Hit> retained = new HashSet<>(previous);
        var selected = new ArrayList<Integer>();
        for (int index = 0; index < hits.size(); index++) {
            if (retained.contains(hits.get(index))) selected.add(index);
        }
        loadThumbnails(hits);
        model.clear();
        model.addAll(hits);
        if (!hits.isEmpty() && (!galleryRequest() || readingCard)) {
            if (selected.isEmpty()) results.setSelectedIndex(0);
            else results.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
            results.ensureIndexIsVisible(results.getSelectedIndex());
        }
        boolean cards = galleryRequest();
        backToGallery.setVisible(cards && readingCard);
        if (cards && !readingCard && !hits.isEmpty()) showGallery();
        else pages.show(contents, hits.isEmpty() ? "empty" : "results");
    }

    private void submitQuery() {
        String term = query.getText().strip();
        if (!term.isEmpty()) {
            recentTerms.remove(term);
            recentTerms.addFirst(term);
            if (recentTerms.size()>3) recentTerms.removeLast();
            recentQueries.removeAll();
            recentQueries.add(LibraryStyle.label("최근 검색",12,false,LibraryStyle.MUTED));
            for (String recent : recentTerms) {
                var chip = new JButton(recent.length()>24 ? recent.substring(0,24)+"…" : recent);
                chip.putClientProperty("html.disable",true);
                chip.setToolTipText(recent);
                chip.addActionListener(e -> {
                    if (search.isEnabled()) { query.setText(recent); submitQuery(); }
                });
                recentQueries.add(chip);
            }
        }
        refresh("");
        if (referencePanel.enabled() && ReferenceSearch.requested(term)) {
            var provider = AgentConnection.preferred();
            String model = AgentConnection.model(provider);
            var effort = AgentConnection.effort(provider);
            referencePanel.search(progress -> ReferenceSearch.search(term, null, provider, model, effort, progress));
        } else referencePanel.clearSearch();
    }

    private void updateReferenceContext() {
        referencePanel.setAvailable(!working && keywordMode.isSelected()
                && scope.getSelectedItem() == SearchIndex.Scope.ALL);
    }

    private void refresh(String summary) {
        searchCached(new SearchRequest(query.getText(), (SearchIndex.Scope) scope.getSelectedItem(),
                (SearchIndex.Mode) mode.getSelectedItem(), false), summary);
    }

    private void refreshBrokenOcr(String summary) {
        searchCached(new SearchRequest("", SearchIndex.Scope.IMAGE, SearchIndex.Mode.PHRASE, true), summary);
    }

    private void cancelSearch() {
        var previous = searchWorker;
        searchWorker = null;
        if (previous != null) previous.cancel(true);
    }

    private void searchCached(SearchRequest request, String summary) {
        cancelSearch();
        lastSearch = request;
        lastSearchSummary = summary;
        status.setToolTipText(null);
        status.setText(request.brokenOcr() ? "OCR 깨짐 의심 목록을 찾는 중…" : "색인된 문서를 검색하는 중…");
        searchWorker = new SwingWorker<>() {
            protected List<Library.Hit> doInBackground() throws Exception {
                if (!request.brokenOcr() && request.scope()!=SearchIndex.Scope.IMAGE && request.needle().isBlank())
                    return SearchIndex.libraryCards(library,LIMIT);
                return request.brokenOcr() ? SearchIndex.suspectedOcrCached(library, LIMIT + 1)
                        : SearchIndex.searchCached(library, request.needle(), request.scope(), request.mode(), LIMIT);
            }

            protected void done() {
                try {
                    if (searchWorker != this) return;
                    searchWorker = null;
                    var hits = get();
                    displaySearch(request, hits, summary);
                } catch (Exception e) {
                    status.setText("색인에서 검색하지 못했습니다.");
                    status.setToolTipText(e.getCause() == null ? e.toString() : e.getCause().toString());
                } finally {
                    if (!startupSyncStarted) startIndexSync();
                }
            }
        };
        searchWorker.execute();
    }

    private void displaySearch(SearchRequest request, List<Library.Hit> hits, String summary) {
        var previous = request.equals(displayedSearch) ? results.getSelectedValuesList() : List.<Library.Hit>of();
        if (!request.equals(displayedSearch)) readingCard = false;
        displayedSearch = request;
        if (request.scope()==SearchIndex.Scope.IMAGE) SearchStudio.headline(headline,"그림 속","글자를 찾아보세요.");
        else SearchStudio.headline(headline,"보관함에서","무엇을 찾을까요?");
        hero.setVisible(!readingCard && !request.brokenOcr() && request.needle().isBlank());
        recentQueries.setVisible(hero.isVisible() && !recentTerms.isEmpty());
        if (request.brokenOcr()) {
            var displayed = hits.subList(0, Math.min(hits.size(), LIMIT));
            setResultView(ResultView.BROKEN_OCR);
            activeWords = List.of();
            showImport = false;
            count.setText("저품질 의심 문서 "+hits.stream().map(Library.Hit::md).distinct().count()+(hits.size()>LIMIT?"건 이상":"건")+" · 그림 " + displayed.size() + (hits.size() > LIMIT ? "+" : ""));
            emptyTitle.setText("재인식할 그림이 없습니다");
            emptyHint.setText("현재 색인에서 깨짐이 의심되는 글자 인식 결과를 찾지 못했습니다.");
            emptyButton.setText("전체 문서 보기");
            showHits(displayed, previous);
            status.setText(summary + (displayed.isEmpty() ? " " : "그림을 선택하면 점검 이유가 표시됩니다. 후보 전체를 한 번에 정밀 OCR할 수 있습니다."));
            return;
        }
        boolean pictures = request.scope() == SearchIndex.Scope.IMAGE;
        boolean all = request.needle().isBlank();
        setResultView(pictures ? ResultView.PICTURE : ResultView.DOCUMENT);
        activeWords = request.mode().words(request.needle());
        count.setText((pictures ? "그림 결과" : all ? "문서 목록" : "검색 결과") + "   "
                + hits.size() + (hits.size() == LIMIT ? "+" : ""));
        showImport = !pictures && all && hits.isEmpty();
        boolean indexing = !startupSyncStarted || indexWorker != null;
        String emptyHeading = indexing ? "색인된 문서가 아직 없습니다" : "첫 문서를 추가하세요";
        emptyTitle.setText(showImport ? emptyHeading : "검색 결과가 없습니다");
        emptyHint.setText(showImport ? "PowerPoint·Excel·Word·PDF 파일이나 폴더를 이곳에 끌어오세요."
                : pictures ? "검색어를 줄이거나 그림이 있는 문서를 추가해 보세요."
                : "검색어를 줄이거나 전체 문서 목록을 확인하세요.");
        emptyButton.setText(showImport ? "파일 선택" : "전체 문서 보기");
        showHits(hits, previous);
        status.setText(summary + (hits.isEmpty() || galleryRequest() && !readingCard ? "Ctrl+F 검색창으로 이동"
                : "Ctrl+F 검색창으로 이동     ·     결과를 두 번 클릭하면 Markdown 뷰어 열기"));
    }

    private void startIndexSync() {
        if (indexWorker != null) return;
        startupSyncStarted = true;
        syncIndex.setEnabled(false);
        largeOptions.setEnabled(false);
        indexStatus.setToolTipText(null);
        indexStatus.setText("색인 확인 중…");
        indexWorker = new SwingWorker<>() {
            private boolean readFirstSnapshot;
            private SearchIndex.Progress latest = new SearchIndex.Progress(0, 0, 0, 0);

            protected Void doInBackground() throws Exception {
                SearchIndex.synchronizeAll(library, this::publish);
                return null;
            }

            protected void process(List<SearchIndex.Progress> updates) {
                latest = updates.getLast();
                indexStatus.setText(indexProgressText("색인 확인 중", latest));
                if (!readFirstSnapshot && latest.updated() > 0 && model.isEmpty()
                        && !working && searchWorker == null && lastSearch != null) {
                    readFirstSnapshot = true;
                    searchCached(lastSearch, lastSearchSummary);
                }
            }

            protected void done() {
                indexWorker = null;
                syncIndex.setEnabled(!working);
                largeOptions.setEnabled(!working);
                try {
                    get();
                    indexStatus.setText(latest.deferred() > 0 ? "색인 최신 · 대용량 보류 " + latest.deferred() + "건" : "색인 최신");
                    indexStatus.setToolTipText(indexProgressText("색인 확인 완료", latest));
                } catch (Exception e) {
                    indexStatus.setText("일부 문서의 색인 확인을 완료하지 못했습니다.");
                    indexStatus.setToolTipText(e.getCause() == null ? e.toString() : e.getCause().toString());
                }
                if (!working && lastSearch != null) searchCached(lastSearch, lastSearchSummary);
            }
        };
        indexWorker.execute();
    }

    private static String indexProgressText(String label, SearchIndex.Progress latest) {
        return label + " · 확인 " + latest.checked() + "건 · 갱신 " + latest.updated()
                + "건 · 제거 " + latest.removed() + "건 · 대용량 보류 " + latest.deferred() + "건";
    }

    private void searchAfterWork(String summary) {
        busy(false, summary);
        if (lastSearch != null) searchCached(lastSearch, summary);
        else refresh(summary);
    }

    private void repairSelectedOcr() {
        var selected = results.getSelectedValuesList().stream().filter(hit -> hit.picture() != null).toList();
        if (resultView == ResultView.DOCUMENT || working || selected.isEmpty()) return;
        startRepair(selected,List.of(),false);
    }

    private void chooseMarkdown(Path initial){
        var picker=new JFileChooser(library.toFile());
        picker.setFileFilter(new FileNameExtensionFilter("Markdown","md"));
        if(initial!=null)picker.setSelectedFile(initial.toFile());
        if(picker.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION)
            MarkdownWindow.open(frame,picker.getSelectedFile().toPath(),null);
    }

    private void enrichPictureSearch(){
        if(working)return;
        busy(true,"같은 그림의 OCR 확인 중…");
        new SwingWorker<PictureSearchIndex.Stats,String>(){
            boolean hasPaddle;
            protected PictureSearchIndex.Stats doInBackground()throws Exception{
                try{ImageOcr.validate(ImageOcr.Mode.PADDLE);hasPaddle=true;}
                catch(IOException missing){return PictureSearchIndex.enrich(null,this::publish);}
                try(var paddle=new ImageOcr(ImageOcr.Mode.PADDLE,ImageOcr.scripts())){
                    return PictureSearchIndex.enrich(paddle,this::publish);
                }
            }
            protected void process(List<String> lines){status.setText(lines.getLast());}
            protected void done(){
                try{
                    var s=get();
                    searchAfterWork("그림 검색 보강 · 동일 그림 OCR "+s.reused()+"건 · 새 OCR "+s.recognized()
                            +"건 · 글자 없음 "+s.empty()+"건 · 실패 "+s.failed()+"건"+
                            (hasPaddle?"":" · 새 OCR은 한글 정밀 OCR 설치 후 사용 가능"));
                }catch(Exception e){busy(false,"그림 검색 보강 중단");error(e);}
            }
        }.execute();
    }

    private void choosePrecisionNotes(){
        var picker=new JFileChooser(library.toFile());
        picker.setDialogTitle("한글 정밀 OCR · 여러 Markdown 문서 또는 폴더 선택");
        picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        picker.setMultiSelectionEnabled(true);
        picker.setFileFilter(new FileNameExtensionFilter("변환된 Markdown","md"));
        if(picker.showOpenDialog(frame)!=JFileChooser.APPROVE_OPTION)return;
        var paths=Arrays.stream(picker.getSelectedFiles()).map(java.io.File::toPath).toList();
        if(!paths.isEmpty())startRepair(List.of(),paths,false);
    }

    private void startRepair(List<Library.Hit> selected,List<Path> inputs,boolean all){
        if(working)return;
        resumeRepair.setVisible(false);
        remainingNotes=List.of();remainingPictures=List.of();
        stopRequested=false;
        stopRepair.setEnabled(true);stopRepair.setVisible(true);
        repairAll.setEnabled(false);
        busy(true, "PaddleOCR 준비 중…");
        new SwingWorker<Void, RepairProgress>() {
            private final List<Path> deferredNotes=new ArrayList<>();
            private final List<Library.Hit> deferredPictures=new ArrayList<>();
            private int total;
            private int updated;
            private int processed;
            private int failed;
            private int indexFailures;
            private final List<String> failures = new ArrayList<>();

            protected Void doInBackground() throws Exception {
                var documents=new LinkedHashMap<Path,List<Library.Hit>>();
                var candidates=all?SearchIndex.suspectedOcrCached(library,Integer.MAX_VALUE):selected;
                for(var hit:candidates)documents.computeIfAbsent(hit.md(),unused->new ArrayList<>()).add(hit);
                var notes=new LinkedHashSet<Path>();
                for(var input:inputs){
                    if(Files.isDirectory(input)){
                        try(var files=Files.newDirectoryStream(input,"*.md")){
                            for(var file:files)if(PictureAssets.key(file)!=null)notes.add(file);
                        }
                    }else notes.add(input);
                }
                for(var note:notes)documents.putIfAbsent(note,List.of());
                total=documents.size();
                if(total==0)return null;
                ImageOcr.validate(ImageOcr.Mode.PADDLE);
                var changed = new ArrayList<Path>();
                try (var paddle = new ImageOcr(ImageOcr.Mode.PADDLE, ImageOcr.scripts())) {
                    int started=0;
                    for (var item : documents.entrySet()) {
                        if(stopRequested){
                            if(item.getValue().isEmpty())deferredNotes.add(item.getKey());
                            else deferredPictures.addAll(item.getValue());
                            continue;
                        }
                        var note=item.getKey();
                        String title=note.getFileName().toString();
                        publish(new RepairProgress(started++, title));
                        try {
                            var hits=item.getValue();
                            if(hits.isEmpty())hits=OcrRepair.documentPictures(note);
                            int replacements = OcrRepair.repair(note.getParent(), hits, paddle);
                            updated += replacements;
                            if (replacements > 0) changed.add(note);
                        } catch (IOException e) {
                            failures.add(title + "\n" + e.getMessage());
                            failed++;
                        }
                        processed++;
                    }
                } finally {
                    if (!changed.isEmpty()) {
                        publish(new RepairProgress(processed, "변경된 문서 색인 중"));
                        for (Path note : changed) {
                            try {
                                var result = SearchIndex.indexCompletedDetailed(note.getParent(), List.of(note));
                                indexFailures += 1 - result.indexed() - result.deferred();
                            } catch (IOException e) {
                                indexFailures++;
                                failures.add("변경된 문서 색인을 완료하지 못했습니다.\n" + e.getMessage());
                            }
                        }
                    }
                }
                return null;
            }

            protected void process(List<RepairProgress> updates) {
                var latest = updates.getLast();
                showProgress(latest.processed(), total,
                        "PaddleOCR 재인식 " + latest.processed() + "/" + total + "문서 · " + latest.title());
            }

            protected void done() {
                try { get(); }
                catch (Exception e) { error(e); }
                stopRepair.setVisible(false);repairAll.setEnabled(true);
                remainingNotes=List.copyOf(deferredNotes);remainingPictures=List.copyOf(deferredPictures);
                resumeRepair.setVisible(!remainingNotes.isEmpty() || !remainingPictures.isEmpty());
                showNotices(failures, "OCR 재인식 알림");
                if (processed == 0) {
                    busy(false, total==0 ? "정밀 OCR 대상이 없습니다." : "정밀 OCR 처리 문서 0건");
                } else {
                    String indexIssues = indexFailures > 0 ? " · 색인 미반영 " + indexFailures + "건" : "";
                    String summary = (stopRequested?"중단 · ":"완료 · ")+"처리 문서 "+processed+"/"+total+" · 새 문구 적용 그림 "+updated+"개 · 실패 문서 "+failed+"건"+indexIssues+"     ";
                    searchAfterWork(summary);
                }
            }
        }.execute();
    }

    private record RepairProgress(int processed, String title) {}

    private void showProgress(long done, long total, String text) {
        progress.setIndeterminate(false);
        int percent = total == 0 ? 100 : (int) (100.0 * done / total);
        progress.setValue(percent);
        progress.setString(done + " / " + total + " (" + percent + "%)");
        progress.setStringPainted(true);
        status.setToolTipText(text);
        status.setText(text);
    }

    private void showNotices(List<String> messages, String title) {
        if (messages.isEmpty()) return;
        JTextArea log = new JTextArea(String.join("\n\n", messages), 10, 54);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setCaretPosition(0);
        JOptionPane.showMessageDialog(frame, new JScrollPane(log), title, JOptionPane.WARNING_MESSAGE);
    }

    private ImageOcr.Mode confirmEngine(ImageOcr.Mode engine, boolean importing) {
        if (engine == ImageOcr.Mode.OFF) return engine;
        try {
            ImageOcr.validate(engine);
        } catch (IOException e) {
            Object[] choices = importing
                    ? new String[]{"Windows OCR로 계속", "OCR 끄고 변환", "취소"}
                    : new String[]{"Windows OCR로 계속", "취소"};
            int choice = JOptionPane.showOptionDialog(frame, e.getMessage(), "OCR 설정 확인",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, choices, choices[0]);
            if (choice == 0) {
                if (importing) ocrMode.setSelectedItem(ImageOcr.Mode.WINDOWS);
                return ImageOcr.Mode.WINDOWS;
            }
            if (importing && choice == 1) {
                ocrMode.setSelectedItem(ImageOcr.Mode.OFF);
                return ImageOcr.Mode.OFF;
            }
            return null;
        }
        if(engine==ImageOcr.Mode.VISION && JOptionPane.showConfirmDialog(frame,
                "선택한 문서의 그림을 설정된 비전 API로 보냅니다.\n대상: "+
                System.getenv().getOrDefault("OPENAI_BASE_URL","https://api.openai.com/v1")+
                "\n모델: "+System.getenv("OFFICEMD_VISION_MODEL")+"\n계속할까요?",
                "비전 OCR",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return null;
        return engine;
    }

    private void chooseOcrNotes() {
        if (working) return;
        var selected = (ImageOcr.Mode) ocrMode.getSelectedItem();
        selected = (ImageOcr.Mode) JOptionPane.showInputDialog(frame, "그림을 읽을 OCR 방식을 선택하세요.",
                "OCR만 실행", JOptionPane.PLAIN_MESSAGE, null,
                new ImageOcr.Mode[]{ImageOcr.Mode.WINDOWS, ImageOcr.Mode.PADDLE, ImageOcr.Mode.VISION},
                selected == ImageOcr.Mode.OFF ? ImageOcr.Mode.WINDOWS : selected);
        if (selected == null) return;
        JFileChooser picker = new JFileChooser(library.toFile());
        picker.setDialogTitle("OCR만 실행할 Markdown 선택");
        picker.setFileFilter(new FileNameExtensionFilter("변환된 Markdown", "md"));
        picker.setMultiSelectionEnabled(true);
        var hit = selectedHit();
        if (hit != null) picker.setSelectedFile(hit.md().toFile());
        if (picker.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        var notes = Arrays.stream(picker.getSelectedFiles()).map(java.io.File::toPath).toList();
        if (notes.isEmpty()) return;
        var engine = confirmEngine(selected, false);
        if (engine == null) return;
        busy(true, "보존된 그림 확인 중…");
        new SwingWorker<Void, String>() {
            int recognized, skipped, deferred, withoutPictures, alreadyDone;
            final List<String> issues = new ArrayList<>();
            protected Void doInBackground() throws Exception {
                try (var ocr = new ImageOcr(engine, ImageOcr.scripts())) {
                    for (var note : notes) {
                        publish(note.getFileName().toString());
                        try {
                            Path output = note.toAbsolutePath().getParent();
                            var state = OcrBackfill.inspect(output, note);
                            if (state.pending() == 0) {
                                if (state.pictures() == 0) withoutPictures++;
                                else alreadyDone++;
                                continue;
                            }
                            var result = OcrBackfill.run(output, note, ocr);
                            recognized += result.recognized();
                            skipped += result.skipped();
                            if (result.recognized() + result.skipped() > 0) {
                                var completion = SearchIndex.indexCompletedDetailed(output, List.of(note));
                                deferred += completion.deferred();
                                if (completion.indexed() + completion.deferred() == 0)
                                    issues.add(note.getFileName() + " · 검색 색인 갱신을 확인해 주세요.");
                            }
                        } catch (IOException ex) { issues.add(note.getFileName() + " · " + ex.getMessage()); }
                    }
                    issues.addAll(ocr.warnings);
                }
                return null;
            }
            protected void process(List<String> names) { status.setText("OCR만 실행 중 · " + names.getLast()); }
            protected void done() {
                try { get(); } catch (Exception ex) { error(ex); }
                showNotices(issues, "OCR 처리 알림");
                searchAfterWork("그림 OCR 완료 " + recognized + "개 · 건너뜀 " + skipped
                        + "개 · 대용량 색인 보류 " + deferred + "건"
                        + (alreadyDone == 0 ? "" : " · 처리할 그림 없는 문서 " + alreadyDone + "건")
                        + (withoutPictures == 0 ? "" : " · 보존 그림 없는 문서 " + withoutPictures + "건")
                        + "     ");
            }
        }.execute();
    }

    private void manageLarge() {
        if (working || indexWorker != null) return;
        busy(true, "대용량 보류 목록 확인 중…");
        new SwingWorker<SearchIndex.LargeSettings, Void>() {
            protected SearchIndex.LargeSettings doInBackground() throws Exception {
                return SearchIndex.largeSettings(library);
            }
            protected void done() {
                try {
                    var settings = get();
                    JCheckBox hold = new JCheckBox("100 MB 초과 Markdown의 자동 색인 보류", settings.hold());
                    JList<SearchIndex.HeldNote> pending = new JList<>(settings.pending().toArray(SearchIndex.HeldNote[]::new));
                    pending.setVisibleRowCount(8);
                    Object[] fields = {hold, "자동 색인을 허용할 문서를 선택하세요.", new JScrollPane(pending)};
                    if (JOptionPane.showConfirmDialog(frame, fields, "대용량 색인 관리",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                        applyLarge(hold.isSelected(), pending.getSelectedValuesList().stream()
                                .map(SearchIndex.HeldNote::path).toList());
                        return;
                    }
                } catch (Exception ex) { error(ex); }
                searchAfterWork("");
            }
        }.execute();
    }

    private void applyLarge(boolean hold, List<Path> allow) {
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                SearchIndex.configureLarge(library, hold, allow);
                return null;
            }
            protected void done() {
                busy(false, "");
                try { get(); startIndexSync(); } catch (Exception ex) { error(ex); }
            }
        }.execute();
    }

    private void choose(boolean directory) {
        JFileChooser picker = new JFileChooser();
        if (directory) picker.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        else {
            picker.setMultiSelectionEnabled(true);
            picker.setFileFilter(new FileNameExtensionFilter("PowerPoint · Excel · Word · PDF", "ppt", "pptx", "xls", "xlsx", "doc", "docx", "pdf"));
        }
        if (picker.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        var inputs = directory ? List.of(picker.getSelectedFile()) : Arrays.asList(picker.getSelectedFiles());
        importFiles(inputs);
    }

    private void importFiles(List<java.io.File> inputs) {
        if (working || inputs.isEmpty()) return;
        var chosen = new JList<java.io.File>(inputs.toArray(java.io.File[]::new));
        chosen.setSelectionInterval(0, inputs.size()-1);
        chosen.setVisibleRowCount(Math.min(inputs.size(),6));
        chosen.getAccessibleContext().setAccessibleName("변환 대상 파일 및 폴더");
        var plan = LibraryStyle.panel(new BorderLayout(0,8),4);
        plan.add(LibraryStyle.label("변환할 파일과 폴더를 선택하세요",18,true,LibraryStyle.INK),BorderLayout.NORTH);
        plan.add(new JScrollPane(chosen));
        plan.add(importOptions,BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(frame, plan, "문서 추가 · 변환 설정",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        var paths = chosen.getSelectedValuesList().stream().map(java.io.File::toPath).toList();
        if (paths.isEmpty()) return;
        var selected = (ImageOcr.Mode) ocrMode.getSelectedItem();
        var engine = confirmEngine(selected, true);
        if (engine == null) return;
        boolean filterLatest = latestOnly.isSelected();
        boolean reconvert = reconvertSameName.isSelected();
        int workers = (Integer) conversionWorkers.getSelectedItem();
        busy(true, "변환할 파일을 찾는 중…");
        new SwingWorker<BatchConversion.Result, BatchConversion.Progress>() {
            protected BatchConversion.Result doInBackground() throws Exception {
                return BatchConversion.run(paths, library, reconvert, engine, workers, this::publish,
                        filterLatest ? preview -> LatestVersionDialog.confirm(frame, preview) : null);
            }
            protected void process(List<BatchConversion.Progress> updates) {
                var latest = updates.getLast();
                if (latest.phase() == BatchConversion.Phase.PREVIEW) {
                    status.setText("최신본 변환 대상 확인 중…");
                } else if (latest.phase() == BatchConversion.Phase.INDEXING) {
                    showProgress(latest.processed(), latest.total(), "검색 색인 작성 중 · " + latest.current());
                } else if (latest.phase() == BatchConversion.Phase.SCANNING) {
                    String phase = "기존 변환 확인".equals(latest.current()) ? "기존 변환 확인 중" : "파일 찾는 중";
                    status.setText(phase + " · 확인 " + latest.scanned() + "개 · 변환 대상 " + latest.total() + "개");
                } else {
                    String current = latest.current() == null || latest.current().isBlank() ? "" : " · " + latest.current();
                    String parallel = (workers == AUTOMATIC_WORKERS ? "자동 동시 한도 " : "동시 한도 ") + latest.workers();
                    showProgress(latest.processed(),latest.total(),"변환 중 · 완료 " + latest.converted()
                            + " · 건너뜀 " + latest.skipped() + " · 실패 " + latest.failed()
                            + " · 색인 " + latest.indexed() + " · 대용량 보류 " + latest.deferred() + " · " + parallel + current);
                }
            }
            protected void done() {
                try {
                    var result = get();
                    if (result.cancelled()) {
                        searchAfterWork("최신본 변환을 취소했습니다.     ");
                        return;
                    }
                    if(result.converted()>0){
                        lastConversion=result.converted()==1?result.lastMarkdown():null;
                        conversionResult.setVisible(true);
                    }
                    long notices = result.failed() + result.scanFailures() + result.warnings() + result.indexFailures();
                    showNotices(result.details(),"변환 및 OCR 알림 " + notices + "건");
                    String scanIssues = result.scanFailures()==0 ? "" : " · 탐색 오류 " + result.scanFailures() + "건";
                    String indexIssues = result.indexFailures()==0 ? "" : " · 색인 오류 " + result.indexFailures() + "건";
                    String summary = "변환 " + result.converted() + "건 · 건너뜀 " + result.skipped()
                            + "건 · 실패 " + result.failed() + "건 · 색인 " + result.indexed() + "건"
                            + " · 대용량 보류 " + result.deferred() + "건 · 이전판 제외 " + result.latestSkipped() + "건"
                            + scanIssues + indexIssues + "     ";
                    searchAfterWork(summary);
                } catch (Exception e) {
                    error(e);
                    busy(false, "변환 작업이 중단됐습니다.");
                }
            }
        }.execute();
    }
}
