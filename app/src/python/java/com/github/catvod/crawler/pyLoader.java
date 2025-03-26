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
import java.lang.reflect.Field;
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
            Spider sp = pythonLoader.getSpider(key, getPyUrl(cls, ext));
            spiders.put(key, sp);
            Log.i("PyLoader", "echo-getSpider 加载spider: " + key);
            return sp;
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params, String key, String api, String ext) {
        try {
            String doStr = params.get("do");
            assert doStr != null;
            if (doStr.equals("ck") || doStr.equals("live"))
                return pythonLoader.proxyLocal("", "", params);
            return (Object[]) pythonLoader.proxyLocal(key, getPyUrl(api, ext), params);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            LOG.i("echo-recentPyApi" + recentPyApi);
            PythonSpider originalSpider = (PythonSpider) pythonLoader.getSpider(MD5.string2MD5(recentPyApi), recentPyApi);
            PythonSpiderWrapper wrapper = new PythonSpiderWrapper(originalSpider);
            return wrapper.proxyLocal(params);
        } catch (Throwable th) {
            LOG.i("echo-Throwable:---" + th.getMessage());
            th.printStackTrace();
        }
        return null;
    }

    public static class PythonSpiderWrapper {
        private final PythonSpider spider;

        public PythonSpiderWrapper(PythonSpider spider) {
            this.spider = spider;
        }

        public Object[] proxyLocal(Map<String, String> param) {
            try {
                // 反射获取私有字段 app
                Field appField = PythonSpider.class.getDeclaredField("app");
                appField.setAccessible(true);
                PyObject app = (PyObject) appField.get(spider);

                // 反射获取私有字段 pySpider
                Field pySpiderField = PythonSpider.class.getDeclaredField("pySpider");
                pySpiderField.setAccessible(true);
                PyObject pySpider = (PyObject) pySpiderField.get(spider);

                // 调用 Python 接口获取原始结果
                assert app != null;
                List<PyObject> poList = app.callAttr("localProxy",
                        new Object[]{pySpider, spider.map2json(param).toString()}).asList();
                int code = poList.get(0).toInt();
                String type = poList.get(1).toString();
                String action = poList.get(2).toString();
                InputStream stream = new ByteArrayInputStream(action.getBytes("utf8"));
                Object extra = null;
                if (poList.size() > 3) {
                    extra = poList.get(3).toJava(Map.class);
                }
                return new Object[]{code, type, stream, extra};
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    private String getPyUrl(String api, String ext) {
        StringBuilder urlBuilder = new StringBuilder(api);
        if (!ext.isEmpty()) {
            urlBuilder.append(api.contains("?") ? "&" : "?").append("extend=").append(ext);
        }
        return urlBuilder.toString();
    }
}
