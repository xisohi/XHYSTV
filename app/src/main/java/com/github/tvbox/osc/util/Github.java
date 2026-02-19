package com.github.tvbox.osc.util;
import android.util.Log;

import java.util.Random;

/**
 * GitHub 加速工具类
 * 功能特性：
 * 1. 多代理源支持（5个主流加速服务）
 * 2. 自动切换机制（配合下载器重试）
 * 3. 负载均衡策略（顺序轮询）
 * 4. 调试日志输出
 *
 * 建议每3-6个月检查代理服务可用性并更新列表
 */
public class Github {
    private static final String TAG = "Github";
    /**
     * 代理加速源列表（按推荐优先级排序）
     */
    private static final String[] PROXY_URLS = {
            "https://mirror.ghproxy.com/",
            "https://ghfast.top/",              // 稳定（备用）
            "https://ghproxy.net/",             // 国内速度快，首选
            "https://github.catvod.com/",       // 猫影视git文件加速
            "https://gh.xisohi.dpdns.org/"      // 个人维护（备用）
    };
    // 当前使用的代理索引（volatile确保多线程可见性）
    private static volatile int currentProxyIndex = 0;

    // 随机数生成器（预留用于随机选择策略）
    private static final Random random = new Random();

    /**
     * JSON 配置文件地址（自定义服务器 - 无需代理）
     * 示例: https://xhys.lcjly.cn/update/fongmi.json
     *
     * @param name 配置文件名（不含扩展名）
     * @return 完整的JSON URL
     */
    public static String getJson(String name) {
        // 自定义服务器（推荐，更稳定，无需代理）
        return "https://xhys.lcjly.cn/update/" + name + ".json";
    }
    public static String getApk(String name) {
        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + name + ".apk";
        return getAcceleratedUrl(githubUrl);
    }

    /**
     * 核心方法：对GitHub URL进行代理加速
     * 当前策略：顺序轮询（适合配合失败重试机制）
     *
     * @param githubUrl 原始GitHub URL
     * @return 加速后的URL
     */
    private static String getAcceleratedUrl(String githubUrl) {
        return PROXY_URLS[currentProxyIndex] + githubUrl;
    }

    /**
     * 当下载失败时调用此方法切换到下一个代理
     * 建议在下载失败的 catch 块中调用
     */
    public static synchronized void switchToNextProxy() {
        currentProxyIndex = (currentProxyIndex + 1) % PROXY_URLS.length;
        Log.w(TAG, "下载失败，切换到下一个代理: " + PROXY_URLS[currentProxyIndex]);
    }

    /**
     * 手动设置代理索引（调试用）
     *
     * @param index 0-4 之间的整数
     */
    public static synchronized void setProxyIndex(int index) {
        if (index >= 0 && index < PROXY_URLS.length) {
            currentProxyIndex = index;
            Log.i(TAG, "手动设置代理: " + PROXY_URLS[currentProxyIndex]);
        } else {
            Log.e(TAG, "无效的代理索引: " + index);
        }
    }

    /**
     * 重置为第一个代理（最优代理）
     * 建议在每次更新检查前调用
     */
    public static synchronized void resetProxy() {
        currentProxyIndex = 0;
        Log.i(TAG, "代理已重置为: " + PROXY_URLS[currentProxyIndex]);
    }

    /**
     * 获取当前代理状态（调试用）
     *
     * @return 格式化后的状态字符串
     */
    public static String getProxyStatus() {
        StringBuilder status = new StringBuilder("\n========== GitHub 代理状态 ==========\n");
        for (int i = 0; i < PROXY_URLS.length; i++) {
            status.append(i == currentProxyIndex ? "→ [当前] " : "   [备用] ")
                    .append("代理").append(i).append(": ")
                    .append(PROXY_URLS[i])
                    .append("\n");
        }
        status.append("=====================================");
        return status.toString();
    }

    /**
     * 获取代理源总数
     * @return 代理数量
     */
    public static int getProxyCount() {
        return PROXY_URLS.length;
    }
}
