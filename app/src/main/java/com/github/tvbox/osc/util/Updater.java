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
 * TVBox 应用更新管理器（优化版）
 * 改进：
 * 1. 下载前自动选择最快代理（利用 Github 类的测速功能）
 * 2. 启动时不测速，节省资源
 * 3. 24小时缓存测速结果
 * 4. 保留原有重试机制作为降级方案
 */
public class Updater implements Download.Callback {
    private static final String TAG = "Updater";
    private static final int MAX_RETRY_COUNT = 4;
    private static final int PRE_CHECK_TIMEOUT = 5000; // 预检超时 5 秒

    private Activity activity;
    private Handler mainHandler;
    private AlertDialog dialog;
    private ProgressDialog progressDialog;
    private int retryCount = 0;
    private boolean forceCheck = false;
    private boolean silentMode = false;
    private String apkName;
    private boolean isInstallTriggered = false;
    private boolean isSpeedTested = false; // 标记是否已完成测速

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
     * 优化：在获取 URL 前确保测速已完成（最多等待 5 秒）
     */
    private String getApkUrl() {
        apkName = "XHYSTV-" + BuildConfig.FLAVOR;

        // 如果还未测速，等待测速完成（最多等待 PRE_CHECK_TIMEOUT）
        if (!isSpeedTested) {
            Log.d(TAG, "等待测速完成...");
            long startTime = System.currentTimeMillis();
            while (!isSpeedTested && (System.currentTimeMillis() - startTime) < PRE_CHECK_TIMEOUT) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!isSpeedTested) {
                Log.w(TAG, "测速超时，使用默认代理");
            } else {
                Log.i(TAG, "测速完成，使用最快代理下载");
            }
        }

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
        TextView tvFlavor = view.findViewById(R.id.flavorType);
        TextView btnConfirm = view.findViewById(R.id.confirm);
        TextView btnCancel = view.findViewById(R.id.cancel);

        tvVersion.setText(activity.getString(R.string.update_version, version));
        String flavor = BuildConfig.FLAVOR;
        String flavorDisplay = getFlavorDisplayName(flavor);
        tvFlavor.setText(flavorDisplay);
        tvDesc.setText(desc);

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

            // 显示提示，告知用户正在选择最优线路
            showToast("正在选择最优下载线路...");

            // 在后台线程测速
            new Thread(() -> {
                try {
                    // 执行测速（同步，但不会阻塞 UI）
                    Github.forceSpeedTest();
                    isSpeedTested = true;
                    Log.i(TAG, "测速完成，开始下载");

                    // 切回主线程执行实际下载
                    mainHandler.post(() -> {
                        // 重置安装触发标记
                        isInstallTriggered = false;
                        // 执行实际下载
                        doDownload();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "测速失败: " + e.getMessage());
                    // 测速失败也继续下载（使用默认代理）
                    mainHandler.post(() -> {
                        isInstallTriggered = false;
                        doDownload();
                    });
                }
            }).start();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.requestFocus();
    }

    /**
     * 实际执行下载
     */
    /**
     * 实际执行下载
     */
    private void doDownload() {
        // 重置安装触发标记，允许重新安装
        isInstallTriggered = false;

        String url = getApkUrl();
        Log.i(TAG, "下载: " + url);

        mainHandler.post(() -> {
            if (dialog != null) dialog.dismiss();
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

        File cacheDir = getAvailableCacheDir();
        File file = new File(cacheDir, "update.apk");
        if (file.exists()) {
            file.delete();
        }
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        Download.create(url, file).start(this);
    }

    // ========== 权限检查和路径选择方法 ==========

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

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
            // 切换到下一个代理
            Github.switchToNextProxy();
            // 标记测速已失效，下次下载会重新测速
            isSpeedTested = false;

            mainHandler.post(() -> {
                if (progressDialog != null) {
                    progressDialog.setMessage("切换代理重试 " + retryCount + "/" + MAX_RETRY_COUNT);
                }
                // 延迟重试，让网络稳定
                mainHandler.postDelayed(() -> {
                    // 重新触发测速（异步）
                    new Thread(() -> {
                        try {
                            Github.forceSpeedTest();
                            isSpeedTested = true;
                            Log.i(TAG, "重试前测速完成，使用代理: " + Github.getProxyStatus());
                            // 开始下载
                            mainHandler.post(() -> {
                                isInstallTriggered = false;
                                doDownload();
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "重试测速失败: " + e.getMessage());
                            // 测速失败也继续下载
                            mainHandler.post(() -> {
                                isInstallTriggered = false;
                                doDownload();
                            });
                        }
                    }).start();
                }, 1500);
            });
        } else {
            Log.e(TAG, "所有代理尝试失败，停止重试");
            mainHandler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, "下载失败，所有代理均不可用", Toast.LENGTH_LONG).show();
                }
                retryCount = 0;
                isSpeedTested = false;
            });
        }
    }

    @Override
    public void success(File file) {
        if (isInstallTriggered) return;
        isInstallTriggered = true;

        Log.i(TAG, "下载成功: " + file.getAbsolutePath());
        // 下载成功，重置重试计数
        retryCount = 0;
        mainHandler.post(() -> {
            if (progressDialog != null) progressDialog.dismiss();
            installApk(file);
        });
    }

    // ========== 安装逻辑 ==========

    private void installApk(File file) {
        try {
            file.setReadable(true, false);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                uri = FileProvider.getUriForFile(activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider", file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }

            intent.setDataAndType(uri, "application/vnd.android.package-archive");

            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                Log.e(TAG, "无 Activity 处理安装 Intent，尝试备用方案");
                fallbackInstall(file);
                return;
            }

            activity.startActivity(intent);

        } catch (Exception e) {
            Log.e(TAG, "安装失败: " + e.getMessage(), e);
            fallbackInstall(file);
        }
    }

    private void fallbackInstall(File file) {
        try {
            file.setReadable(true, false);
            File publicFile = copyToPublicDir(file);
            if (publicFile != null) {
                file = publicFile;
                file.setReadable(true, false);
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                showToast("系统无法安装 APK，请前往设置开启\"未知来源\"后手动安装");
                isInstallTriggered = false;
                return;
            }

            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "备用安装也失败: " + e.getMessage());
            showToast("安装失败，请手动安装");
            isInstallTriggered = false;
        }
    }

    private File copyToPublicDir(File sourceFile) {
        try {
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            File targetFile = new File(downloadDir, "update.apk");

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

    private void showToast(String msg) {
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }

    private String getFlavorDisplayName(String flavor) {
        switch (flavor) {
            case "java":
                return "Java通用版";
            case "java32":
                return "Java 32位版";
            case "java64":
                return "Java 64位版";
            case "python":
                return "Python通用版";
            case "python32":
                return "Python 32位版";
            case "python64":
                return "Python 64位版";
            default:
                return flavor;
        }
    }
}