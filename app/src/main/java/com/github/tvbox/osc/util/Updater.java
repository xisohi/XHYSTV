package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;

import org.json.JSONObject;

import java.io.File;

/**
 * TVBox 应用更新管理器
 */
public class Updater implements Download.Callback {
    private static final String TAG = "Updater";
    private static final int MAX_RETRY_COUNT = 4;

    private Activity activity;
    private Handler mainHandler;
    private AlertDialog dialog;
    private ProgressDialog progressDialog;
    private int retryCount = 0;
    private boolean forceCheck = false;
    private boolean silentMode = false;
    private String apkName;

    public static Updater create() {
        return new Updater();
    }

    private Updater() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    public Updater force() {
        this.forceCheck = true;
        return this;
    }
    public Updater silent() {
        this.silentMode = true;
        return this;
    }
    public void start(Activity activity) {
        this.activity = activity;
        if (forceCheck && !silentMode) {
            showToast("正在检查更新...");
        }
        new Thread(this::checkUpdate).start();
    }

    /**
     * 获取 JSON 配置地址
     */
    private String getJsonUrl() {
        return Github.getJson("XHYSTV");
    }

    /**
     * 获取 APK 下载地址
     */
    private String getApkUrl() {
        // 使用 BuildConfig.FLAVOR 区分不同版本
        apkName = "XHYSTV-" + BuildConfig.FLAVOR;
        return Github.getApk(apkName);
    }

    /**
     * 检查更新
     */
    private void checkUpdate() {
        try {
            Log.d(TAG, "检查更新: " + getJsonUrl());

            String response = OkGo.<String>get(getJsonUrl())
                    .execute()
                    .body()
                    .string();

            Log.d(TAG, "返回: " + response);

            JSONObject json = new JSONObject(response);
            String latestCode = json.optString("code", "0");
            String name = json.optString("name", "未知版本");
            String desc = json.optString("desc", "暂无更新说明");

            int remoteCode = Integer.parseInt(latestCode);
            int localCode = BuildConfig.VERSION_CODE;

            Log.d(TAG, "本地版本: " + localCode + ", 远程版本: " + remoteCode);

            if (remoteCode > localCode) {
                // 有更新，显示对话框
                mainHandler.post(() -> showUpdateDialog(name, desc));
            } else {
                // 无更新，强制模式才提示
                if (forceCheck && !silentMode) {
                    mainHandler.post(() -> showToast("当前已是最新版本"));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "检查失败: " + e.getMessage());
            // 静默模式不报错，强制模式才提示
            if (forceCheck && !silentMode) {
                mainHandler.post(() -> showToast("检查更新失败: " + e.getMessage()));
            }
        }
    }

    /**
     * 显示更新对话框
     */
    private void showUpdateDialog(String version, String desc) {
        if (activity == null || activity.isFinishing()) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null);

        TextView tvVersion = view.findViewById(R.id.version);
        TextView tvDesc = view.findViewById(R.id.desc);
        TextView btnConfirm = view.findViewById(R.id.confirm);
        TextView btnCancel = view.findViewById(R.id.cancel);

        tvVersion.setText(activity.getString(R.string.update_version, version));
        tvDesc.setText(desc);

        // TV 焦点设置
        btnConfirm.setFocusable(true);
        btnCancel.setFocusable(true);

        dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create();

        dialog.show();

        // 确认更新
        btnConfirm.setOnClickListener(v -> {
            btnConfirm.setEnabled(false);
            btnConfirm.setText("准备下载...");
            startDownload();
        });

        // 取消
        btnCancel.setOnClickListener(v -> dialog.dismiss());

        // 默认焦点在确认
        btnConfirm.requestFocus();
    }

    /**
     * 开始下载
     */
    private void startDownload() {
        String url = getApkUrl();
        Log.i(TAG, "下载: " + url);

        mainHandler.post(() -> {
            if (dialog != null) dialog.dismiss();

            progressDialog = new ProgressDialog(activity);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setTitle("正在下载");
            progressDialog.setMax(100);
            progressDialog.setCancelable(false);
            progressDialog.show();
        });

        File file = new File(activity.getExternalCacheDir(), "update.apk");

        // 使用你的 Download 类
        Download.create(url, file).start(this);
    }

    @Override
    public void progress(int progress) {
        mainHandler.post(() -> {
            if (progressDialog != null) {
                progressDialog.setProgress(progress);
            }
        });
    }

    @Override
    public void error(String msg) {
        Log.e(TAG, "下载错误: " + msg);

        retryCount++;
        if (retryCount < MAX_RETRY_COUNT) {
            Github.switchToNextProxy();
            mainHandler.post(() -> {
                if (progressDialog != null) {
                    progressDialog.setMessage("切换代理重试 " + retryCount + "/" + MAX_RETRY_COUNT);
                }
                // 延迟重试
                mainHandler.postDelayed(this::startDownload, 1500);
            });
        } else {
            mainHandler.post(() -> {
                if (progressDialog != null) progressDialog.dismiss();
                showToast("下载失败，所有代理均不可用");
            });
        }
    }

    @Override
    public void success(File file) {
        Log.i(TAG, "下载成功: " + file.getAbsolutePath());
        mainHandler.post(() -> {
            if (progressDialog != null) progressDialog.dismiss();
            installApk(file);
        });
    }

    /**
     * 安装 APK
     */
    private void installApk(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }

            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            activity.startActivity(intent);

        } catch (Exception e) {
            Log.e(TAG, "安装失败: " + e.getMessage());
            showToast("安装失败: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }
}