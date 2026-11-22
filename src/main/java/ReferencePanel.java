import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Recommendations and caption preparation leave the regular search model untouched. */
final class ReferencePanel extends JPanel {
    private static final int MAX_RECOMMENDATIONS = 3;
    private final JButton prepare = new JButton("장표 설명 준비");
    private final JLabel preparation = LibraryStyle.label("", 12, false, LibraryStyle.MUTED);
    private final JPanel controls = LibraryStyle.panel(new BorderLayout(10, 0), 0);
    private final JLabel status = LibraryStyle.label("", 13, false, LibraryStyle.MUTED);
    private final JButton dismiss = new JButton("닫기");
    private final DefaultListModel<ReferenceSearch.Recommendation> recommendations = new DefaultListModel<>();
    private final JList<ReferenceSearch.Recommendation> list = new JList<>(recommendations);
    private final JPanel detail = LibraryStyle.panel(new GridLayout(1, 2, 10, 0), 0);
    private final JTextArea evidence = new JTextArea();
    private final JLabel preview = LibraryStyle.label("", 13, false, LibraryStyle.MUTED);
    private final JButton open = new JButton("파일 열기");
    private final Consumer<Library.Hit> openOriginal;
    private boolean available;
    private SwingWorker<SearchResult, String> searchWorker;
    private SwingWorker<ImageIcon, Void> previewWorker;
    private SwingWorker<Boolean, Void> openWorker;
    private SwingWorker<String, String> prepareWorker;

    @FunctionalInterface
    interface Search {
        List<ReferenceSearch.Recommendation> run(Consumer<String> progress) throws Exception;
    }

    private record SearchResult(List<ReferenceSearch.Recommendation> items, boolean hasStalePages) {}

