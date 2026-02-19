package com.github.tvbox.osc.util;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * TVBox 应用更新管理器
 * 支持多代理切换、Android 4.4+ 兼容安装
 * 改进：动态选择下载路径（有存储权限时优先外部缓存，否则自动降级内部缓存）
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
    private boolean isInstallTriggered = false; // 防止重复安装

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
     * 获取 JSON 配置地址（无需代理）
     */
    private String getJsonUrl() {
        return Github.getJson("XHYSTV");
    }

    /**
     * 获取 APK 下载地址（已加速）
     */
    private String getApkUrl() {
        // 根据 BuildConfig.FLAVOR 生成对应 APK 文件名
        apkName = "XHYSTV-" + BuildConfig.FLAVOR;
        return Github.getApk(apkName);
    }

    /**
     * 检查更新（子线程执行）
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
            int remoteCode = json.optInt("code", 0);
            int localCode = BuildConfig.VERSION_CODE;

            Log.d(TAG, "本地版本: " + localCode + ", 远程版本: " + remoteCode);

            if (remoteCode > localCode) {
                String name = json.optString("name", "未知版本");
                String desc = json.optString("desc", "暂无更新说明");
                mainHandler.post(() -> showUpdateDialog(name, desc));
            } else {
                if (forceCheck && !silentMode) {
                    mainHandler.post(() -> showToast("当前已是最新版本"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检查失败: " + e.getMessage());
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

        btnConfirm.setOnClickListener(v -> {
            btnConfirm.setEnabled(false);
            btnConfirm.setText("准备下载...");
            startDownload();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.requestFocus();
    }

    // ========== 新增：权限检查和路径选择方法 ==========
    /**
     * 检查是否拥有存储权限（兼容 Android 6.0+）
     */
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // 6.0 以下无需动态权限
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 获取可用的缓存目录（外部缓存优先，不可用时回退到内部缓存）
     */
    private File getAvailableCacheDir() {
        if (hasStoragePermission()) {
            File externalCache = activity.getExternalCacheDir();
            if (externalCache != null && externalCache.canWrite()) {
                Log.d(TAG, "使用外部缓存目录: " + externalCache.getPath());
                return externalCache;
            } else {
                Log.d(TAG, "外部缓存不可用，回退到内部缓存");
            }
        }
        File internalCache = activity.getCacheDir();
        Log.d(TAG, "使用内部缓存目录: " + internalCache.getPath());
        return internalCache;
    }

    /**
     * 开始下载 APK
     */
    private void startDownload() {
        String url = getApkUrl();
        Log.i(TAG, "下载: " + url);

        mainHandler.post(() -> {
            if (dialog != null) dialog.dismiss();
            // 关闭旧的进度条（防止重叠）
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            progressDialog = new ProgressDialog(activity);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setTitle("正在下载");
            progressDialog.setMax(100);
            progressDialog.setCancelable(false);
            progressDialog.show();
        });

        // 动态选择缓存目录
        File cacheDir = getAvailableCacheDir();
        File file = new File(cacheDir, "update.apk");
        // 如果文件已存在，先删除，避免写入冲突
        if (file.exists()) {
            file.delete();
        }
        // 确保父目录存在
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        Download.create(url, file).start(this);
    }

    // ========== Download.Callback 实现 ==========

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
        Log.e(TAG, "下载错误: " + msg + ", retryCount=" + retryCount);

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
            Log.e(TAG, "所有代理尝试失败，停止重试");
            mainHandler.post(() -> {
                // 关闭进度条
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                // 显示提示
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, "下载失败，所有代理均不可用", Toast.LENGTH_LONG).show();
                }
                // 重置重试计数，允许后续再次尝试
                retryCount = 0;
            });
        }
    }

    @Override
    public void success(File file) {
        if (isInstallTriggered) return;
        isInstallTriggered = true;

        Log.i(TAG, "下载成功: " + file.getAbsolutePath());
        mainHandler.post(() -> {
            if (progressDialog != null) progressDialog.dismiss();
            installApk(file);
        });
    }

    // ========== 安装逻辑 ==========

    /**
     * 安装 APK（针对不同 Android 版本采用不同方案）
     */
    private void installApk(File file) {
        try {
            // 确保文件可读
            file.setReadable(true, false);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // Android 5.0+
                // 使用 FileProvider 生成 content URI
                uri = FileProvider.getUriForFile(activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // Android 4.4 及以下使用 file URI，并尝试设置权限
                uri = Uri.fromFile(file);
                // 显式授予读取权限（对于 file URI 实际上不需要，但保留以增强兼容性）
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            intent.setDataAndType(uri, "application/vnd.android.package-archive");

            // 检查是否有 Activity 能处理该 Intent
            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                Log.e(TAG, "无 Activity 处理安装 Intent，尝试备用方案");
                fallbackInstall(file);
                return;
            }

            activity.startActivity(intent);

            // 可选：关闭当前 Activity 避免用户返回看到下载界面
            // activity.finish();

        } catch (Exception e) {
            Log.e(TAG, "安装失败: " + e.getMessage(), e);
            fallbackInstall(file);
        }
    }

    /**
     * 备用安装方案：适用于 Android 4.4 及更低版本的特殊处理
     */
    private void fallbackInstall(File file) {
        try {
            // 确保文件全局可读
            file.setReadable(true, false);

            // 尝试将文件复制到公共目录（如 Downloads），以增加安装成功率
            File publicFile = copyToPublicDir(file);
            if (publicFile != null) {
                file = publicFile;
                file.setReadable(true, false);
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 再次检查 Intent 是否可处理
            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                showToast("系统无法安装 APK，请前往设置开启“未知来源”后手动安装");
                isInstallTriggered = false; // 允许重试
                return;
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "备用安装也失败: " + e.getMessage());
            showToast("安装失败，请手动安装");
            isInstallTriggered = false; // 允许重试
        }
    }

    /**
     * 将文件复制到公共下载目录（仅当需要时使用）
     */
    private File copyToPublicDir(File sourceFile) {
        try {
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            File targetFile = new File(downloadDir, "update.apk");

            // 复制文件
            try (FileInputStream inStream = new FileInputStream(sourceFile);
                 FileOutputStream outStream = new FileOutputStream(targetFile);
                 FileChannel inChannel = inStream.getChannel();
                 FileChannel outChannel = outStream.getChannel()) {
                inChannel.transferTo(0, inChannel.size(), outChannel);
            }
            return targetFile;
        } catch (IOException e) {
            Log.e(TAG, "复制文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 显示 Toast（主线程安全）
     */
    private void showToast(String msg) {
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }
}