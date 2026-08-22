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
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * TVBox 应用更新管理器【最终完整版】
 * 修复清单：
 * 1. 修复三处 U+2011 特殊连字符bug(User‑Agent / Content‑Range / package‑archive)
 * 2. Activity销毁判断 isFinishing + isDestroyed
 * 3. 禁止回退getCacheDir私有缓存，优先externalCache，失败落到Download公共目录
 * 4. 修复取消下载错误interrupt，使用isCancelled标记
 * 5. 区分静默/手动更新：静默删除残留apk禁用断点续传；手动保留断点续传
 * 6. OkGo未初始化时清理残缺APK并提示
 * 7. 下载完成APK大小基础校验(>1MB)
 * 8. Github.runSpeedTestIfNeed()复用24h测速缓存，不强制测速
 * 9. ✨无感代理切换：重试不复建ProgressDialog，不弹Toast，仅文字提示，保留已下载字节
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
    private volatile boolean isCancelled = false;
    private long lastSpeedUpdateTime = 0;
    private long lastSpeedUpdateBytes = 0;

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
        notifyUser("正在检查更新...");
        new Thread(this::checkUpdate).start();
    }

    /**
     * 统一判断Activity是否有效：同时判断 isFinishing + isDestroyed
     */
    private boolean isActivityAlive() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private String getJsonUrl() {
        return Github.getJson("XHYSTV");
    }

    private String getApkName() {
        String flavor = BuildConfig.FLAVOR;
        if (flavor == null || flavor.isEmpty()) {
            flavor = "java";
        }
        return "XHYSTV-" + flavor;
    }

    private void notifyUser(String msg) {
        if (!silentMode && isActivityAlive()) {
            showToast(msg);
        }
    }

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

    private void showUpdateDialog(String version, String desc) {
        if (!isActivityAlive()) return;
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
            new Thread(() -> {
                try {
                    long now = System.currentTimeMillis();
                    // 复用24小时测速缓存，不再强制清空全部代理测速
                    if (now - Github.getLastSpeedTestTime() > Github.getSpeedTestInterval()) {
                        Github.runSpeedTestIfNeed();
                    }
                    Log.i(TAG, "测速完成，开始下载");
                } catch (Exception e) {
                    Log.e(TAG, "测速失败: " + e.getMessage());
                }
                mainHandler.post(() -> {
                    // 测速结束回到主线程，先校验Activity存活状态
                    if (!isActivityAlive()) {
                        showToast("页面已失效，请重新点击更新");
                        return;
                    }
                    isInstallTriggered = false;
                    retryCount = 0;
                    doDownload();
                });
            }).start();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.requestFocus();
    }

    private void doDownload() {
        isCancelled = false;
        String apkName = getApkName();
        int proxyCount = Github.getProxyCount();
        if (retryCount >= proxyCount) {
            Log.w(TAG, "所有 " + proxyCount + " 个代理均已尝试，停止下载");
            retryCount = 0;
            mainHandler.post(() -> {
                dismissProgressDialog();
                if (isActivityAlive()) showToast("所有下载线路均失败，请检查网络后重试");
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
                if (isActivityAlive()) showToast("所有代理均不可用，下载失败");
            });
            return;
        }
        Log.i(TAG, "下载: " + url);

        File cacheDir = getAvailableCacheDir();
        final File file = new File(cacheDir, "update.apk");
        long downloadedSize = 0;
        // 静默自动更新(冷启动弹窗):删除旧残留apk，禁用断点续传；手动force更新保留断点续传
        if (!silentMode && file.exists()) {
            downloadedSize = file.length();
            Log.i(TAG, "手动更新，发现已下载 " + downloadedSize + " 字节，继续下载");
        } else {
            if (file.exists()) {
                Log.w(TAG, "静默自动更新，删除本地残留旧apk，禁用断点续传");
                file.delete();
            }
        }

        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        final long initialDownloadedSize = downloadedSize;

        mainHandler.post(() -> {
            // 只首次下载创建ProgressDialog；代理重试不复建弹窗，实现无感切换
            if (retryCount == 1) {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                progressDialog = new ProgressDialog(activity);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progressDialog.setTitle("正在下载");
                progressDialog.setMax(100);
                progressDialog.setCancelable(true);
                progressDialog.setMessage("准备下载...");
                progressDialog.setOnCancelListener(dialogInterface -> {
                    isCancelled = true;
                    if (file.exists()) {
                        file.delete();
                    }
                    if (isActivityAlive()) showToast("已取消下载");
                    retryCount = 0;
                    dismissProgressDialog();
                });
                if (isActivityAlive()) {
                    progressDialog.show();
                }
            } else {
                // 代理重试，仅修改提示文字，对话框保持不销毁
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.setMessage("切换代理中…");
                }
            }
        });

        new Thread(() -> {
            okhttp3.Response response = null;
            java.io.InputStream inputStream = null;
            RandomAccessFile randomAccessFile = null;
            try {
                okhttp3.OkHttpClient client = OkGoHelper.getDefaultClient();
                if (client == null) {
                    // OkGo未初始化，清理旧残缺apk
                    if (file.exists()) file.delete();
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        if (isActivityAlive()) showToast("网络组件尚未初始化完成，请稍后重试更新");
                    });
                    return;
                }

                okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                if (initialDownloadedSize > 0) {
                    requestBuilder.addHeader("Range", "bytes=" + initialDownloadedSize + "-");
                    Log.d(TAG, "请求从 " + initialDownloadedSize + " 字节继续");
                }
                okhttp3.Request request = requestBuilder.build();
                response = client.newCall(request).execute();
                int code = response.code();
                boolean isPartial = (code == 206);
                if (code != 200 && code != 206) {
                    Log.e(TAG, "下载失败http code: " + code);
                    mainHandler.post(() -> {
                        if (progressDialog != null && progressDialog.isShowing()) {
                            progressDialog.setMessage("切换代理中…");
                        }
                    });
                    // 无感切换：不删除文件、不弹Toast，延时自动下一个代理
                    mainHandler.postDelayed(() -> doDownload(), 800);
                    return;
                }

                long contentLength = response.body().contentLength();
                long totalSize = contentLength;
                // 修复 Content‑Range 特殊连字符
                String contentRange = response.header("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    try {
                        String totalStr = contentRange.substring(contentRange.lastIndexOf('/') + 1);
                        totalSize = Long.parseLong(totalStr);
                    } catch (Exception ignored) {
                    }
                }
                if (totalSize <= 0 && contentLength > 0) {
                    totalSize = contentLength;
                }

                long freeSpace = cacheDir.getFreeSpace();
                if (freeSpace < totalSize * 2) {
                    final long needSize = totalSize * 2 / (1024 * 1024);
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        if (isActivityAlive()) showToast("存储空间不足，需要 " + needSize + " MB");
                    });
                    if (file.exists()) file.delete();
                    return;
                }

                final long finalTotalSize = totalSize;
                randomAccessFile = new RandomAccessFile(file, "rw");
                long currentDownloaded = (initialDownloadedSize > 0 && isPartial) ? initialDownloadedSize : 0;
                if (currentDownloaded > 0) {
                    randomAccessFile.seek(currentDownloaded);
                } else {
                    randomAccessFile.setLength(0);
                }
                inputStream = response.body().byteStream();
                byte[] buffer = new byte[32768];
                long totalRead = currentDownloaded;
                int len;
                int lastPercent = -1;
                while ((len = inputStream.read(buffer)) != -1) {
                    if (isCancelled) {
                        Log.d(TAG, "下载已取消");
                        break;
                    }
                    randomAccessFile.write(buffer, 0, len);
                    totalRead += len;
                    if (finalTotalSize > 0) {
                        int percent = (int) (totalRead * 100 / finalTotalSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            long now = System.currentTimeMillis();
                            if (now - lastSpeedUpdateTime >= 1000) {
                                long speedKB = (totalRead - lastSpeedUpdateBytes) / 1024;
                                long remainingSec = (finalTotalSize - totalRead) / (Math.max(speedKB, 1) * 1024);
                                String timeStr = formatTime(remainingSec);
                                final long currentTotalRead = totalRead;
                                final int finalPercent = percent;
                                final String msg = String.format("速度: %s  剩余: %s  进度： %.1f/%.1f MB",
                                        formatSpeed(speedKB), timeStr,
                                        currentTotalRead / (1024.0 * 1024.0),
                                        finalTotalSize / (1024.0 * 1024.0));
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

                if (isCancelled) {
                    if (file.exists()) {
                        file.delete();
                        Log.d(TAG, "已删除取消下载的文件");
                    }
                    return;
                }

                // 下载完成基础校验，apk最小1MB
                if (!file.exists() || file.length() < 1024 * 1024) {
                    Log.e(TAG, "下载得到APK文件异常，大小=" + (file.exists() ? file.length() : 0));
                    if (file.exists()) file.delete();
                    mainHandler.post(() -> {
                        dismissProgressDialog();
                        if (isActivityAlive()) showToast("更新包文件异常，请重试");
                    });
                    return;
                }

                retryCount = 0;
                mainHandler.post(() -> {
                    dismissProgressDialog();
                    installApk(file);
                });

            } catch (Exception e) {
                if (isCancelled) {
                    Log.d(TAG, "用户取消下载");
                    return;
                }
                Log.e(TAG, "下载异常: " + e.getMessage());
                mainHandler.post(() -> {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.setMessage("切换代理中…");
                    }
                });
                // 无感重试：不删除已下载内容，不弹toast，延时切下一个代理
                mainHandler.postDelayed(() -> doDownload(), 800);
                return;
            } finally {
                try {
                    if (randomAccessFile != null) randomAccessFile.close();
                } catch (IOException ignored) {
                }
                try {
                    if (inputStream != null) inputStream.close();
                } catch (IOException ignored) {
                }
                try {
                    if (response != null) response.close();
                } catch (Exception ignored) {
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

    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            return (seconds / 60) + "分" + (seconds % 60) + "秒";
        } else {
            return (seconds / 3600) + "时" + ((seconds % 3600) / 60) + "分";
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 关键：绝不回退getCacheDir()内部私有缓存，Android4.4‑5盒子安装器读不到
     * 优先外部缓存，失败直接落到公共Download目录
     */
    private File getAvailableCacheDir() {
        if (hasStoragePermission()) {
            File externalCache = activity.getExternalCacheDir();
            if (externalCache != null && externalCache.canWrite()) {
                Log.d(TAG, "使用外部缓存目录: " + externalCache.getPath());
                return externalCache;
            }
        }
        Log.w(TAG, "外部缓存不可用，回退公共Download目录，不使用内部私有cache");
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    private void installApk(File file) {
        // 先校验Activity存活状态
        if (!isActivityAlive()) {
            showToast("页面已销毁，请手动重新更新");
            return;
        }
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
            mainHandler.postDelayed(() -> {
                if (file.exists() && file.delete()) {
                    Log.d(TAG, "APK 已删除，释放空间");
                }
            }, 8000);
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
                try {outStream.close();} catch (IOException e) {}
            }
            if (inStream != null) {
                try {inStream.close();} catch (IOException e) {}
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
                try {outChannel.close();} catch (IOException e) {}
            }
            if (inChannel != null) {
                try {inChannel.close();} catch (IOException e) {}
            }
            if (outStream != null) {
                try {outStream.close();} catch (IOException e) {}
            }
            if (inStream != null) {
                try {inStream.close();} catch (IOException e) {}
            }
        }
    }

    private void dismissProgressDialog() {
        if (isActivityAlive()
                && progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showToast(String msg) {
        if (isActivityAlive()) {
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