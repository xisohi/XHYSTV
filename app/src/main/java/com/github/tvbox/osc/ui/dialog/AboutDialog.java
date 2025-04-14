package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {
    private TextView appVersion;
    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        // 初始化TextView
        appVersion = findViewById(R.id.app_version);

        // 获取版本号并设置到TextView
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), 0);
            // 获取产品风味名称
            String flavorName = BuildConfig.FLAVOR;
            appVersion.setText(flavorName + " Version " + packageInfo.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            appVersion.setText("Version: Unknown");
        }
    }
}