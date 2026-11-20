import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

final class LibraryStyle {
    static final Color CANVAS = new Color(0xF4F4F8), INK = new Color(0x282539),
            MUTED = new Color(0x645F73), PURPLE = new Color(0x5842AD), LINE = new Color(0xDEDBE8);
    static final Font TYPE = new Font("Malgun Gothic", Font.PLAIN, 14);

    static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(TYPE.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float)size));
        label.setForeground(color);
        return label;
    }

    static JPanel panel(LayoutManager layout, int padding) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(padding,padding,padding,padding));
        return panel;
    }

    static JPanel card(LayoutManager layout) {
        JPanel p = panel(layout, 16);
        p.setBackground(Color.WHITE);
        p.setOpaque(true);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(16,16,16,16)));
        return p;
    }

    static void button(JButton button, boolean primary) {
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setMargin(new Insets(6,14,6,14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (primary) {
            button.setBackground(PURPLE);
            button.setForeground(Color.WHITE);
            button.setFont(TYPE.deriveFont(Font.BOLD));
        }
    }

    static Icon document(String type, int size, Color color) {
        return new Icon() {
            public int getIconWidth() { return size; }
            public int getIconHeight() { return size; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D)graphics.create();
                g.translate(x,y); g.scale(size/48d,size/48d);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),28));
                g.fill(new RoundRectangle2D.Float(0,0,48,48,13,13));
                g.setColor(color); g.fillRoundRect(13,8,23,32,4,4);
                g.setColor(Color.WHITE);
                if (type.isEmpty()) {
                    for (int i=0;i<3;i++) g.fillRoundRect(18,17+i*5,i==2?8:13,2,2,2);
                } else {
                    g.setFont(new Font("Dialog",Font.BOLD,17));
                    g.drawString(type,18,31);
                }
                g.dispose();
            }
        };
    }

    static ListCellRenderer<Library.Hit> renderer(java.util.function.Function<Library.Hit,Icon> thumbnails) {
        return (list, hit, index, selected, focus) -> {
            JPanel row = panel(new BorderLayout(12,0), 10);
            row.setOpaque(true);
            row.setBackground(selected ? new Color(0xEDE8FB) : Color.WHITE);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0,selected?3:0,1,0,selected?PURPLE:LINE),
                    new EmptyBorder(9,selected?9:12,9,10)));
            Icon thumbnail = thumbnails.apply(hit);
            if (thumbnail == null) {
                String name = hit.source().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                thumbnail = switch (name.substring(name.lastIndexOf('.') + 1)) {
                    case "xls", "xlsx" -> document("X", 38, new Color(0x24765D));
                    case "doc", "docx" -> document("W", 38, new Color(0x315B99));
                    case "pdf" -> document("F", 38, new Color(0xA22D35));
                    default -> document("P", 38, new Color(0xB95331));
                };
            }
            row.add(new JLabel(thumbnail),BorderLayout.WEST);
            JPanel text = panel(new GridLayout(2,1),0);
            text.add(label(hit.title(),14,true,INK));
            String detail = hit.picture() == null ? hit.section() : hit.picture().location();
            text.add(label(detail+"   "+hit.excerpt().replaceAll("\\R"," "),12,false,MUTED));
            row.add(text);
            return row;
        };
    }
}
