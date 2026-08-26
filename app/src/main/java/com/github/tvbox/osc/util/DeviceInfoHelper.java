package com.github.tvbox.osc.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

public class DeviceInfoHelper {
    private static final String TAG = "DeviceInfoHelper";

    /**
     * 获取 CPU 架构（兼容所有 Android 版本）
     */
    public static String getCpuArchitecture() {
        String arch = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                    arch = Build.SUPPORTED_ABIS[0];
                }
            }
            if (arch == null || arch.isEmpty()) {
                arch = Build.CPU_ABI;
            }
            if (arch == null || arch.isEmpty()) {
                arch = readCpuInfoFromProc();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取 CPU 架构失败: " + e.getMessage());
        }
        Log.d(TAG, "CPU 架构: " + arch);
        return arch != null ? arch : "unknown";
    }

    private static String readCpuInfoFromProc() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new InputStreamReader(new FileInputStream("/proc/cpuinfo")));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("processor") || lower.contains("model name")) {
                    if (lower.contains("arm64") || lower.contains("aarch64")) {
                        reader.close();
                        return "arm64-v8a";
                    } else if (lower.contains("armv7")) {
                        reader.close();
                        return "armeabi-v7a";
                    } else if (lower.contains("arm")) {
                        reader.close();
                        return "armeabi";
                    } else if (lower.contains("x86_64") || lower.contains("amd64")) {
                        reader.close();
                        return "x86_64";
                    } else if (lower.contains("x86") || lower.contains("i686")) {
                        reader.close();
                        return "x86";
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "读取 cpuinfo 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 检测设备是否支持 Python 版
     */
    public static boolean isPythonSupported(Context context) {
        String arch = getCpuArchitecture();
        if (arch == null) return false;

        String lower = arch.toLowerCase();
        boolean archSupported =
                // ARM 架构
                lower.contains("arm64") || lower.contains("aarch64") ||
                        lower.contains("armv8") || lower.contains("armv7") ||
                        lower.contains("armeabi") || lower.contains("arm") ||
                        // x86 架构（也支持）
                        lower.contains("x86") || lower.contains("i686") ||
                        lower.contains("x86_64") || lower.contains("amd64");

        if (!archSupported) {
            Log.d(TAG, "架构不支持 Python: " + arch);
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.d(TAG, "Android 版本过低: " + Build.VERSION.SDK_INT);
            return false;
        }

        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            long usableMB = filesDir.getUsableSpace() / (1024 * 1024);
            if (usableMB < 200) {
                Log.d(TAG, "存储空间不足: " + usableMB + "MB");
                return false;
            }
        }

        Log.d(TAG, "✅ 支持 Python (架构: " + arch + ")");
        return true;
    }

    /**
     * 根据 CPU 架构推荐 Python 版本
     */
    private static String getRecommendedPythonFlavor(String arch) {
        if (arch == null) return "python";
        String lower = arch.toLowerCase();

        // 64 位 ARM → python64
        if (lower.contains("arm64") || lower.contains("aarch64") || lower.contains("armv8")) {
            return "python64";
        }
        // 32 位 ARM → python32
        if (lower.contains("armv7") || lower.contains("armeabi") || lower.contains("arm")) {
            return "python32";
        }
        // x86 / 未知 → python 通用版
        return "python";
    }

    /**
     * 获取推荐的 Flavor
     */
    public static String getRecommendedFlavor(Context context) {
        String current = BuildConfig.FLAVOR;

        // 已经是 Python 版，保持不变
        if (current != null && current.startsWith("python")) {
            Log.d(TAG, "当前已是 Python 版: " + current);
            return current;
        }

        // 检测是否支持 Python
        if (!isPythonSupported(context)) {
            Log.d(TAG, "设备不支持 Python，保持: " + current);
            return current != null ? current : "java";
        }

        String arch = getCpuArchitecture();
        String recommended = getRecommendedPythonFlavor(arch);
        Log.i(TAG, "✅ 推荐切换到: " + recommended + " (架构: " + arch + ")");
        return recommended;
    }

    /**
     * 获取 Flavor 显示名称
     */
    public static String getFlavorDisplayName(String flavor) {
        if (flavor == null) return "未知";
        switch (flavor) {
            case "java": return "Java 通用版";
            case "java32": return "Java 32位版";
            case "java64": return "Java 64位版";
            case "python": return "Python 通用版";
            case "python32": return "Python 32位版";
            case "python64": return "Python 64位版";
            default: return flavor;
        }
    }
}