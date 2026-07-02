package com.github.tvbox.osc.util;

import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * GitHub 加速工具类（优化版）
 * 功能特性：
 * 1. 多代理源支持（5个主流加速服务）
 * 2. 自动选择最快代理（异步测速，缓存30分钟）
 * 3. 手动切换机制（保留轮询降级）
 * 4. 调试日志输出
 * <p>
 * 建议每3-6个月检查代理服务可用性并更新列表
 */
public class Github {
    private static final String TAG = "Github";

    /**
     * 代理加速源列表（按推荐优先级排序）
     */
    private static final String[] PROXY_URLS = {
            "https://ghproxy.net/",             // 国内速度快，首选
            "https://ghfast.top/",              // 稳定（备用）
            "https://github.catvod.com/",       // 猫影视git文件加速
            "https://gh.xisohi.dpdns.org/"      // 个人维护（备用）
    };

    // 当前使用的代理索引（用于手动切换，兼容原有逻辑）
    private static volatile int currentProxyIndex = 0;
    // 测速得到的最快代理索引
    private static volatile int fastestProxyIndex = 0;
    // 上次测速时间戳（用于缓存）
    private static volatile long lastSpeedTestTime = 0;
    // 测速缓存有效期（24小时）
    private static final long SPEED_TEST_INTERVAL = 24 * 60 * 60 * 1000L;
    // 测速用的小资源（必须稳定存在）
    private static final String SPEED_TEST_URL = "https://github.com/robots.txt";

    // 随机数生成器（预留）
    private static final Random random = new Random();

    /**
     * JSON 配置文件地址（自定义服务器 - 无需代理）
     *
     * @param name 配置文件名（不含扩展名）
     * @return 完整的JSON URL
     */
    public static String getJson(String name) {
        return "https://xhys.lcjly.cn/update/" + name + ".json";
    }

    /**
     * 获取加速后的 APK 下载链接（自动使用当前最快的代理）
     *
     * @param name APK 文件名（不含扩展名）
     * @return 加速后的完整下载URL
     */
    public static String getApk(String name) {
        String githubUrl = "https://github.com/xisohi/XHYSosc/releases/download/XHYSTV/" + name + ".apk";
        return getAcceleratedUrl(githubUrl);
    }

    /**
     * 核心方法：对 GitHub URL 进行代理加速
     * 策略：优先使用测速最快的代理，若缓存过期则异步触发测速
     *
     * @param githubUrl 原始GitHub URL
     * @return 加速后的URL
     */
    private static String getAcceleratedUrl(String githubUrl) {
        // 如果缓存过期，异步触发测速（不阻塞当前调用）
        if (System.currentTimeMillis() - lastSpeedTestTime >= SPEED_TEST_INTERVAL) {
            speedTestProxiesAsync();
        }
        // 使用当前已知最快的代理
        return PROXY_URLS[fastestProxyIndex] + githubUrl;
    }

    /**
     * 异步测速所有代理，并更新最快索引（非阻塞）
     * 内部已有缓存判断，不会频繁执行
     */
    private static void speedTestProxiesAsync() {
        new Thread(() -> {
            synchronized (Github.class) {
                // 双重检查，防止并发重复测速
                if (System.currentTimeMillis() - lastSpeedTestTime < SPEED_TEST_INTERVAL) {
                    return;
                }
                int fastest = 0;
                long minTime = Long.MAX_VALUE;

                for (int i = 0; i < PROXY_URLS.length; i++) {
                    long start = System.currentTimeMillis();
                    boolean reachable = pingProxy(PROXY_URLS[i] + SPEED_TEST_URL);
                    if (reachable) {
                        long elapsed = System.currentTimeMillis() - start;
                        if (elapsed < minTime) {
                            minTime = elapsed;
                            fastest = i;
                        }
                    } else {
                        Log.w(TAG, "代理 " + i + " 不可达: " + PROXY_URLS[i]);
                    }
                }

                // 更新最快索引和缓存时间
                fastestProxyIndex = fastest;
                lastSpeedTestTime = System.currentTimeMillis();
                Log.i(TAG, "测速完成，最快代理: " + PROXY_URLS[fastestProxyIndex] +
                        " 耗时 " + (minTime == Long.MAX_VALUE ? "不可用" : minTime + "ms"));
            }
        }).start();
    }

    /**
     * 使用 HEAD 请求测试代理连通性
     *
     * @param testUrl 测试URL（代理前缀+资源）
     * @return true 表示可达且返回有效状态码
     */
    private static boolean pingProxy(String testUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(testUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
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
     * 当下载失败时调用此方法切换到下一个代理
     * 会同时更新 fastestProxyIndex 和 currentProxyIndex
     */
    public static synchronized void switchToNextProxy() {
        currentProxyIndex = (currentProxyIndex + 1) % PROXY_URLS.length;
        fastestProxyIndex = currentProxyIndex;
        // 清空测速缓存，强制下次重新测速
        lastSpeedTestTime = 0;
        Log.w(TAG, "下载失败，切换到下一个代理: " + PROXY_URLS[currentProxyIndex]);
    }

    /**
     * 手动设置代理索引（调试用）
     * 同时更新 fastestProxyIndex 和 currentProxyIndex
     *
     * @param index 0-3 之间的整数
     */
    public static synchronized void setProxyIndex(int index) {
        if (index >= 0 && index < PROXY_URLS.length) {
            currentProxyIndex = index;
            fastestProxyIndex = index;
            lastSpeedTestTime = 0; // 强制下次重新测速
            Log.i(TAG, "手动设置代理: " + PROXY_URLS[currentProxyIndex]);
        } else {
            Log.e(TAG, "无效的代理索引: " + index);
        }
    }

    /**
     * 重置为第一个代理（并清空测速缓存）
     */
    public static synchronized void resetProxy() {
        currentProxyIndex = 0;
        fastestProxyIndex = 0;
        lastSpeedTestTime = 0;
        Log.i(TAG, "代理已重置为: " + PROXY_URLS[currentProxyIndex]);
    }

    /**
     * 强制立即测速（同步方式，可能阻塞调用线程，谨慎使用）
     * 供外部需要立即获取最快代理时调用
     */
    public static synchronized void forceSpeedTest() {
        lastSpeedTestTime = 0; // 清空缓存
        // 直接在当前线程执行测速（可能耗时）
        int fastest = 0;
        long minTime = Long.MAX_VALUE;
        for (int i = 0; i < PROXY_URLS.length; i++) {
            long start = System.currentTimeMillis();
            boolean reachable = pingProxy(PROXY_URLS[i] + SPEED_TEST_URL);
            if (reachable) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed < minTime) {
                    minTime = elapsed;
                    fastest = i;
                }
            } else {
                Log.w(TAG, "代理 " + i + " 不可达: " + PROXY_URLS[i]);
            }
        }
        fastestProxyIndex = fastest;
        lastSpeedTestTime = System.currentTimeMillis();
        Log.i(TAG, "强制测速完成，最快代理: " + PROXY_URLS[fastestProxyIndex] +
                " 耗时 " + (minTime == Long.MAX_VALUE ? "不可用" : minTime + "ms"));
    }

    /**
     * 获取当前代理状态（调试用）
     *
     * @return 格式化后的状态字符串
     */
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

    /**
     * 获取代理源总数
     *
     * @return 代理数量
     */
    public static int getProxyCount() {
        return PROXY_URLS.length;
    }
}