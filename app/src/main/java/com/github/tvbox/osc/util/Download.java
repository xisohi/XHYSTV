package com.github.tvbox.osc.util;

import android.os.Handler;
import android.os.Looper;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;

import java.io.File;

/**
 * 文件下载工具类
 */
public class Download {
    private String url;
    private File file;
    private Callback callback;

    public interface Callback {
        void progress(int progress);
        void error(String msg);
        void success(File file);
    }

    public static Download create(String url, File file) {
        return new Download(url, file);
    }

    private Download(String url, File file) {
        this.url = url;
        this.file = file;
    }

    public Download setUrl(String url) {
        this.url = url;
        return this;
    }

    public void start(Callback callback) {
        this.callback = callback;

        OkGo.<File>get(url)
                .execute(new FileCallback(file.getParent(), file.getName()) {
                    @Override
                    public void onSuccess(Response<File> response) {
                        post(() -> callback.success(response.body()));
                    }

                    @Override
                    public void onError(Response<File> response) {
                        String msg = response.getException() != null ?
                                response.getException().getMessage() : "下载失败";
                        post(() -> callback.error(msg));
                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        int percent = (int) (progress.fraction * 100);
                        post(() -> callback.progress(percent));
                    }
                });
    }

    private void post(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
}