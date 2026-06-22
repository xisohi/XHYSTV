package com.github.tvbox.osc.api;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.js.Trans;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class DanmakuApi {
    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final String BUILTIN_API = "https://saas-oa.shyeguang.cn";
    private static final long BUILTIN_TIMEOUT = TimeUnit.SECONDS.toMillis(60);
    private static final int BUILTIN_MAX_RETRY = 2;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    public interface SearchCallback {
        void onFound(String url);
    }

    public static boolean canSearch() {
        return DanmuHelper.isOpen() && !TextUtils.isEmpty(getApiUrl());
    }

    public static void search(String name, String episode, SearchCallback callback) {
        String apiUrl = getApiUrl();
//        LOG.i("echo-danmaku search apiUrl: " + apiUrl);
        if (TextUtils.isEmpty(apiUrl) || callback == null) return;
        try {
            OkHttp.cancel(TAG);
            if (isBuiltinApi(apiUrl)) {
                searchBuiltin(apiUrl, name, episode, callback, 0);
                return;
            }
            newCall(apiUrl, name, episode).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    LOG.e("echo-danmaku search error: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        String body = response.body() == null ? "" : response.body().string();
                        String url = parseUrl(body);
                        if (!TextUtils.isEmpty(url)) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    callback.onFound(url);
                                }
                            });
                        }
                    } catch (Throwable th) {
                        LOG.e("echo-danmaku search parse error: " + th.getMessage());
                    }
                }
            });
        } catch (Throwable th) {
            LOG.e("echo-danmaku search start error: " + th.getMessage());
        }
    }

    public static void cancel() {
        OkHttp.cancel(TAG);
    }

    private static void searchBuiltin(String apiUrl, String name, String episode, SearchCallback callback, int retry) {
        final String baseUrl = normalizeBaseUrl(apiUrl);
        final String simpleName = Trans.t2s(name == null ? "" : name);
        final String simpleEpisode = Trans.t2s(episode == null ? "" : episode);
        final String searchUrl = baseUrl + "/api/v2/search/episodes?anime=" + encode(simpleName);
        LOG.i("echo-danmaku builtin search episodes: " + searchUrl + ", retry=" + retry);
        OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (retry < BUILTIN_MAX_RETRY) {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage() + ", retry later");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            searchBuiltin(apiUrl, name, episode, callback, retry + 1);
                        }
                    }, 1500L * (retry + 1));
                } else {
                    LOG.e("echo-danmaku builtin search error: " + e.getMessage());
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    String episodeId = findEpisodeId(body, simpleEpisode);
                    if (!TextUtils.isEmpty(episodeId)) {
                        loadBuiltinComment(baseUrl, episodeId, callback);
                        return;
                    }
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin episode parse error: " + th.getMessage());
                    searchBuiltinAnime(baseUrl, simpleName, simpleEpisode, callback);
                }
            }
        });
    }

    private static void searchBuiltinAnime(String baseUrl, String name, String episode, SearchCallback callback) {
        final String searchUrl = baseUrl + "/api/v2/search/anime?keyword=" + encode(name);
        LOG.i("echo-danmaku builtin search anime: " + searchUrl);
        OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), searchUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LOG.e("echo-danmaku builtin anime error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    String animeId = findAnimeId(body);
                    if (TextUtils.isEmpty(animeId)) return;
                    loadBuiltinBangumi(baseUrl, animeId, episode, callback);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin anime parse error: " + th.getMessage());
                }
            }
        });
    }

    private static void loadBuiltinBangumi(String baseUrl, String animeId, String episode, SearchCallback callback) {
        final String bangumiUrl = baseUrl + "/api/v2/bangumi/" + animeId;
        LOG.i("echo-danmaku builtin bangumi: " + bangumiUrl);
        OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), bangumiUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LOG.e("echo-danmaku builtin bangumi error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    String episodeId = findEpisodeId(body, episode);
                    if (!TextUtils.isEmpty(episodeId)) loadBuiltinComment(baseUrl, episodeId, callback);
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin bangumi parse error: " + th.getMessage());
                }
            }
        });
    }

    private static void loadBuiltinComment(String baseUrl, String episodeId, SearchCallback callback) {
        final String commentUrl = baseUrl + "/api/v2/comment/" + episodeId + "?format=json";
        LOG.i("echo-danmaku builtin comment: " + commentUrl);
        OkHttp.newCall(OkHttp.client(BUILTIN_TIMEOUT), commentUrl, TAG).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                LOG.e("echo-danmaku builtin comment error: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    String body = response.body() == null ? "" : response.body().string();
                    final String xml = commentJsonToXml(body);
                    if (TextUtils.isEmpty(xml)) return;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFound(xml);
                        }
                    });
                } catch (Throwable th) {
                    LOG.e("echo-danmaku builtin comment parse error: " + th.getMessage());
                }
            }
        });
    }

    private static Call newCall(String apiUrl, String name, String episode) {
        name = Trans.t2s(name == null ? "" : name);
        episode = Trans.t2s(episode == null ? "" : episode);
        if (apiUrl.contains("{name}") || apiUrl.contains("{episode}")) {
            return OkHttp.newCall(apiUrl.replace("{name}", name).replace("{episode}", episode), TAG);
        }
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("name", name);
        params.put("episode", episode);
        return OkHttp.newCall(apiUrl, OkHttp.toBody(params), TAG);
    }

    private static String getApiUrl() {
        String custom = Hawk.get(HawkConfig.DANMU_API, "");
        if (!TextUtils.isEmpty(custom)) return custom.trim();
        String config = ApiConfig.get().getDanmaku().trim();
        return TextUtils.isEmpty(config) ? BUILTIN_API : config;
    }

    private static boolean isBuiltinApi(String apiUrl) {
        if (TextUtils.isEmpty(apiUrl)) return false;
        return normalizeBaseUrl(apiUrl).equals(BUILTIN_API);
    }

    private static String normalizeBaseUrl(String apiUrl) {
        String url = apiUrl == null ? "" : apiUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/87654321")) url = url.substring(0, url.length() - "/87654321".length());
        return url;
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text == null ? "" : text, "UTF-8");
        } catch (Throwable th) {
            return text == null ? "" : text;
        }
    }

    private static String findAnimeId(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray array = object.optJSONArray("animes");
        if (array == null) array = object.optJSONArray("anime");
        if (array == null) array = object.optJSONArray("data");
        if (array == null || array.length() <= 0) return "";
        JSONObject item = array.optJSONObject(0);
        if (item == null) return "";
        String id = item.optString("animeId", "");
        if (TextUtils.isEmpty(id)) id = item.optString("id", "");
        return id;
    }

    private static String findEpisodeId(String body, String episode) throws Exception {
        JSONArray episodes = findEpisodes(new JSONObject(body));
        if (episodes == null || episodes.length() <= 0) return "";
        int targetNumber = extractNumber(episode);
        String firstId = "";
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject item = episodes.optJSONObject(i);
            if (item == null) continue;
            String id = firstString(item, "episodeId", "id");
            if (TextUtils.isEmpty(firstId)) firstId = id;
            String title = firstString(item, "episodeTitle", "title", "name");
            int number = parseEpisodeNumber(firstString(item, "episodeNumber", "number", "sort"));
            if (!TextUtils.isEmpty(episode) && !TextUtils.isEmpty(title) && title.contains(episode)) return id;
            if (targetNumber > 0 && number == targetNumber) return id;
        }
        return firstId;
    }

    private static JSONArray findEpisodes(JSONObject object) {
        JSONArray array = object.optJSONArray("episodes");
        if (array != null) return array;
        JSONObject bangumi = object.optJSONObject("bangumi");
        if (bangumi != null) {
            array = bangumi.optJSONArray("episodes");
            if (array != null) return array;
        }
        JSONArray animes = object.optJSONArray("animes");
        if (animes == null) animes = object.optJSONArray("anime");
        if (animes == null) animes = object.optJSONArray("data");
        if (animes != null) {
            for (int i = 0; i < animes.length(); i++) {
                JSONObject item = animes.optJSONObject(i);
                if (item == null) continue;
                array = item.optJSONArray("episodes");
                if (array != null && array.length() > 0) return array;
            }
        }
        return null;
    }

    private static int parseEpisodeNumber(String value) {
        try {
            if (TextUtils.isEmpty(value)) return -1;
            return (int) Float.parseFloat(value);
        } catch (Throwable th) {
            return extractNumber(value);
        }
    }

    private static int extractNumber(String text) {
        if (TextUtils.isEmpty(text)) return -1;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) builder.append(ch);
        }
        if (builder.length() == 0) return -1;
        try {
            return Integer.parseInt(builder.toString());
        } catch (Throwable th) {
            return -1;
        }
    }

    private static String commentJsonToXml(String body) throws Exception {
        JSONObject object = new JSONObject(body);
        JSONArray comments = object.optJSONArray("comments");
        if (comments == null) comments = object.optJSONArray("data");
        if (comments == null || comments.length() <= 0) return "";
        StringBuilder builder = new StringBuilder();
        builder.append("<i>");
        for (int i = 0; i < comments.length(); i++) {
            JSONObject item = comments.optJSONObject(i);
            if (item == null) continue;
            String param = item.optString("p", "");
            String text = item.optString("m", "");
            if (TextUtils.isEmpty(text)) text = item.optString("text", "");
            if (TextUtils.isEmpty(param) || TextUtils.isEmpty(text)) continue;
            builder.append("<d p=\"").append(escapeXml(normalizeDanmakuParam(param))).append("\">")
                    .append(escapeXml(text)).append("</d>");
        }
        builder.append("</i>");
        String xml = builder.toString();
        LOG.i("echo-danmaku builtin xml length: " + xml.length());
        return xml;
    }

    private static String normalizeDanmakuParam(String param) {
        String[] values = param.split(",");
        if (values.length < 4) return param;
        String time = values[0];
        String type = values[1];
        String size = values[2];
        String color = values[3];
        if (isColorValue(size)) {
            color = size;
            size = "25";
        } else if (!isColorValue(color)) {
            color = "16777215";
        }
        return time + "," + type + "," + size + "," + normalizeColor(color);
    }

    private static boolean isColorValue(String value) {
        try {
            if (TextUtils.isEmpty(value)) return false;
            String text = value.trim();
            if (text.startsWith("#")) return true;
            if (text.startsWith("0x") || text.startsWith("0X")) return true;
            long color = Long.parseLong(text);
            return color >= 0 && color <= 0x00ffffffL;
        } catch (Throwable th) {
            return false;
        }
    }

    private static String normalizeColor(String color) {
        if (TextUtils.isEmpty(color)) return "16777215";
        String text = color.trim();
        try {
            if (text.startsWith("#")) return String.valueOf(Long.parseLong(text.substring(1), 16));
            if (text.startsWith("0x") || text.startsWith("0X")) return String.valueOf(Long.parseLong(text.substring(2), 16));
        } catch (Throwable ignored) {
        }
        return text;
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String escapeXml(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
                .replace(">", "&gt;")
                .replace("<", "&lt;");
    }

    private static String parseUrl(String body) throws Exception {
        if (TextUtils.isEmpty(body)) return "";
        String text = body.trim();
        if (text.startsWith("[")) {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String url = object.optString("url", "").trim();
                if (!TextUtils.isEmpty(url)) return url;
            }
        } else if (text.startsWith("{")) {
            return new JSONObject(text).optString("url", "").trim();
        } else if (text.startsWith("http") || text.startsWith("file")) {
            return text;
        }
        return "";
    }
}
