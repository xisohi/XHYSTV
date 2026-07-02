package com.github.tvbox.osc.util;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * GitHub 加速工具类（优化版）
 * 功能特性：
 * 1. 多代理源支持
 * 2. 自动选择最快代理（异步测速，缓存24小时）
 * 3. 手动切换机制（保留轮询降级）
 * 4. 调试日志输出
 * <p>
 * 建议每月检查代理服务可用性并更新列表
 */
public class Github {
    private static final String TAG = "Github";

    /**
     * 代理加速源列表（按推荐优先级排序）
     * 更新日期: 2026-07-02
     */
    private static final String[] PROXY_URLS = {
            "https://github.catvod.com/",       // ✅ 已验证可用
            "https://ghp.ci/",                  // 🆕 社区推荐
            "https://ghproxy.net/",             // 备用（可能恢复）
            "https://ghfast.top/",              // 备用（可能恢复）
            "https://ghproxy.homeboyc.cn/",     // 🆕 社区推荐
            "https://gh.xisohi.dpdns.org/",      // 个人维护（备用）
    };

    private static volatile int currentProxyIndex = 0;
    private static volatile int fastestProxyIndex = 0;
    private static volatile long lastSpeedTestTime = 0;
    private static final long SPEED_TEST_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final String SPEED_TEST_URL = "https://github.com/robots.txt";
    private static final Random random = new Random();

    public static String getJson(String name) {
        return "https://xhys.lcjly.cn/update/" + name + ".json";
    }

    public static String getApk(String name) {
        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + name + ".apk";
        return getAcceleratedUrl(githubUrl);
    }

    private static String getAcceleratedUrl(String githubUrl) {
        if (System.currentTimeMillis() - lastSpeedTestTime >= SPEED_TEST_INTERVAL) {
            speedTestProxiesAsync();
        }
        return PROXY_URLS[fastestProxyIndex] + githubUrl;
    }

    private static void speedTestProxiesAsync() {
        new Thread(() -> {
            synchronized (Github.class) {
                if (System.currentTimeMillis() - lastSpeedTestTime < SPEED_TEST_INTERVAL) {
                    return;
                }
                int fastest = 0;
                long minTime = Long.MAX_VALUE;

                Log.d(TAG, "========== 开始测速 ==========");
                for (int i = 0; i < PROXY_URLS.length; i++) {
                    long start = System.currentTimeMillis();
                    boolean reachable = pingProxy(PROXY_URLS[i] + SPEED_TEST_URL);
                    if (reachable) {
                        long elapsed = System.currentTimeMillis() - start;
                        Log.d(TAG, "代理 " + i + " (" + PROXY_URLS[i] + ") 响应时间: " + elapsed + "ms ✅");
                        if (elapsed < minTime) {
                            minTime = elapsed;
                            fastest = i;
                        }
                    } else {
                        Log.w(TAG, "代理 " + i + " 不可达: " + PROXY_URLS[i] + " ❌");
                    }
                }

                fastestProxyIndex = fastest;
                lastSpeedTestTime = System.currentTimeMillis();
                Log.i(TAG, "测速完成，最快代理: " + PROXY_URLS[fastestProxyIndex] +
                        " 耗时 " + (minTime == Long.MAX_VALUE ? "不可用" : minTime + "ms"));
                Log.d(TAG, "========== 测速结束 ==========");
            }
        }).start();
    }

    private static boolean pingProxy(String testUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(testUrl);

            // DNS 解析日志（调试用）
            try {
                java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(url.getHost());
                Log.d(TAG, "DNS解析 " + url.getHost() + " -> " + addresses[0].getHostAddress());
            } catch (Exception e) {
                Log.w(TAG, "DNS解析失败: " + e.getMessage());
            }

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(2000);  // 优化：2秒超时
            conn.setReadTimeout(2000);

            int code = conn.getResponseCode();
            Log.d(TAG, "响应码: " + code + " - " + testUrl);
            return code >= 200 && code < 400;
        } catch (java.net.SocketTimeoutException e) {
            Log.d(TAG, "连接超时: " + testUrl);
            return false;
        } catch (Exception e) {
            Log.d(TAG, "连接失败: " + e.getMessage() + " - " + testUrl);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static synchronized void switchToNextProxy() {
        currentProxyIndex = (currentProxyIndex + 1) % PROXY_URLS.length;
        fastestProxyIndex = currentProxyIndex;
        lastSpeedTestTime = 0;
        Log.w(TAG, "下载失败，切换到下一个代理: " + PROXY_URLS[currentProxyIndex]);
    }

    public static synchronized void setProxyIndex(int index) {
        if (index >= 0 && index < PROXY_URLS.length) {
            currentProxyIndex = index;
            fastestProxyIndex = index;
            lastSpeedTestTime = 0;
            Log.i(TAG, "手动设置代理: " + PROXY_URLS[currentProxyIndex]);
        } else {
            Log.e(TAG, "无效的代理索引: " + index);
        }
    }

    public static synchronized void resetProxy() {
        currentProxyIndex = 0;
        fastestProxyIndex = 0;
        lastSpeedTestTime = 0;
        Log.i(TAG, "代理已重置为: " + PROXY_URLS[currentProxyIndex]);
    }

    public static synchronized void forceSpeedTest() {
        lastSpeedTestTime = 0;
        int fastest = 0;
        long minTime = Long.MAX_VALUE;

        Log.d(TAG, "========== 强制测速开始 ==========");
        for (int i = 0; i < PROXY_URLS.length; i++) {
            long start = System.currentTimeMillis();
            boolean reachable = pingProxy(PROXY_URLS[i] + SPEED_TEST_URL);
            if (reachable) {
                long elapsed = System.currentTimeMillis() - start;
                Log.d(TAG, "代理 " + i + " (" + PROXY_URLS[i] + ") 响应时间: " + elapsed + "ms ✅");
                if (elapsed < minTime) {
                    minTime = elapsed;
                    fastest = i;
                }
            } else {
                Log.w(TAG, "代理 " + i + " 不可达: " + PROXY_URLS[i] + " ❌");
            }
        }

        fastestProxyIndex = fastest;
        lastSpeedTestTime = System.currentTimeMillis();
        Log.i(TAG, "强制测速完成，最快代理: " + PROXY_URLS[fastestProxyIndex] +
                " 耗时 " + (minTime == Long.MAX_VALUE ? "不可用" : minTime + "ms"));
        Log.d(TAG, "========== 强制测速结束 ==========");
    }

    public static String getProxyStatus() {
        StringBuilder status = new StringBuilder("\n========== GitHub 代理状态 ==========\n");
        status.append("当前使用: 代理").append(fastestProxyIndex)
                .append(" (").append(PROXY_URLS[fastestProxyIndex]).append(")\n");
        status.append("测速缓存: ").append(lastSpeedTestTime == 0 ? "未测速/已失效" :
                (System.currentTimeMillis() - lastSpeedTestTime) / 1000 + "秒前\n");
        for (int i = 0; i < PROXY_URLS.length; i++) {
            status.append(i == fastestProxyIndex ? "→ [最快] " : "   [备用] ")
                    .append("代理").append(i).append(": ")
                    .append(PROXY_URLS[i])
                    .append("\n");
        }
        status.append("=====================================");
        return status.toString();
    }

    public static int getProxyCount() {
        return PROXY_URLS.length;
    }
}