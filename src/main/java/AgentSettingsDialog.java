import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import javax.swing.*;

final class AgentSettingsDialog extends JDialog {
    private final JComboBox<DocumentAgent.Provider> agents=new JComboBox<>(DocumentAgent.Provider.values());
    private final JTextField location=new JTextField();
    private final JLabel auth=new JLabel(),test=new JLabel();
    private final JButton login=new JButton("로그인 열기"),probe=new JButton("연결 테스트"),refresh=new JButton("다시 확인");
    private final JLabel guideText=new JLabel();
    private SwingWorker<?,?> worker;
    private boolean saved;

    static DocumentAgent.Provider open(Window parent,DocumentAgent.Provider selected){
        var d=new AgentSettingsDialog(parent,selected);
        d.setVisible(true);
        return d.saved ? d.selected() : null;
    }
    private DocumentAgent.Provider selected(){return (DocumentAgent.Provider)agents.getSelectedItem();}
    private AgentSettingsDialog(Window parent,DocumentAgent.Provider selected){
        super(parent,"에이전트 연결 설정",ModalityType.APPLICATION_MODAL);
        var panel=LibraryStyle.panel(new GridBagLayout(),18);
        GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;
        c.anchor=GridBagConstraints.WEST;c.insets=new Insets(4,0,4,0);
        agents.setSelectedItem(selected);
        var agentRow=LibraryStyle.panel(new BorderLayout(10,0),0);
        agentRow.add(LibraryStyle.label("연결할 에이전트",13,true,LibraryStyle.INK),BorderLayout.WEST);agentRow.add(agents);
        panel.add(agentRow,c);
        c.insets=new Insets(14,0,4,0);
        panel.add(step("1","CLI 설치"),c);
        c.insets=new Insets(4,0,4,0);
        panel.add(guideText,c);
        location.setEditable(false);panel.add(location,c);
        var browser=new JButton("공식 설치 안내");var browse=new JButton("실행 파일 지정");var auto=new JButton("자동 탐색");
        panel.add(row(browser,browse,auto),c);
        c.insets=new Insets(14,0,4,0);
        panel.add(step("2","계정 연결"),c);
        c.insets=new Insets(4,0,4,0);
        panel.add(auth,c);panel.add(row(login,refresh),c);
        c.insets=new Insets(14,0,4,0);
        panel.add(step("3","문서 질문 연결 확인"),c);
        c.insets=new Insets(4,0,4,0);
        panel.add(LibraryStyle.label("선택한 에이전트에 짧은 테스트 문구를 보내 응답을 확인합니다.",13,false,LibraryStyle.SECONDARY),c);
        panel.add(test,c);panel.add(row(probe),c);
        for(var button:java.util.List.of(browser,browse,auto,login,refresh,probe))LibraryStyle.button(button,false);
        var use=new JButton("기본 에이전트로 저장");LibraryStyle.button(use,true);
        var close=new JButton("닫기");LibraryStyle.button(close,false);
        var footer=LibraryStyle.panel(new FlowLayout(FlowLayout.RIGHT,6,0),0);
        footer.add(close);footer.add(use);
        c.weighty=1;c.anchor=GridBagConstraints.SOUTH;c.insets=new Insets(16,0,0,0);
        panel.add(footer,c);
        panel.setOpaque(true);panel.setBackground(Color.WHITE);
        setContentPane(panel);setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        agents.addActionListener(e->inspect());
        browser.addActionListener(e->{try{Desktop.getDesktop().browse(AgentConnection.guide(selected()));}catch(Exception ex){message(ex);}});
        browse.addActionListener(e->{
            var picker=new JFileChooser();picker.setDialogTitle(selected().exe+".exe 선택");
            if(picker.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
            try{AgentConnection.path(selected(),picker.getSelectedFile().toPath());inspect();}catch(Exception ex){message(ex);}
        });
        auto.addActionListener(e->{try{AgentConnection.path(selected(),null);inspect();}catch(Exception ex){message(ex);}});
        login.addActionListener(e->{try{AgentConnection.login(selected());auth.setText("열린 터미널·브라우저에서 로그인한 뒤 ‘다시 확인’을 누르세요.");}catch(Exception ex){message(ex);}});
        refresh.addActionListener(e->inspect());
        probe.addActionListener(e->{
            var p=selected();test.setText("에이전트 응답 확인 중…");
            run(()->{
                String reply=DocumentAgent.invoke(p,"Return exactly OFFICE_CONNECTED and nothing else. Do not use tools.");
                if(!reply.strip().equals("OFFICE_CONNECTED"))throw new java.io.IOException("예상한 테스트 응답을 받지 못했습니다.");
                return "연결 성공 · 문서에 질문할 수 있습니다.";
            },test);
        });
        use.addActionListener(e->{AgentConnection.prefer(selected());saved=true;dispose();});
        close.addActionListener(e->dispose());
        setSize(640,600);setMinimumSize(new Dimension(580,560));setLocationRelativeTo(parent);inspect();
    }
    private static JPanel step(String number,String title){
        var p=LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,8,0),0);
        var badge=new JLabel(number,SwingConstants.CENTER){
            protected void paintComponent(Graphics graphics){
                var g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(LibraryStyle.INK);g.fillOval(0,0,getWidth()-1,getHeight()-1);g.dispose();
                super.paintComponent(graphics);
            }
        };
        badge.setForeground(Color.WHITE);badge.setFont(LibraryStyle.TYPE.deriveFont(Font.BOLD,11f));
        badge.setPreferredSize(new Dimension(20,20));
        p.add(badge);p.add(LibraryStyle.label(title,15,true,LibraryStyle.INK));
        return p;
    }
    private JPanel row(JButton... buttons){
        JPanel p=LibraryStyle.panel(new FlowLayout(FlowLayout.LEFT,5,0),0);
        for(JButton b:buttons)p.add(b);return p;
    }
    private void message(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"에이전트 연결 설정",JOptionPane.INFORMATION_MESSAGE);}
    private void inspect(){
        if(worker!=null)worker.cancel(true);
        var p=selected();test.setText("연결 테스트 전");
        guideText.setText("Windows용 "+p+" CLI를 설치한 뒤 ‘다시 확인’을 누르세요.");
        try{
            Path exe=p.executable();location.setText(exe.toString());location.setCaretPosition(0);
            auth.setText("인증 상태 확인 중…");login.setEnabled(true);probe.setEnabled(true);
            run(()->AgentConnection.auth(p,exe),auth);
        }catch(Exception ex){
            location.setText("실행 파일을 찾을 수 없습니다. 설치 후 "+p.exe+".exe를 지정할 수 있습니다.");
            auth.setText("CLI 설치 후 로그인할 수 있습니다.");login.setEnabled(false);probe.setEnabled(false);refresh.setEnabled(true);
        }
    }
    private void run(Callable<String> action,JLabel label){
        if(worker!=null)worker.cancel(true);
        probe.setEnabled(false);refresh.setEnabled(false);
        var job=new SwingWorker<String,Void>(){
            protected String doInBackground()throws Exception{return action.call();}
            protected void done(){
                if(isCancelled() || worker!=this)return;
                try{label.setText(get());}
                catch(Exception ex){label.setText("확인 실패 · 로그인, 구독 또는 CLI 상태를 확인하세요.");message(ex.getCause() instanceof Exception cause?cause:ex);}
                finally{probe.setEnabled(true);refresh.setEnabled(true);}
            }
        };worker=job;job.execute();
    }
    @Override public void dispose(){if(worker!=null)worker.cancel(true);super.dispose();}
}
