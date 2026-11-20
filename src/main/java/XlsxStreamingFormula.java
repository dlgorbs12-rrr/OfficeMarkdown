import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;

final class XlsxStreamingFormula {
    private static final String COLUMN = "\\$?[A-Za-z]{1,3}";
    private static final String ROW = "\\$?[1-9][0-9]{0,6}";
    private static final Pattern REFERENCE = Pattern.compile(
            "(" + COLUMN + ")(" + ROW + ")"
            + "|(" + COLUMN + "):(" + COLUMN + ")"
            + "|(" + ROW + "):(" + ROW + ")");

    // Shared formulas store their expression once. Translate only Excel A1 references, leaving
    // quoted text, sheet qualifiers, names, functions and structured references as written.
    static String shift(String expression, int rows, int columns) {
        if (rows == 0 && columns == 0) return expression;
        var out = new StringBuilder(expression.length());
        Matcher candidate = REFERENCE.matcher(expression);
        for (int i = 0; i < expression.length();) {
            char ch = expression.charAt(i);
            int end = switch (ch) {
                case '"', '\'' -> quotedEnd(expression, i, ch);
                case '[' -> bracketEnd(expression, i);
                default -> i;
            };
            if (end > i) {
                out.append(expression, i, end);
                i = end;
                continue;
            }
            if ((ch == '$' || Character.isLetterOrDigit(ch)) && (i == 0 || !nameChar(expression.charAt(i - 1)))
                    && candidate.region(i, expression.length()).lookingAt()
                    && isReference(expression, candidate)) {
                out.append(move(candidate, rows, columns));
                i = candidate.end();
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isReference(String expression, Matcher candidate) {
        int end = candidate.end();
        if (end < expression.length()) {
            char next = expression.charAt(end);
            if (nameChar(next) || next == '!' || next == '(' || next == '[') return false;
            if (next == ':') {
                int cursor = end + 1;
                while (cursor < expression.length() && nameChar(expression.charAt(cursor))) cursor++;
                if (cursor < expression.length() && expression.charAt(cursor) == '!') return false;
            }
        }
        if (candidate.group(1) != null) return validColumn(candidate.group(1)) && validRow(candidate.group(2));
        if (candidate.group(3) != null) return validColumn(candidate.group(3)) && validColumn(candidate.group(4));
        return validRow(candidate.group(5)) && validRow(candidate.group(6));
    }

    private static String move(Matcher candidate, int rows, int columns) {
        if (candidate.group(1) != null) {
            String col = moveColumn(candidate.group(1), columns);
            String row = moveRow(candidate.group(2), rows);
            return col == null || row == null ? "#REF!" : col + row;
        }
        if (candidate.group(3) != null) {
            return range(moveColumn(candidate.group(3), columns), moveColumn(candidate.group(4), columns));
        }
        return range(moveRow(candidate.group(5), rows), moveRow(candidate.group(6), rows));
    }

    private static String range(String first, String last) {
        return (first == null ? "#REF!" : first) + ":" + (last == null ? "#REF!" : last);
    }

    private static String moveColumn(String value, int delta) {
        if (value.startsWith("$") || delta == 0) return value;
        int moved = XlsxStreaming.columnOf(value) + delta;
        if (moved < 0 || moved >= XlsxStreaming.MAX_COLUMNS) return null;
        String column = CellReference.convertNumToColString(moved);
        return Character.isLowerCase(value.charAt(0)) ? column.toLowerCase(Locale.ROOT) : column;
    }

    private static String moveRow(String value, int delta) {
        if (value.startsWith("$") || delta == 0) return value;
        int moved = Integer.parseInt(value) + delta;
        return moved < 1 || moved > XlsxStreaming.MAX_ROWS ? null : Integer.toString(moved);
    }

    private static boolean validColumn(String column) {
        return XlsxStreaming.columnOf(column.startsWith("$") ? column.substring(1) : column) >= 0;
    }

    private static boolean validRow(String row) {
        return Integer.parseInt(row.startsWith("$") ? row.substring(1) : row) <= XlsxStreaming.MAX_ROWS;
    }

    private static boolean nameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '\\' || ch == '$';
    }

    private static int quotedEnd(String expression, int start, char quote) {
        for (int i = start + 1; i < expression.length(); i++) {
            if (expression.charAt(i) != quote) continue;
            if (i + 1 >= expression.length() || expression.charAt(i + 1) != quote) return i + 1;
            i++;
        }
        return expression.length();
    }

    private static int bracketEnd(String expression, int start) {
        int depth = 1;
        for (int i = start + 1; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (ch == '\'') {
                i++;
            } else if (ch == '[') {
                depth++;
            } else if (ch == ']' && --depth == 0) {
                return i + 1;
            }
        }
        return expression.length();
    }
}
