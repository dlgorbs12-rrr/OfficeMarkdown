import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

final class UpdateDialog {
    private static final String CHECK_ON_STARTUP = "checkOnStartup";
    private final Window owner;
    private final BooleanSupplier busy;
    private final Runnable exitForUpdate;
    private final Consumer<String> status;
    private final JCheckBoxMenuItem autoCheck;
    private boolean checking;
    private boolean manualCheck;
    private Offer offer;

    private record CheckResult(String current, Optional<Candidate> candidate) {}
    private record Candidate(ReleaseUpdate updater, ReleaseUpdate.Release release, boolean installed) {}

    UpdateDialog(Window owner, BooleanSupplier busy, Runnable exitForUpdate, Consumer<String> status) {
        this.owner = owner;
        this.busy = busy;
        this.exitForUpdate = exitForUpdate;
        this.status = status;
        Preferences preferences = Preferences.userRoot().node("OfficeMarkdown/updates");
        autoCheck = new JCheckBoxMenuItem("시작할 때 업데이트 확인", preferences.getBoolean(CHECK_ON_STARTUP, true));
        autoCheck.addActionListener(event -> preferences.putBoolean(CHECK_ON_STARTUP, autoCheck.isSelected()));
    }

    JCheckBoxMenuItem autoCheckItem() {
        return autoCheck;
    }

    void checkOnStartup() {
        if (autoCheck.isSelected()) check(false);
    }

    void checkManually() {
        check(true);
    }

    private void check(boolean manual) {
        if (offer != null) {
            if (manual) offer.toFront();
            return;
        }
        if (manual) {
            manualCheck = true;
            status.accept("업데이트 확인 중…");
        }
        if (checking) return;
        checking = true;
        new SwingWorker<CheckResult, Void>() {
            @Override protected CheckResult doInBackground() throws IOException, InterruptedException {
                String current = AppVersion.current();
                var updater = ReleaseUpdate.create();
                return new CheckResult(current,
                        updater.check(current).map(release -> new Candidate(updater, release, AppVersion.installed())));
            }

            @Override protected void done() {
                checking = false;
                boolean showStatus = manualCheck;
                manualCheck = false;
                if (!owner.isDisplayable() || (!showStatus && !autoCheck.isSelected())) return;
                try {
                    CheckResult result = get();
                    Optional<Candidate> candidate = result.candidate();
                    if (candidate.isEmpty()) {
                        if (showStatus) status.accept("새 업데이트가 없습니다 · 현재 버전 " + result.current());
                        return;
                    }
                    if (showStatus) status.accept("");
                    offer = new Offer(candidate.get(), result.current());
                    offer.setVisible(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    if (showStatus) status.accept("업데이트를 확인할 수 없습니다.");
                }
            }
        }.execute();
    }

    private final class Offer extends JDialog {
        private final Candidate candidate;
        private final JTextArea detail = new JTextArea(3, 38);
        private final JProgressBar progress = new JProgressBar();
        private final JButton action = new JButton("업데이트");
        private final JButton releasePage = new JButton("릴리스 페이지 열기");
        private final JButton close = new JButton("나중에");
        private Thread worker;
        private Path installer;

        Offer(Candidate candidate, String current) {
            super(owner, "Office Markdown 업데이트", Dialog.ModalityType.MODELESS);
            this.candidate = candidate;
            var body = LibraryStyle.panel(new BorderLayout(0, 12), 18);
            var heading = LibraryStyle.panel(new BorderLayout(0, 4), 0);
            heading.add(LibraryStyle.label("새 버전 " + candidate.release().version(), 20, true, LibraryStyle.INK),
                    BorderLayout.NORTH);
            heading.add(LibraryStyle.label("현재 버전 " + current, 14, false, LibraryStyle.MUTED));
            body.add(heading, BorderLayout.NORTH);

            detail.setText(candidate.installed() ? "업데이트를 누르면 설치 파일의 SHA-256을 확인한 뒤 설치를 시작하고 앱을 종료합니다."
                    : "로컬 빌드와 휴대용 실행본은 릴리스 페이지에서 새 버전과 설치 안내를 확인하세요.");
            detail.setOpaque(false);
            detail.setEditable(false);
            detail.setFont(LibraryStyle.TYPE);
            detail.setForeground(LibraryStyle.INK);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            body.add(detail);

            var actions = LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0), 0);
            LibraryStyle.button(close, false);
            LibraryStyle.button(releasePage, !candidate.installed());
            actions.add(close);
            actions.add(releasePage);
            if (candidate.installed()) {
                LibraryStyle.button(action, true);
                actions.add(action);
            }
            var footer = LibraryStyle.panel(new BorderLayout(0, 8), 0);
            progress.setVisible(false);
            progress.setIndeterminate(true);
            progress.getAccessibleContext().setAccessibleName("설치 파일 다운로드 및 검증 진행");
            footer.add(progress, BorderLayout.NORTH);
            footer.add(actions, BorderLayout.SOUTH);
            body.add(footer, BorderLayout.SOUTH);

            close.addActionListener(event -> dispose());
            releasePage.addActionListener(event -> browse());
            action.addActionListener(event -> {
                if (!readyToInstall()) return;
                if (installer == null) download();
                else verifyForInstall();
            });
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setContentPane(body);
            getRootPane().setDefaultButton(close);
            setResizable(false);
            pack();
            setLocationRelativeTo(owner);
        }

