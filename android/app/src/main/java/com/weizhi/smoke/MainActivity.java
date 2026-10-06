package com.weizhi.smoke;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.weizhi.WeizhiEngine;
import com.weizhi.agent.AgentToolsBundle;
import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.caps.AndroidCaps;
import com.weizhi.platform.OrganizeFiles;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo host for productivity caps. Each button runs one short JS task on a background thread
 * so {@code android.ui.confirm} can show a dialog on the main thread without deadlocking.
 */
public final class MainActivity extends Activity {
    private static final int REQ_TREE = 42;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView log;
    private File workspace;
    private CountDownLatch pickLatch;
    private String pickedUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        workspace = new File(getFilesDir(), "docs");
        if (!workspace.exists() && !workspace.mkdirs()) {
            throw new IllegalStateException("workspace");
        }
        seed(new File(workspace, "发票.txt"), "发票草稿");
        seed(new File(workspace, "封面.jpg"), "photo");
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(32, 32, 32, 32);
        addButton(column, "确认对话框", "android.ui.confirm('将整理文档，是否继续？')");
        addButton(column, "写文档并读回",
                "android.files.write('note.txt','发票草稿'); android.files.read('note.txt')");
        addButton(column, "重命名 / 移动 / 撤销",
                "android.files.write('keep/a.txt','');"
                        + "android.files.write('note.txt','合同');"
                        + "android.files.rename('note.txt','合同.txt');"
                        + "android.files.move('合同.txt','keep');"
                        + "var u = android.files.undo();"
                        + "({undo:u.ok, text:android.files.read('合同.txt'), audit:android.audit.recent().length})");
        addButton(column, "整理沙箱文档", OrganizeFiles.run("android"));
        addButton(column, "整理所选文件夹",
                "android.files.pickDirectory(); " + OrganizeFiles.run("android"));
        addButton(column, "分享文案", "android.share.send({title:'朋友圈草稿', text:'今天的行程已排好'})");
        addButton(column, "打开示例文本", "android.intent.start({action:'view', path:'发票.txt'})");
        addButton(column, "5 秒后提醒",
                "var s = android.reminders.schedule({title:'出发', body:'检查证件', atMs: Date.now()+5000}); s");
        addButton(column, "Agent bash", null);
        addButton(column, "Agent run_js", null);
        addButton(column, "Agent skill", null);
        log = new TextView(this);
        log.setPadding(0, 24, 0, 0);
        log.setText("Weizhi Demo\n平台对象: android\n工作区: " + workspace.getAbsolutePath());
        column.addView(log);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
    }

    private static void seed(File file, String text) {
        if (file.exists()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The organize button reports a clear script error if the sample files are missing.
        }
    }

    private void addButton(LinearLayout column, String label, String js) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("Agent bash".equals(label)) {
                    runAgentTools("bash", Map.of("command", "pwd"));
                    return;
                }
                if ("Agent run_js".equals(label)) {
                    runAgentTools("run_js", Map.of("code", "JSON.stringify(1+2)"));
                    return;
                }
                if ("Agent skill".equals(label)) {
                    runAgentTools("load_skill_through_path",
                            Map.of("skillId", "demo", "path", "SKILL.md"));
                    return;
                }
                runSnippet(label, js, "整理所选文件夹".equals(label), "分享文案".equals(label));
            }
        });
        column.addView(button);
    }

    private void runAgentTools(String tool, Map<String, Object> input) {
        append(tool + " …");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AgentToolkit tk = AgentToolsBundle.builder(Paths.get(workspace.getAbsolutePath()))
                            .compositeSkills(MainActivity.this, "agent_skills")
                            .engineConfigure(engine -> {
                                try {
                                    AndroidCaps.Session session =
                                            new AndroidCaps.Session(MainActivity.this, workspace);
                                    session.confirmer = message -> true;
                                    AndroidCaps.install(engine, session);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .build();
                    String out = tk.call(tool, input);
                    append(tool + "\n" + out);
                } catch (Exception e) {
                    append(tool + " 失败\n" + e.getMessage());
                }
            }
        });
    }

    private void runSnippet(String label, String js, boolean pick, boolean shareSheet) {
        append(label + " …");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                AndroidCaps.Session session = new AndroidCaps.Session(MainActivity.this, workspace);
                session.confirmer = new com.weizhi.platform.PlatformHost.Confirmer() {
                    @Override
                    public boolean confirm(String message) throws Exception {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            throw new IllegalStateException("runJs must not be on the main thread");
                        }
                        AtomicBoolean ok = new AtomicBoolean(false);
                        CountDownLatch latch = new CountDownLatch(1);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setMessage(message)
                                        .setPositiveButton("继续", (d, w) -> {
                                            ok.set(true);
                                            latch.countDown();
                                        })
                                        .setNegativeButton("取消", (d, w) -> latch.countDown())
                                        .setOnCancelListener(d -> latch.countDown())
                                        .show();
                            }
                        });
                        if (!latch.await(60, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("ui.confirm timed out");
                        }
                        return ok.get();
                    }
                };
                if (pick) {
                    session.directoryPicker = new com.weizhi.platform.PlatformHost.DirectoryPicker() {
                        @Override
                        public String pick() throws Exception {
                            pickedUri = null;
                            pickLatch = new CountDownLatch(1);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                                    startActivityForResult(intent, REQ_TREE);
                                }
                            });
                            if (!pickLatch.await(120, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("pickDirectory timed out");
                            }
                            return pickedUri;
                        }
                    };
                }
                session.launchShareSheet = shareSheet;
                session.launchIntent = "打开示例文本".equals(label);
                try (WeizhiEngine engine = new WeizhiEngine()) {
                    AndroidCaps.install(engine, session);
                    String out = engine.runJs(js, 120000);
                    append(label + "\n" + out);
                } catch (Exception e) {
                    append(label + " 失败\n" + e.getMessage());
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {
                // Listing still works for this session when the grant flags are present.
            }
            pickedUri = uri.toString();
        } else {
            pickedUri = null;
        }
        if (pickLatch != null) {
            pickLatch.countDown();
        }
    }

    private void append(String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log.setText(line);
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
