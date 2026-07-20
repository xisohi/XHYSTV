package com.github.tvbox.osc.util;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

/**
 * TVBox 应用更新管理器
 * 特性：
 * 1. 下载前自动选择最快代理（利用 Github 类的测速功能）
 * 2. 启动时不测速，节省资源
 * 3. 24小时缓存测速结果
 * 4. 代理失败自动按速度优先级切换下一个代理
 * 5. 兼容 Android 4.4 - Android 15
 */
public class Updater {
    private static final String TAG = "Updater";

    private Activity activity;
    private Handler mainHandler;
    private AlertDialog dialog;
    private ProgressDialog progressDialog;
    private int retryCount = 0;
    private boolean forceCheck = false;
    private boolean silentMode = false;
    private boolean isInstallTriggered = false;

    public static Updater create() {
        return new Updater();
    }

    private Updater() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private long lastSpeedUpdateTime = 0;
    private long lastSpeedUpdateBytes = 0;

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
        notifyUser("正在检查更新...");
        new Thread(this::checkUpdate).start();
    }

    /**
     * 获取 JSON 配置地址（无需代理）
     */
    private String getJsonUrl() {
        return Github.getJson("XHYSTV");
    }

    /**
     * 获取 APK 名称（根据当前 flavor）
     */
    private String getApkName() {
        String flavor = BuildConfig.FLAVOR;
        if (flavor == null || flavor.isEmpty()) {
            flavor = "java";
        }
        return "XHYSTV-" + flavor;
    }

    /**
     * 用户通知（仅在手动检查且非静默模式下显示）
     */
    private void notifyUser(String msg) {
        if (forceCheck && !silentMode) {
            showToast(msg);
        }
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
                notifyUser("当前已是最新版本");
            }
        } catch (Exception e) {
            Log.e(TAG, "检查失败: " + e.getMessage());
            notifyUser("检查更新失败: " + e.getMessage());
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
        tvFlavor.setText(getFlavorDisplayName(BuildConfig.FLAVOR));
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
            showToast("正在选择最优下载线路...");

            // 后台测速，完成后在主线程下载
            new Thread(() -> {
                try {
                    Github.forceSpeedTest();
                    Log.i(TAG, "测速完成，开始下载");
                } catch (Exception e) {
                    Log.e(TAG, "测速失败: " + e.getMessage());
                }
                mainHandler.post(() -> {
                    isInstallTriggered = false;
                    retryCount = 0;
                    doDownload();
                });
            }).start();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.requestFocus();
    }

    /**
     * 实际执行下载
     * 使用 retryCount 作为代理索引，自动按速度优先级切换代理
     */
    private void doDownload() {
        String apkName = getApkName();

        // 检查重试次数
        int proxyCount = Github.getProxyCount();
        if (retryCount >= proxyCount) {
            Log.w(TAG, "所有 " + proxyCount + " 个代理均已尝试，停止下载");
            retryCount = 0;
            mainHandler.post(() -> {
                dismissProgressDialog();
                showToast("所有下载线路均失败，请检查网络后重试");
            });
            return;
        }

        Log.d(TAG, "开始第 " + (retryCount + 1) + "/" + proxyCount + " 次下载尝试");

        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + apkName + ".apk";
        String url = Github.getProxyUrlByIndex(githubUrl, retryCount);
        retryCount++;

        if (url == null) {
            Log.e(TAG, "无可用代理");
            mainHandler.post(() -> {
                dismissProgressDialog();
                showToast("所有代理均不可用，下载失败");
            });
            return;
        }

        Log.i(TAG, "下载: " + url);

        mainHandler.post(() -> {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
            dismissProgressDialog();
            progressDialog = new ProgressDialog(activity);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setTitle("正在下载");
            progressDialog.setMax(100);
            progressDialog.setCancelable(false);
            progressDialog.setMessage("准备下载...");
            if (activity != null && !activity.isFinishing()) {
                progressDialog.show();
            }
        });

        File cacheDir = getAvailableCacheDir();
        final File file = new File(cacheDir, "update.apk");
        if (file.exists()) {
            file.delete();
        }
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        new Thread(() -> {
            okhttp3.Response response = null;
            java.io.InputStream inputStream = null;
            java.io.FileOutputStream outputStream = null;
            try {
                okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                if (client == null) {
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        showToast("OkHttpClient 未初始化");
                    });
                    return;
                }

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build();

                response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    Log.e(TAG, "下载失败: " + response.code());
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        showToast("下载失败，切换代理重试...");
                        mainHandler.postDelayed(() -> doDownload(), 1500);
                    });
                    return;
                }

                long contentLength = response.body().contentLength();
                long freeSpace = cacheDir.getFreeSpace();
                if (freeSpace < contentLength * 2) {
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        showToast("存储空间不足，需要 " + (contentLength * 2 / (1024 * 1024)) + " MB");
                    });
                    return;
                }
                inputStream = response.body().byteStream();
                outputStream = new java.io.FileOutputStream(file);
                byte[] buffer = new byte[32768];
                long totalRead = 0;
                int len;
                int lastPercent = -1;

                while ((len = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                    totalRead += len;

                    if (contentLength > 0) {
                        int percent = (int) (totalRead * 100 / contentLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;

                            long now = System.currentTimeMillis();
                            if (now - lastSpeedUpdateTime >= 1000) {
                                // 计算速度 KB/s
                                long speedKB = (totalRead - lastSpeedUpdateBytes) / 1024;
                                // 计算剩余时间（秒）
                                long remainingSec = (contentLength - totalRead) / (Math.max(speedKB, 1) * 1024);
                                String timeStr = formatTime(remainingSec);

                                final int finalPercent = percent;
                                final String msg = String.format("速度: %s  剩余: %s  进度： %.1f/%.1f MB",
                                        formatSpeed(speedKB), timeStr,
                                        totalRead / (1024.0 * 1024.0),
                                        contentLength / (1024.0 * 1024.0));

                                mainHandler.post(() -> {
                                    if (progressDialog != null && progressDialog.isShowing()) {
                                        progressDialog.setProgress(finalPercent);
                                        progressDialog.setMessage(msg);
                                    }
                                });

                                lastSpeedUpdateTime = now;
                                lastSpeedUpdateBytes = totalRead;
                            }
                        }
                    }
                }
                outputStream.flush();

                // 下载成功，重置重试计数
                retryCount = 0;
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    installApk(file);
                });

            } catch (Exception e) {
                Log.e(TAG, "下载异常: " + e.getMessage());
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    showToast("下载异常，切换代理重试...");
                    mainHandler.postDelayed(() -> doDownload(), 1500);
                });
            } finally {
                if (outputStream != null) {
                    try { outputStream.close(); } catch (IOException e) { /* ignore */ }
                }
                if (inputStream != null) {
                    try { inputStream.close(); } catch (IOException e) { /* ignore */ }
                }
                if (response != null) {
                    response.close();
                }
            }
        }).start();
    }

    private String formatSpeed(long speedKB) {
        if (speedKB < 1024) {
            return speedKB + " KB/s";
        } else {
            return String.format("%.1f MB/s", speedKB / 1024.0);
        }
    }
    // 格式化时间
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        } else {
            return (seconds / 3600) + "时" + ((seconds % 3600) / 60) + "分";
        }
    }

    // ========== 权限检查和路径选择 ==========

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private File getAvailableCacheDir() {
        if (hasStoragePermission()) {
            File externalCache = activity.getExternalCacheDir();
            if (externalCache != null && externalCache.canWrite()) {
                Log.d(TAG, "使用外部缓存目录: " + externalCache.getPath());
                return externalCache;
            }
        }
        File internalCache = activity.getCacheDir();
        Log.d(TAG, "使用内部缓存目录: " + internalCache.getPath());
        return internalCache;
    }

    // ========== 安装逻辑 ==========

    private void installApk(File file) {
        if (isInstallTriggered) return;
        isInstallTriggered = true;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return copyToPublicDirMediaStore(sourceFile);
        }
        return copyToPublicDirLegacy(sourceFile);
    }

    private File copyToPublicDirMediaStore(File sourceFile) {
        FileInputStream inStream = null;
        OutputStream outStream = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "update.apk");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
            values.put("relative_path", Environment.DIRECTORY_DOWNLOADS);

            Uri uri = activity.getContentResolver().insert(
                    Uri.parse("content://media/external/downloads"), values);
            if (uri == null) return null;

            outStream = activity.getContentResolver().openOutputStream(uri);
            inStream = new FileInputStream(sourceFile);
            byte[] buffer = new byte[32768];
            int len;
            while ((len = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, len);
            }
            outStream.flush();

            return new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "update.apk");
        } catch (Exception e) {
            Log.e(TAG, "MediaStore 复制失败: " + e.getMessage());
            return null;
        } finally {
            if (outStream != null) {
                try { outStream.close(); } catch (IOException e) { }
            }
            if (inStream != null) {
                try { inStream.close(); } catch (IOException e) { }
            }
        }
    }

    private File copyToPublicDirLegacy(File sourceFile) {
        FileInputStream inStream = null;
        FileOutputStream outStream = null;
        FileChannel inChannel = null;
        FileChannel outChannel = null;
        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            File targetFile = new File(downloadDir, "update.apk");

            inStream = new FileInputStream(sourceFile);
            outStream = new FileOutputStream(targetFile);
            inChannel = inStream.getChannel();
            outChannel = outStream.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);
            return targetFile;
        } catch (IOException e) {
            Log.e(TAG, "复制文件失败: " + e.getMessage());
            return null;
        } finally {
            if (outChannel != null) {
                try { outChannel.close(); } catch (IOException e) { }
            }
            if (inChannel != null) {
                try { inChannel.close(); } catch (IOException e) { }
            }
            if (outStream != null) {
                try { outStream.close(); } catch (IOException e) { }
            }
            if (inStream != null) {
                try { inStream.close(); } catch (IOException e) { }
            }
        }
    }

    // ========== 工具方法 ==========

    private void dismissProgressDialog() {
        if (activity != null && !activity.isFinishing()
                && progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showToast(String msg) {
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }

    private String getFlavorDisplayName(String flavor) {
        if (flavor == null) return "未知版本";
        switch (flavor) {
            case "java": return "Java通用版";
            case "java32": return "Java 32位版";
            case "java64": return "Java 64位版";
            case "python": return "Python通用版";
            case "python32": return "Python 32位版";
            case "python64": return "Python 64位版";
            default: return flavor;
        }
    }
}