        private void browse() {
            try {
                Desktop.getDesktop().browse(candidate.release().page());
                if (!candidate.installed()) dispose();
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                detail.setText("브라우저에서 릴리스 페이지를 열 수 없습니다.\n" + candidate.release().page());
            }
        }

        private void download() {
            detail.setText("설치 파일을 다운로드하고 검증하는 중입니다.");
            setWorking("다운로드 취소");
            worker = Thread.startVirtualThread(() -> {
                Path verified = null;
                try {
                    verified = candidate.updater().download(candidate.release());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException e) {
                    // Report the failure on the event dispatch thread.
                }
                Path downloaded = verified;
                SwingUtilities.invokeLater(() -> downloadFinished(downloaded));
            });
        }

        private void downloadFinished(Path downloaded) {
            worker = null;
            if (!isDisplayable()) {
                discard(downloaded);
                return;
            }
            installer = downloaded;
            setWorking(null);
            if (installer == null) {
                detail.setText("설치 파일 다운로드 또는 SHA-256 확인에 실패했습니다. 다시 시도할 수 있습니다.");
                return;
            }
            verifyForInstall();
        }

        private void setWorking(String cancelText) {
            boolean working = cancelText != null;
            progress.setVisible(working);
            action.setEnabled(!working);
            releasePage.setEnabled(!working);
            close.setText(working ? cancelText : "나중에");
            pack();
        }

        private void verifyForInstall() {
            if (!readyToInstall()) return;
            Path downloaded = installer;
            detail.setText("설치 파일의 SHA-256을 다시 확인하는 중입니다.");
            setWorking("검증 취소");
            worker = Thread.startVirtualThread(() -> {
                boolean valid = false;
                try {
                    candidate.updater().verifyInstaller(candidate.release(), downloaded);
                    valid = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException e) {
                    // A changed or unavailable file must be downloaded again.
                }
                boolean verified = valid;
                SwingUtilities.invokeLater(() -> verificationFinished(downloaded, verified));
            });
        }

        private boolean readyToInstall() {
            if (!busy.getAsBoolean()) return true;
            detail.setText("진행 중인 작업이 끝나면 업데이트를 다시 눌러 주세요.");
            return false;
        }

        private void verificationFinished(Path downloaded, boolean verified) {
            worker = null;
            if (!isDisplayable()) {
                discard(downloaded);
                return;
            }
            setWorking(null);
            if (!verified) {
                discard(installer);
                installer = null;
                action.setText("업데이트");
                detail.setText("설치 파일이 변경되었거나 확인할 수 없습니다. 다시 다운로드하세요.");
                return;
            }
            if (!readyToInstall()) return;
            try {
                AppVersion.startInstaller(downloaded);
            } catch (IOException | SecurityException e) {
                detail.setText("설치 프로그램을 시작할 수 없습니다. 설치를 다시 시도할 수 있습니다.");
                return;
            }
            installer = null;
            dispose();
            exitForUpdate.run();
        }

        @Override public void dispose() {
            // A running download or verification hands its temporary file back in its completion callback.
            if (worker != null) worker.interrupt();
            else discard(installer);
            installer = null;
            if (offer == this) offer = null;
            super.dispose();
        }

        private void discard(Path downloaded) {
            if (downloaded == null) return;
            Thread.startVirtualThread(() -> {
                try {
                    candidate.updater().discard(downloaded);
                } catch (IOException e) {
                    // Cleanup is best effort. The updater uses a fresh directory for each download.
                }
            });
        }
    }
}