    ReferencePanel(Consumer<Library.Hit> openOriginal) {
        super(new BorderLayout(0, 8));
        this.openOriginal = openOriginal;
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LibraryStyle.LINE),
                new EmptyBorder(8, 12, 8, 12)));
        var actions = LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT, 6, 0), 0);
        actions.add(prepare);
        controls.add(actions, BorderLayout.WEST);
        controls.add(preparation);
        prepare.addActionListener(e -> {
            if (prepareWorker == null) startPreparation();
            else cancelPreparation(true);
        });

        var header = LibraryStyle.panel(new BorderLayout(10, 0), 0);
        var title = LibraryStyle.label("추천 슬라이드", 15, true, LibraryStyle.INK);
        title.setLabelFor(list);
        header.add(title, BorderLayout.WEST);
        header.add(status);
        header.add(dismiss, BorderLayout.EAST);
        dismiss.addActionListener(e -> {
            if (searchWorker == null) clearSearch();
            else {
                clearSearch();
                showStatus("슬라이드 추천을 취소했습니다.");
            }
        });
        add(header, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(source, value, index, selected, focus);
                putClientProperty("html.disable", true);
                if (value instanceof ReferenceSearch.Recommendation item) setText(pageLabel(item.page()));
                return this;
            }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadSelection(); });
        var listScroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setPreferredSize(new Dimension(260, MAX_RECOMMENDATIONS * list.getFixedCellHeight() + 3));
        evidence.setEditable(false);
        evidence.setLineWrap(true);
        evidence.setWrapStyleWord(true);
        evidence.setFont(LibraryStyle.TYPE.deriveFont(13f));
        evidence.setMargin(new Insets(5, 5, 5, 5));
        evidence.getAccessibleContext().setAccessibleName("추천 근거와 원본 인용");
        var text = LibraryStyle.panel(new BorderLayout(0, 6), 0);
        text.add(listScroll, BorderLayout.NORTH);
        text.add(new JScrollPane(evidence));
        detail.add(text);

        preview.setHorizontalAlignment(SwingConstants.CENTER);
        preview.getAccessibleContext().setAccessibleName("선택한 슬라이드 미리보기");
        var previewBox = LibraryStyle.panel(new BorderLayout(0, 4), 0);
        previewBox.add(preview);
        var fileActions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT, 0, 0), 0);
        fileActions.add(open);
        previewBox.add(fileActions, BorderLayout.SOUTH);
        open.addActionListener(e -> openSelected());
        detail.add(previewBox);
        detail.setPreferredSize(new Dimension(600, 174));
        detail.setVisible(false);
        add(detail);
        setVisible(false);
    }

    JComponent controls() { return controls; }

    void setAvailable(boolean value) {
        if (available == value) return;
        available = value;
        if (!value) clearSearch();
    }

    boolean enabled() { return available; }

    boolean isWorking() { return searchWorker != null || prepareWorker != null; }

    void reset() {
        clearSearch();
        cancelPreparation(false);
    }

    void clearSearch() {
        var previous = searchWorker;
        searchWorker = null;
        if (previous != null) previous.cancel(true);
        cancelSelectionWorkers();
        recommendations.clear();
        detail.setVisible(false);
        setVisible(false);
        dismiss.setText("닫기");
    }

    void search(Search request) {
        clearSearch();
        if (!enabled()) return;
        showStatus("검색어와 맞는 슬라이드를 찾는 중…");
        dismiss.setText("추천 취소");
        searchWorker = new SwingWorker<>() {
            @Override protected SearchResult doInBackground() throws Exception {
                var fresh = new ArrayList<ReferenceSearch.Recommendation>();
                boolean stale = false;
                for (var item : request.run(this::publish)) {
                    if (isCancelled() || fresh.size() == MAX_RECOMMENDATIONS) break;
                    if (ReferenceIndex.current(item.page())) fresh.add(item);
                    else stale = true;
                }
                return new SearchResult(fresh, stale);
            }

            @Override protected void process(List<String> updates) {
                if (searchWorker == this) setStatus(updates.getLast());
            }

            @Override protected void done() {
                if (searchWorker != this) return;
                searchWorker = null;
                dismiss.setText("닫기");
                try {
                    var result = get();
                    var items = result.items();
                    if (items.isEmpty()) {
                        setStatus(result.hasStalePages() ? "추천 원본이 변경되었습니다. 최신 슬라이드를 보려면 다시 검색하세요."
                                : "현재 검색 범위에서 근거를 확인한 추천 슬라이드가 없습니다.");
                        return;
                    }
                    recommendations.addAll(items);
                    setStatus(items.size() + (result.hasStalePages() ? "장 · 원본이 변경된 슬라이드를 제외했습니다." : "장"));
                    detail.setVisible(true);
                    list.setSelectedIndex(0);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    var cause = ex.getCause();
                    boolean timedOut = cause instanceof TimeoutException
                            || cause.getMessage() != null && cause.getMessage().contains("시간 초과");
                    setStatus((timedOut ? "AI 슬라이드 추천 응답 시간이 초과되었습니다." : "AI 슬라이드 추천을 완료하지 못했습니다.")
                            + " 일반 검색 결과는 계속 이용할 수 있습니다.");
                }
            }
        };
        searchWorker.execute();
    }

    private void showStatus(String message) {
        setStatus(message);
        setVisible(true);
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setToolTipText(message);
    }

    private void startPreparation() {
        prepare.setText("준비 취소");
        setPreparationStatus("보관함의 PPTX를 확인하는 중…");
        prepareWorker = new SwingWorker<>() {
            private volatile String latest = "";

            @Override protected String doInBackground() throws Exception {
                var plan = ReferenceWiki.plan();
                if (plan.isEmpty()) return "준비할 장표 설명이 없습니다.";
                var approved = new java.util.concurrent.atomic.AtomicBoolean();
                int slides = plan.stream().mapToInt(ReferenceWiki.Target::slides).sum();
                SwingUtilities.invokeAndWait(() -> {
                    if (prepareWorker != this || isCancelled() || !controls.isShowing()) return;
                    String prompt = "문서 " + plan.size() + "개 · 장표 " + slides
                            + "장의 이미지를 " + AgentConnection.preferred() + "에 보냅니다. AI 사용량이 발생합니다. 진행할까요?";
                    approved.set(JOptionPane.showConfirmDialog(controls,prompt,"장표 설명 준비",
                            JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION);
                });
                if (!approved.get() || isCancelled()) return "장표 설명 준비를 취소했습니다.";
                return ReferenceWiki.prepare(message -> { latest = message; publish(message); },plan);
            }

            @Override protected void process(List<String> updates) {
                if (prepareWorker == this) setPreparationStatus(updates.getLast());
            }

            @Override protected void done() {
                if (prepareWorker != this) return;
                prepareWorker = null;
                prepare.setText("장표 설명 준비");
                try {
                    setPreparationStatus(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    setPreparationStatus(latest.isBlank() ? "장표 설명 준비를 완료하지 못했습니다."
                            : "장표 설명 준비 미완료 · " + latest);
                }
            }
        };
        prepareWorker.execute();
    }

    private void setPreparationStatus(String message) {
        preparation.setText(message);
        preparation.setToolTipText(message.isBlank() ? null : message);
    }

    private void cancelPreparation(boolean announce) {
        var previous = prepareWorker;
        prepareWorker = null;
        if (previous != null) previous.cancel(true);
        prepare.setText("장표 설명 준비");
        setPreparationStatus(announce ? "장표 설명 준비를 취소했습니다." : "");
    }

    private void cancelSelectionWorkers() {
        var loading = previewWorker;
        var opening = openWorker;
        previewWorker = null;
        openWorker = null;
        if (loading != null) loading.cancel(true);
        if (opening != null) opening.cancel(true);
    }

    private void loadSelection() {
        cancelSelectionWorkers();
        preview.setIcon(null);
        preview.setText("");
        open.setEnabled(false);
        var item = list.getSelectedValue();
        if (item == null) {
            evidence.setText("");
            return;
        }
        var page = item.page();
        evidence.setText(pageLabel(page) + "\n" + item.reason() + "\n원본 인용  “" + item.quote() + "”");
        evidence.setCaretPosition(0);
        preview.setText(page.number() + "장 미리보기를 불러오는 중…");
        open.setEnabled(true);
        previewWorker = new SwingWorker<>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                BufferedImage image = ReferenceWiki.preview(page);
                return image == null ? null : scaledPreview(image);
            }

            @Override protected void done() {
                if (previewWorker != this) return;
                previewWorker = null;
                try {
                    var icon = get();
                    preview.setIcon(icon);
                    preview.setText(icon == null ? "슬라이드 미리보기를 표시할 수 없습니다." : "");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof EvidenceIndex.Changed) removeStale(item);
                    else preview.setText("슬라이드 미리보기를 표시할 수 없습니다.");
                }
            }
        };
        previewWorker.execute();
    }

    private void openSelected() {
        var item = list.getSelectedValue();
        if (item == null || !enabled() || openWorker != null) return;
        open.setEnabled(false);
        openWorker = new SwingWorker<>() {
            @Override protected Boolean doInBackground() throws Exception {
                return ReferenceIndex.current(item.page());
            }

            @Override protected void done() {
                if (openWorker != this) return;
                openWorker = null;
                open.setEnabled(true);
                try {
                    if (get()) openOriginal.accept(item.page().hit());
                    else removeStale(item);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    setStatus("원본 슬라이드를 확인할 수 없습니다. 다시 검색하세요.");
                }
            }
        };
        openWorker.execute();
    }

    private void removeStale(ReferenceSearch.Recommendation item) {
        recommendations.removeElement(item);
        detail.setVisible(!recommendations.isEmpty());
        if (!recommendations.isEmpty() && list.isSelectionEmpty()) list.setSelectedIndex(0);
        setStatus("원본 또는 색인이 변경된 추천을 제외했습니다. 최신 슬라이드를 보려면 다시 검색하세요.");
    }

    private static String pageLabel(ReferenceIndex.Page page) {
        return page.number() + "장 · " + page.hit().title();
    }

    private static ImageIcon scaledPreview(BufferedImage image) {
        double scale = Math.min(1, Math.min(275d / image.getWidth(), 140d / image.getHeight()));
        var bitmap = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)),
                Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            graphics.drawImage(image, 0, 0, bitmap.getWidth(), bitmap.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(bitmap);
    }
}
