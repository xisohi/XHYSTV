// 文件: app/src/python/java/com/github/catvod/crawler/python/PyLoader.java
package com.github.catvod.crawler;

import android.util.Log;
import com.chaquo.python.PyObject;
import com.github.catvod.crawler.python.IPyLoader;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.undcover.freedom.pyramid.PythonLoader;
import com.undcover.freedom.pyramid.PythonSpider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class pyLoader implements IPyLoader {
    private final PythonLoader pythonLoader = PythonLoader.getInstance().setApplication(App.getInstance());
    private static final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private String lastConfig = null; // 记录上次的配置

    @Override
    public void clear() {
        spiders.clear();
    }

    @Override
    public void setConfig(String jsonStr) {
        if (jsonStr != null && !jsonStr.equals(lastConfig)) {
            Log.i("PyLoader", "echo-setConfig 初始化json ");
            pythonLoader.setConfig(jsonStr);
            lastConfig = jsonStr;
        }
    }

    private String recentPyApi;
    @Override
    public void setRecentPyKey(String pyApi) {
        recentPyApi = pyApi;
    }

    @Override
    public Spider getSpider(String key, String cls, String ext) {
        if (spiders.containsKey(key)) {
            Log.i("PyLoader", "echo-getSpider spider缓存: " + key);
            return spiders.get(key);
        }
        try {
            Log.i("PyLoader", "echo-getSpider url: " + getPyUrl(cls, ext));
            Spider sp = pythonLoader.getSpider(key, getPyUrl(cls, ext));
//            Log.i("PyLoader", "echo-getSpider homeContent: " + sp.homeContent(true));
            spiders.put(key, sp);
            Log.i("PyLoader", "echo-getSpider 加载spider: " + key);
            return (Spider)sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params){
        LOG.i("echo-recentPyApi" + recentPyApi);
        try {
            PythonSpider originalSpider = (PythonSpider) getSpider(MD5.string2MD5(recentPyApi), recentPyApi,"");
            return originalSpider.proxyLocal(params);
        } catch (Throwable th) {
            LOG.i("echo-proxyInvoke_Throwable:---" + th.getMessage());
            th.printStackTrace();
        }
        return null;
    }

    private String getPyUrl(String api, String ext) throws UnsupportedEncodingException {
        StringBuilder urlBuilder = new StringBuilder(api);
        if (!ext.isEmpty()) {
//            ext= URLEncoder.encode(ext,"utf8");
            urlBuilder.append(api.contains("?") ? "&" : "?").append("extend=").append(ext);
        }
        return urlBuilder.toString();
    }
}
