import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import java.nio.file.Path;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Shared search header and virtualized library cards. All document text comes from the index. */
final class SearchStudio {
    static final Color PAPER = new Color(0xFFFDF7);
    static final Color CHARCOAL = new Color(0x242923);
    static final Color SELECTED = new Color(0xDAE7BD);
    static final Color HOT = new Color(0xC34422);
    static final Color TRACK = new Color(0xEBE8DC);

    static JPanel heading(JLabel title) {
        JPanel heading = LibraryStyle.panel(new BorderLayout(16, 0), 4);
        var text = LibraryStyle.panel(new BorderLayout(0,4), 0);
        var crumb = LibraryStyle.label("색인된 전체 문서", 13, false, LibraryStyle.MUTED);
        crumb.setIcon(new Dot());
        crumb.setIconTextGap(8);
        text.add(crumb,BorderLayout.NORTH);
        text.add(title);
        heading.add(text);
        heading.add(new PaperStack(), BorderLayout.EAST);
        return heading;
    }

    /** Large two-tone title. Only fixed UI strings are passed here, never document text. */
    static JLabel headline(String lead, String emphasis) {
        var label = new JLabel();
        label.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD, 38f));
        label.setForeground(LibraryStyle.INK);
        headline(label, lead, emphasis);
        return label;
    }

    static void headline(JLabel label, String lead, String emphasis) {
        label.putClientProperty("html.disable", null);
        label.setText("<html>" + lead + " <span style='color:#c34422'>" + emphasis + "</span></html>");
        label.getAccessibleContext().setAccessibleName(lead + " " + emphasis);
    }

    /** Section title with a hairline above and a red two-digit count, as in the design. */
    static JLabel sectionTitle() {
        var label = new JLabel();
        label.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD, 20f));
        label.setForeground(LibraryStyle.INK);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,0,0,0,LibraryStyle.INK), new EmptyBorder(12,2,6,0)));
        return label;
    }

    static void sectionTitle(JLabel label, String name, int count, boolean more) {
        String number = String.format(Locale.ROOT, "%02d", count) + (more ? "+" : "");
        label.setText("<html>" + name + "&nbsp;&nbsp;<span style='color:#c34422;font-family:Consolas;font-weight:normal'>"
                + number + "</span></html>");
        label.getAccessibleContext().setAccessibleName(name + " " + count + (more ? "개 이상" : "개"));
    }

    /** Rounded panel with an optional flat drop shadow under the bottom edge. */
    static final class Round extends JPanel {
        private final int arc, shadow;
        private final Color fill, line;
        Round(LayoutManager layout, int arc, Color fill, Color line, int shadow) {
            super(layout);
            this.arc = arc; this.fill = fill; this.line = line; this.shadow = shadow;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float w = getWidth() - 1, h = getHeight() - 1 - shadow;
            if (shadow > 0) {
                g.setColor(new Color(0xEAE5D6));
                g.fill(new RoundRectangle2D.Float(0, shadow, w, h, arc, arc));
            }
            g.setColor(fill);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            g.dispose();
        }
        /** Children are clipped to the rounded shape so square backgrounds do not poke out at the corners. */
        @Override protected void paintChildren(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            float w = getWidth() - 1, h = getHeight() - 1 - shadow;
            g.clip(new RoundRectangle2D.Float(0, 0, w + 1, h + 1, arc, arc));
            super.paintChildren(g);
            g.dispose();
            if (line == null) return;
            Graphics2D edge = (Graphics2D) graphics.create();
            edge.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            edge.setColor(line);
            edge.draw(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            edge.dispose();
        }
    }

    private static final class Dot implements Icon {
        public int getIconWidth() { return 7; }
        public int getIconHeight() { return 7; }
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xBC512B));
            g.fillOval(x, y, 7, 7);
            g.dispose();
        }
    }

    private static final class PaperStack extends JComponent {
        PaperStack() { setPreferredSize(new Dimension(150, 96)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < 3; i++) {
                Graphics2D page = (Graphics2D) g.create();
                page.translate(24 + i * 15, 10);
                page.rotate(Math.toRadians(new int[]{-16, 15, -5}[i]), 33, 38);
                page.setColor(new Color(0x18403520, true));
                page.fillRect(4,5,66,74);
                page.setColor(new Color(new int[]{0xEDD993, 0xACBFA7, 0xED986F}[i]));
                page.fillRect(0,0,66,74);
                page.setColor(new Color(0x48483C));
                page.drawRect(0,0,66,74);
                page.setColor(new Color(0x584A38));
                if (i == 0) for (int y = 12; y < 70; y += 7) page.drawLine(1, y, 65, y);
                else if (i == 1) for (int x = 14; x < 66; x += 15) page.drawLine(x, 1, x, 73);
                else for (int y=14; y<62; y+=7) page.drawLine(10,y,y<28?36:54,y);
                page.dispose();
            }
            g.dispose();
        }
    }

    /** Scales a thumbnail to fit the cover area without stretching it. */
    private static final class Fit extends JComponent {
        private final Image image;
        Fit(Icon icon) {
            image = icon instanceof ImageIcon imageIcon ? imageIcon.getImage() : null;
            setPreferredSize(new Dimension(10, 10));
        }
        @Override protected void paintComponent(Graphics graphics) {
            if (image == null) return;
            int iw = image.getWidth(null), ih = image.getHeight(null);
            if (iw <= 0 || ih <= 0) return;
            double scale = Math.min((double) getWidth() / iw, (double) getHeight() / ih);
            int w = (int) Math.round(iw * scale), h = (int) Math.round(ih * scale);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            g.dispose();
        }
    }

    static String format(Library.Hit hit) {
        String filename = hit.source().getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "문서" : filename.substring(dot+1).toUpperCase(Locale.ROOT);
    }

    static String name(Library.Hit hit) {
        String title = hit.title();
        int dot = title.lastIndexOf('.');
        return dot > 0 && title.substring(dot + 1).equalsIgnoreCase(format(hit)) ? title.substring(0, dot) : title;
    }

    /**
     * First readable line of an excerpt. Markdown table header and divider rows (for example
     * {@code | 행 | A |} and {@code | --- | --- |}) are skipped, and a data row shows its cell text.
     */
    static String readable(String excerpt) {
        for (String raw : excerpt.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.matches("\\|?[\\s:|-]*\\|?") ) continue;
            if (line.startsWith("|")) {
                var cells = new java.util.ArrayList<String>();
                for (String cell : line.substring(1).split("\\|")) {
                    String text = cell.strip();
                    if (!text.isEmpty()) cells.add(text);
                }
                if (cells.isEmpty() || cells.stream().allMatch(c -> c.matches("행|[A-Z]{1,3}|\\d+"))) continue;
                cells.removeIf(c -> c.matches("\\d+"));
                line = String.join(" · ", cells).replaceAll("\\b[A-Z]{1,3}\\d+ · ", "");
            }
            line = line.replaceFirst("^#+\\s*", "");
            if (!line.isBlank()) return line;
        }
        return "";
    }

    static ListCellRenderer<Library.Hit> covers(Function<Library.Hit,Icon> thumbnails) {
        return (list, hit, index, selected, focus) -> {
            String format = format(hit);
            var card = new Round(new BorderLayout(), 12, PAPER, selected || focus ? LibraryStyle.ACCENT : LibraryStyle.LINE, 0);
            card.setBorder(new EmptyBorder(1,1,1,1));
            var outer = LibraryStyle.panel(new BorderLayout(), 0);
            outer.setBorder(new EmptyBorder(4,4,10,10));
            outer.add(card);
            var cover = LibraryStyle.panel(new BorderLayout(0,4), 0);
            cover.setOpaque(true);
            cover.setBackground(new Color(switch(format) {
                case "XLS","XLSX" -> 0xE7D398;
                case "PDF" -> 0xADBFAA;
                case "PPT","PPTX" -> 0xE9B092;
                default -> 0xC2D3E0;
            }));
            Icon thumb = thumbnails.apply(hit);
            if (thumb != null) {
                cover.setBorder(new EmptyBorder(8,12,8,12));
                cover.add(new Fit(thumb));
            } else {
                var sheet = LibraryStyle.panel(new BorderLayout(0,3),8);
                sheet.setOpaque(true);
                sheet.setBackground(PAPER);
                sheet.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(0x605D5055, true)), new EmptyBorder(7,10,7,10)));
                cover.setBorder(new EmptyBorder(10,22,8,22));
                sheet.add(LibraryStyle.label(format + "   /   " + hit.section(),11,true,LibraryStyle.MUTED),BorderLayout.NORTH);
                String text = readable(hit.displayExcerpt());
                sheet.add(LibraryStyle.label(text.isBlank() ? name(hit) : text, 15,true,LibraryStyle.INK));
                cover.add(sheet);
            }
            card.add(cover);
            var caption = LibraryStyle.panel(new GridLayout(2,1),0);
            caption.setBorder(new EmptyBorder(8,12,9,12));
            caption.setOpaque(true);
            caption.setBackground(selected ? SELECTED : PAPER);
            caption.add(LibraryStyle.label(name(hit),14,true,LibraryStyle.INK));
            String where = hit.picture()==null ? hit.section() : hit.picture().location();
            if(!hit.otherSources().isEmpty()){
                where+=" · 다른 사본 "+hit.otherSources().size()+"건";
                outer.setToolTipText(String.join(" · ",hit.otherSources().stream().map(Path::toString).toList()));
            }else outer.setToolTipText(null);
            caption.add(LibraryStyle.label(format + " · " + where,12,false,LibraryStyle.MUTED));
            card.add(caption,BorderLayout.SOUTH);
            return outer;
        };
    }

    private SearchStudio() {}
}
