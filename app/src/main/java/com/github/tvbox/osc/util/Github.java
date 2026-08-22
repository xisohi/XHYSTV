package com.github.tvbox.osc.util;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Github {
    private static final String TAG = "Github";
    private static final long SPEED_TEST_INTERVAL = 24 * 60 * 60 * 1000L;

    /**
     * 对外公开：执行测速（内部自动判断24h缓存）
     */
    public static void runSpeedTestIfNeed() {
        speedTestProxiesSync();
    }
    /**
     * 代理加速源域名列表（不带协议）
     */
    private static final String[] PROXY_HOSTS = {
            //兼容 Android 4.4
            "github.catvod.com/",
            "ghproxy.net/",
            "gh-proxy.org/",
            //Android 5.0+
            "ghfast.top/",
            "gh.acmsz.top/",
            "gh.xisohi.dpdns.org/"
    };

    // 测速结果缓存
    private static List<Integer> speedRanking = new ArrayList<>();
    private static volatile long lastSpeedTestTime = 0;

    /**
     * 根据系统版本获取协议前缀
     */
    private static String getProxyScheme() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP
                ? "http://" : "https://";
    }

    /**
     * 获取完整的代理 URL 数组
     */
    private static String[] getProxyUrls() {
        String scheme = getProxyScheme();
        String[] urls = new String[PROXY_HOSTS.length];
        for (int i = 0; i < PROXY_HOSTS.length; i++) {
            urls[i] = scheme + PROXY_HOSTS[i];
        }
        return urls;
    }

    /**
     * 获取测速目标 URL
     */
    private static String getSpeedTestUrl() {
        return getProxyScheme() + "github.com/robots.txt";
    }

    /**
     * 获取 JSON 配置地址
     */
    public static String getJson(String name) {
        return "https://xhys.xisohi.dpdns.org/update/" + name + ".json";
    }

    /**
     * 获取 APK 下载地址（使用最快代理）
     */
    public static String getApk(String name) {
        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + name + ".apk";
        return getAcceleratedUrl(githubUrl);
    }

    /**
     * 获取加速后的 URL
     */
    private static String getAcceleratedUrl(String githubUrl) {
        if (System.currentTimeMillis() - lastSpeedTestTime >= SPEED_TEST_INTERVAL) {
            speedTestProxiesSync();
        }
        if (speedRanking.isEmpty()) {
            return getProxyUrls()[0] + githubUrl;
        }
        return getProxyUrls()[speedRanking.get(0)] + githubUrl;
    }

    /**
     * 按指定索引获取代理 URL（用于下载失败轮询）
     */
    public static synchronized String getProxyUrlByIndex(String githubUrl, int index) {
        if (speedRanking.isEmpty()) {
            speedTestProxiesSync();
        }
        if (index < 0 || index >= speedRanking.size()) {
            return null;
        }
        String[] proxyUrls = getProxyUrls();
        int proxyIndex = speedRanking.get(index);
        Log.i(TAG, "指定代理索引 " + (index + 1) + "/" + speedRanking.size() +
                ": " + proxyUrls[proxyIndex]);
        return proxyUrls[proxyIndex] + githubUrl;
    }

    /**
     * 获取可用代理数量
     */
    public static synchronized int getProxyCount() {
        return speedRanking.isEmpty() ? PROXY_HOSTS.length : speedRanking.size();
    }

    /**
     * 同步测速
     */
    private static synchronized void speedTestProxiesSync() {
        if (System.currentTimeMillis() - lastSpeedTestTime < SPEED_TEST_INTERVAL && !speedRanking.isEmpty()) {
            return;
        }

        Log.d(TAG, "========== 开始测速 ==========");
        List<ProxySpeed> speeds = new ArrayList<>();
        String[] proxyUrls = getProxyUrls();
        String testUrl = getSpeedTestUrl();

        for (int i = 0; i < proxyUrls.length; i++) {
            long start = System.currentTimeMillis();
            boolean reachable = pingProxy(proxyUrls[i] + testUrl);
            if (reachable) {
                long elapsed = System.currentTimeMillis() - start;
                speeds.add(new ProxySpeed(i, elapsed));
                Log.d(TAG, "代理 " + i + " (" + proxyUrls[i] + ") 响应时间: " + elapsed + "ms ✅");
            } else {
                Log.w(TAG, "代理 " + i + " 不可达: " + proxyUrls[i] + " ❌");
            }
        }

        java.util.Collections.sort(speeds, new Comparator<ProxySpeed>() {
            @Override
            public int compare(ProxySpeed o1, ProxySpeed o2) {
                return Long.compare(o1.time, o2.time);
            }
        });

        speedRanking.clear();
        for (ProxySpeed ps : speeds) {
            speedRanking.add(ps.index);
        }

        lastSpeedTestTime = System.currentTimeMillis();
        if (!speedRanking.isEmpty()) {
            Log.i(TAG, "测速完成，最快代理: " + proxyUrls[speedRanking.get(0)]);
            Log.i(TAG, "代理优先级顺序: " + speedRanking.toString());
        } else {
            Log.w(TAG, "测速完成，无可用代理");
            for (int i = 0; i < proxyUrls.length; i++) {
                speedRanking.add(i);
            }
        }
        Log.d(TAG, "========== 测速结束 ==========");
    }

    private static class ProxySpeed {
        int index;
        long time;
        ProxySpeed(int index, long time) {
            this.index = index;
            this.time = time;
        }
    }

    private static boolean pingProxy(String testUrl) {
        okhttp3.OkHttpClient client = null;
        try {
            client = OkGoHelper.getDefaultClient();
            if (client == null) {
                client = OkGoHelper.getItvClient();
            }
            if (client == null) {
                Log.w(TAG, "OkHttpClient未初始化，使用Legacy测速");
                return pingProxyLegacy(testUrl);
            }

            okhttp3.OkHttpClient pingClient = client.newBuilder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(testUrl)
                    .head()
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();

            try (okhttp3.Response response = pingClient.newCall(request).execute()) {
                int code = response.code();
                // 只要服务器有响应（2xx, 3xx, 4xx），我们就认为代理可用
                // 只有连接错误（IOException）才认为不可达
                if (code > 0) {
                    Log.d(TAG, "ping 响应码: " + code + " (代理可达)");
                    return true;
                }
                Log.d(TAG, "ping 返回无效状态码: " + code);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "pingProxy 异常: " + e.getMessage() + ", 降级到Legacy");
            return pingProxyLegacy(testUrl);
        }
    }

    private static boolean pingProxyLegacy(String testUrl) {
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

    /**
     * 强制测速
     */
    public static synchronized void forceSpeedTest() {
        lastSpeedTestTime = 0;
        speedRanking.clear();
        speedTestProxiesSync();
    }

    /**
     * 获取代理状态
     */
    public static String getProxyStatus() {
        String[] proxyUrls = getProxyUrls();
        StringBuilder status = new StringBuilder("\n========== GitHub 代理状态 ==========\n");
        if (!speedRanking.isEmpty()) {
            status.append("最快代理: ").append(proxyUrls[speedRanking.get(0)]).append("\n");
            status.append("代理优先级顺序:\n");
            for (int i = 0; i < speedRanking.size(); i++) {
                status.append("  ").append(i + 1).append(". ")
                        .append(proxyUrls[speedRanking.get(i)]).append("\n");
            }
        } else {
            status.append("未测速或测速失败\n");
        }
        status.append("=====================================");
        return status.toString();
    }
    public static long getLastSpeedTestTime() {
        return lastSpeedTestTime;
    }
    public static long getSpeedTestInterval() {
        return SPEED_TEST_INTERVAL;
    }
}