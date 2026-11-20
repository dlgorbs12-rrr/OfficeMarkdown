import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LatestVersionSelectionTest {
    @Test void ordersVersionComponentsAndDatesWithinOneDirectoryAndExtension() {
        var selection = new LatestVersionSelection();
        var inputs = List.of(
                "source/매뉴얼_ver0.9.xlsx", "source/매뉴얼_ver1.1.XLSX", "source/매뉴얼_ver1.10.xlsx",
                "source/nested/매뉴얼_ver1.1.xlsx", "source/매뉴얼_ver1.1.xls",
                "source/다른 매뉴얼_ver0.70_150304.xlsx", "source/다른 매뉴얼_ver0.71_150305.xlsx",
                "source/e-WP_Project_Management__Audit_Log_2015-07-20T160000.xlsx",
                "source/e-WP_Project_Management__Audit_Log_2015-07-20T170000.xlsx",
                "source/일간 현황_2015-07-20.xlsx", "source/일간 현황_20150721.xlsx");
        inputs.forEach(input -> selection.record(Path.of(input), true));

        var plan = selection.plan(inputs.size());
        assertEquals(0, plan.preview().ambiguousGroups());
        assertEquals(5, plan.preview().latestSkipped());
        assertEquals(Set.of(Path.of(inputs.get(0)), Path.of(inputs.get(1)), Path.of(inputs.get(5)),
                Path.of(inputs.get(7)), Path.of(inputs.get(9))), plan.skippedPaths());
    }

    @Test void keepsEntireGroupWhenOrderingOrGroupingIsUncertain() {
        var selection = new LatestVersionSelection();
        var inputs = List.of(
                "nonmonotonic_ver1_2025-01-02.pptx", "nonmonotonic_ver2_2025-01-01.pptx", "nonmonotonic_ver3_2025-01-03.pptx",
                "same rank_ver1.pptx", "same rank_v1.0.pptx", "same rank_v2.pptx",
                "mixed_ver1.pptx", "mixed_ver2_250103.pptx",
                "same day_2025-01-01.pptx", "same day_2025-01-01T120000.pptx",
                "invalid_2025-02-10.pptx", "invalid_2025-02-30.pptx",
                "marked_v1.pptx", "marked_v2.pptx", "marked.pptx",
                "codes_150304.pptx", "codes_150305.pptx");
        for (String input : inputs) selection.record(Path.of("source", input), true);
        for (String input : inputs) selection.checkUnmarked(Path.of("source", input));

        var plan = selection.plan(inputs.size());
        assertEquals(6, plan.preview().ambiguousGroups());
        assertEquals(inputs.size(), plan.preview().selected());
        assertTrue(plan.skippedPaths().isEmpty());
    }
}
