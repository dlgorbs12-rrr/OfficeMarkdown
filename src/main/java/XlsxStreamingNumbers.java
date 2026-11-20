import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Date1904Support;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

final class XlsxStreamingNumbers {
    private final DataFormatter display = new DataFormatter(Locale.KOREA);
    private final Cell cell;
    private double value;
    private short formatId;
    private String formatCode;

    XlsxStreamingNumbers(boolean date1904) {
        // POI's raw formatting path rounds and scales numbers differently. Expose only the
        // numeric cell properties its display formatter reads, with one reusable view per worksheet.
        var workbook = (Workbook) Proxy.newProxyInstance(Workbook.class.getClassLoader(),
                new Class<?>[]{Workbook.class, Date1904Support.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isDate1904")) return date1904;
                    throw new UnsupportedOperationException(method.getName());
                });
        var sheet = view(Sheet.class, (proxy, method, args) -> {
            if (method.getName().equals("getWorkbook")) return workbook;
            throw new UnsupportedOperationException(method.getName());
        });
        var style = view(CellStyle.class, (proxy, method, args) -> switch (method.getName()) {
            case "getDataFormat" -> formatId;
            case "getDataFormatString" -> formatCode;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        cell = view(Cell.class, (proxy, method, args) -> switch (method.getName()) {
            case "getCellType" -> CellType.NUMERIC;
            case "getNumericCellValue" -> value;
            case "getDateCellValue" -> DateUtil.getJavaDate(value, date1904);
            case "getCellStyle" -> style;
            case "getSheet" -> sheet;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    String format(double value, int formatId, String formatCode) {
        this.value = value;
        this.formatId = (short) formatId;
        this.formatCode = formatCode;
        return display.formatCellValue(cell);
    }

    private static <T> T view(Class<T> type, InvocationHandler getter) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, getter));
    }
}
