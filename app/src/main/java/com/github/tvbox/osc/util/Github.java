package com.github.tvbox.osc.util;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Github {
    private static final String TAG = "Github";

    /**
     * 代理加速源列表（按推荐优先级排序）
     * 注意: 使用 HTTP 以兼容 Android 4.4 的 SSL 限制
     */
    private static final String[] PROXY_URLS = {
            //兼容 Android 4.4
            "http://github.catvod.com/",
            "http://ghproxy.net/",
            "http://gh-proxy.org/",
            //Android 4.4 无法使用
            "https://github.akams.cn/",
            "https://gh.llkk.cc/",
            "https://gh.xisohi.dpdns.org/"
    };

    // 存储测速结果（按速度排序的代理索引列表）
    private static List<Integer> speedRanking = new ArrayList<>();
    private static volatile int currentRetryIndex = 0;
    private static volatile long lastSpeedTestTime = 0;
    private static final long SPEED_TEST_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final String SPEED_TEST_URL = "https://github.com/robots.txt";

    public static String getJson(String name) {
        return "https://xhys.lcjly.cn/update/" + name + ".json";
    }

    public static String getApk(String name) {
        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + name + ".apk";
        return getAcceleratedUrl(githubUrl);
    }

    private static String getAcceleratedUrl(String githubUrl) {
        if (System.currentTimeMillis() - lastSpeedTestTime >= SPEED_TEST_INTERVAL) {
            speedTestProxiesSync();  // 同步测速，保证结果可用
        }
        // 如果测速结果为空，使用默认排序
        if (speedRanking.isEmpty()) {
            return PROXY_URLS[0] + githubUrl;
        }
        // 从最快的代理开始
        currentRetryIndex = 0;
        int fastestIndex = speedRanking.get(0);
        return PROXY_URLS[fastestIndex] + githubUrl;
    }

    /**
     * 获取当前应该使用的代理 URL（按速度优先级轮询）
     * 每次调用自动切换到下一个代理
     */
    public static synchronized String getNextProxyUrl(String githubUrl) {
        if (speedRanking.isEmpty()) {
            speedTestProxiesSync();
        }
        if (currentRetryIndex >= speedRanking.size()) {
            // 所有代理都已尝试
            return null;
        }
        int proxyIndex = speedRanking.get(currentRetryIndex);
        currentRetryIndex++;
        Log.i(TAG, "尝试代理 " + currentRetryIndex + "/" + speedRanking.size() +
                ": " + PROXY_URLS[proxyIndex]);
        return PROXY_URLS[proxyIndex] + githubUrl;
    }

    /**
     * 按指定索引获取代理 URL（用于外部控制轮询）
     */
    public static synchronized String getProxyUrlByIndex(String githubUrl, int index) {
        if (speedRanking.isEmpty()) {
            speedTestProxiesSync();
        }
        if (index < 0 || index >= speedRanking.size()) {
            return null;
        }
        int proxyIndex = speedRanking.get(index);
        Log.i(TAG, "指定代理索引 " + (index + 1) + "/" + speedRanking.size() +
                ": " + PROXY_URLS[proxyIndex]);
        return PROXY_URLS[proxyIndex] + githubUrl;
    }

    /**
     * 获取可用代理数量
     */
    public static synchronized int getProxyCount() {
        return speedRanking.isEmpty() ? PROXY_URLS.length : speedRanking.size();
    }

    /**
     * 重置重试索引（下载成功或重新开始时调用）
     */
    public static synchronized void resetRetry() {
        currentRetryIndex = 0;
    }

    /**
     * 获取当前已重试次数
     */
    public static synchronized int getCurrentRetryIndex() {
        return currentRetryIndex;
    }

    /**
     * 同步测速（会阻塞当前线程，确保结果可用）
     */
    private static synchronized void speedTestProxiesSync() {
        if (System.currentTimeMillis() - lastSpeedTestTime < SPEED_TEST_INTERVAL && !speedRanking.isEmpty()) {
            return;
        }

        Log.d(TAG, "========== 开始测速 ==========");
        List<ProxySpeed> speeds = new ArrayList<>();

        for (int i = 0; i < PROXY_URLS.length; i++) {
            long start = System.currentTimeMillis();
            boolean reachable = pingProxy(PROXY_URLS[i] + SPEED_TEST_URL);
            if (reachable) {
                long elapsed = System.currentTimeMillis() - start;
                speeds.add(new ProxySpeed(i, elapsed));
                Log.d(TAG, "代理 " + i + " (" + PROXY_URLS[i] + ") 响应时间: " + elapsed + "ms ✅");
            } else {
                Log.w(TAG, "代理 " + i + " 不可达: " + PROXY_URLS[i] + " ❌");
            }
        }

        // ✅ 使用 Collections.sort（兼容 API 1+）
        java.util.Collections.sort(speeds, new Comparator<ProxySpeed>() {
            @Override
            public int compare(ProxySpeed o1, ProxySpeed o2) {
                if (o1.time < o2.time) return -1;
                if (o1.time > o2.time) return 1;
                return 0;
            }
        });

        speedRanking.clear();
        for (ProxySpeed ps : speeds) {
            speedRanking.add(ps.index);
        }

        lastSpeedTestTime = System.currentTimeMillis();
        if (!speedRanking.isEmpty()) {
            Log.i(TAG, "测速完成，最快代理: " + PROXY_URLS[speedRanking.get(0)]);
            Log.i(TAG, "代理优先级顺序: " + speedRanking.toString());
        } else {
            Log.w(TAG, "测速完成，无可用代理");
            // 降级：使用所有代理按原始顺序
            for (int i = 0; i < PROXY_URLS.length; i++) {
                speedRanking.add(i);
            }
        }
        Log.d(TAG, "========== 测速结束 ==========");
    }

    /**
     * 代理速度数据结构
     */
    private static class ProxySpeed {
        int index;
        long time;
        ProxySpeed(int index, long time) {
            this.index = index;
            this.time = time;
        }
    }

    private static boolean pingProxy(String testUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(testUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // 强制测速（外部调用）
    public static synchronized void forceSpeedTest() {
        lastSpeedTestTime = 0;
        speedRanking.clear();
        currentRetryIndex = 0;
        speedTestProxiesSync();
    }

    public static String getProxyStatus() {
        StringBuilder status = new StringBuilder("\n========== GitHub 代理状态 ==========\n");
        if (!speedRanking.isEmpty()) {
            status.append("最快代理: ").append(PROXY_URLS[speedRanking.get(0)]).append("\n");
            status.append("代理优先级顺序:\n");
            for (int i = 0; i < speedRanking.size(); i++) {
                int idx = speedRanking.get(i);
                status.append("  ").append(i + 1).append(". ")
                        .append(PROXY_URLS[idx])
                        .append("\n");
            }
        } else {
            status.append("未测速或测速失败\n");
        }
        status.append("=====================================");
        return status.toString();
    }
}