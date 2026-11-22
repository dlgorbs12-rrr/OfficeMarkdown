import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class ReferencePanelTest {
    @TempDir Path fixtures;

    @Test void hiddenSearchCannotOverwriteReplacement() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var oldPublished = new CountDownLatch(1);
        var timedOut = new CountDownLatch(1);
        var staleStatus = new CountDownLatch(1);
        var panel = onEdt(() -> {
            var view = new ReferencePanel(hit -> fail("Unexpected file open"));
            view.setAvailable(false);
            view.search(progress -> { throw new AssertionError("Unavailable result action ran"); });
            assertFalse(view.isWorking());
            assertFalse(view.isVisible());
            view.setAvailable(true);
            view.search(progress -> {
                started.countDown();
                try {
                    releaseOld.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    releaseOld.await();
                }
                progress.accept("늦은 이전 검색");
                oldPublished.countDown();
                return List.of();
            });
            var status = find(view, JLabel.class, label -> label.getText().equals("검색어와 맞는 슬라이드를 찾는 중…"));
            assertNotNull(status);
            status.addPropertyChangeListener("text", event -> {
                String value = (String) event.getNewValue();
                if (value.equals("늦은 이전 검색")) staleStatus.countDown();
                if (value.contains("응답 시간이 초과되었습니다")) timedOut.countDown();
            });
            return view;
        });
        try {
            assertTrue(started.await(5, SECONDS));
            onEdt(() -> {
                panel.setAvailable(false);
                assertFalse(panel.isVisible());
                assertFalse(panel.isWorking());
                panel.setAvailable(true);
                assertFalse(panel.isVisible());
                panel.search(progress -> {
                    if (!oldPublished.await(5, SECONDS)) throw new AssertionError("Old search did not publish");
                    throw new TimeoutException("Provider diagnostic");
                });
                return null;
            });
            assertTrue(interrupted.await(5, SECONDS));
            releaseOld.countDown();
            assertTrue(timedOut.await(5, SECONDS));
            assertEquals(1, staleStatus.getCount());
            assertTrue(onEdt(() -> find(panel, JLabel.class,
                    label -> label.getText().contains("일반 검색 결과는 계속 이용할 수 있습니다.")) != null));
        } finally {
            releaseOld.countDown();
            onEdt(() -> { panel.reset(); return null; });
        }
    }

    @Test void previewsTheIdentifiedSlideAndRejectsChangedOriginalOnOpen() throws Exception {
        var source = fixtures.resolve("구성도.pptx");
        try (var show = new XMLSlideShow(); var output = Files.newOutputStream(source)) {
            show.createSlide();
            var box = show.createSlide().createTextBox();
            box.setAnchor(new Rectangle(20, 20, 300, 35));
            box.setText("인증 서비스 연결");
            show.write(output);
        }
        var library = Files.createDirectory(fixtures.resolve("library"));
        var note = Library.convert(source, library);
        SearchIndex.indexCompleted(library, List.of(note));
        ReferenceIndex.prepare();
        var page = ReferenceIndex.search("인증 서비스 연결", null).stream()
                .filter(item -> item.number() == 2).findFirst().orElseThrow();
        var rendered = new CountDownLatch(1);
        var opened = new CountDownLatch(1);
        var removed = new CountDownLatch(1);
        var target = new AtomicReference<Library.Hit>();
        var panel = onEdt(() -> {
            var view = new ReferencePanel(hit -> { target.set(hit); opened.countDown(); });
            view.setAvailable(true);

            var preview = find(view, JLabel.class,
                    label -> "선택한 슬라이드 미리보기".equals(label.getAccessibleContext().getAccessibleName()));
            assertNotNull(preview);
            preview.addPropertyChangeListener("icon", event -> {
                if (event.getNewValue() instanceof ImageIcon) rendered.countDown();
            });
            view.search(progress -> List.of(new ReferenceSearch.Recommendation(page, "인증 흐름을 보여 줍니다.", "인증 서비스 연결")));
            return view;
        });
        try {
            assertTrue(rendered.await(5, SECONDS));
            onEdt(() -> {
                var evidence = find(panel, JTextArea.class, area -> area.getText().contains("인증 서비스 연결"));
                assertNotNull(evidence);
                assertTrue(evidence.getText().contains("2장"));
                assertTrue(evidence.getText().contains("인증 흐름을 보여 줍니다."));
                var status = find(panel, JLabel.class, label -> label.getText().equals("1장"));
                assertNotNull(status);
                status.addPropertyChangeListener("text", event -> {
                    if (((String) event.getNewValue()).contains("변경된 추천을 제외했습니다")) removed.countDown();
                });
                openButton(panel).doClick(0);
                return null;
            });
            assertTrue(opened.await(5, SECONDS));
            assertEquals(source, target.getAndSet(null).source());

            Files.writeString(source, "바뀐 원본");
            onEdt(() -> { openButton(panel).doClick(0); return null; });
            assertTrue(removed.await(5, SECONDS));
            assertNull(target.get());
        } finally {
            onEdt(() -> { panel.reset(); return null; });
        }
    }

    private static JButton openButton(ReferencePanel panel) {
        var button = find(panel, JButton.class, item -> item.getText().equals("파일 열기"));
        assertNotNull(button);
        return button;
    }

    private static <T extends Component> T find(Container parent, Class<T> type, Predicate<T> matches) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && matches.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container group) {
                T found = find(group, type, matches);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
