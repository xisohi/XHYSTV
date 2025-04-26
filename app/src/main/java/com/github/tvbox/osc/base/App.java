package com.github.tvbox.osc.base;

import android.app.Activity;
import androidx.multidex.MultiDexApplication;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.github.catvod.crawler.JsLoader;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;
import com.github.tvbox.osc.ui.xupdate.UpdateHttpService;
import com.xuexiang.xupdate.XUpdate;
import com.xuexiang.xupdate.entity.UpdateError;
import com.xuexiang.xupdate.listener.OnUpdateFailureListener;
import com.xuexiang.xupdate.utils.UpdateUtils;
import com.lzy.okgo.OkGo;
import android.content.Context;
import android.os.Environment;
import android.widget.Toast;
/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;

    private static P2PClass p;
    public static String burl;
    private static String dashData;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initParams();
        // OKGo
        OkGoHelper.init(); //台标获取
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        PlayerHelper.init();
        QuickJSLoader.init();
        FileUtils.cleanPlayerCache();
        //初始化更新
        initUpdate();
    }

    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);
        if (!Hawk.contains(HawkConfig.PLAY_TYPE)) {
            Hawk.put(HawkConfig.PLAY_TYPE, 1);
        }
        //自定义默认配置，首页推荐，硬解，安全dns，缩略图
        if (!Hawk.contains(HawkConfig.HOME_REC)) {
            Hawk.put(HawkConfig.HOME_REC, 2);
        }
        if (!Hawk.contains(HawkConfig.IJK_CODEC)) {
            Hawk.put(HawkConfig.IJK_CODEC, "硬解码");
        }
        if (!Hawk.contains(HawkConfig.DOH_URL)) {
            Hawk.put(HawkConfig.DOH_URL, 2);
        }
        if (!Hawk.contains(HawkConfig.SEARCH_VIEW)) {
            Hawk.put(HawkConfig.SEARCH_VIEW, 2);
        }
    }

    public static App getInstance() {
        return instance;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }


    private VodInfo vodInfo;
    public void setVodInfo(VodInfo vodinfo){
        this.vodInfo = vodinfo;
    }
    public VodInfo getVodInfo(){
        return this.vodInfo;
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }

    public Activity getCurrentActivity() {
        return AppManager.getInstance().currentActivity();
    }

    public void setDashData(String data) {
        dashData = data;
    }
    public String getDashData() {
        return dashData;
    }
    /**
     * 初始化更新组件服务
     */
    private void initUpdate() {
        XUpdate.get()
                .debug(true)
                .isWifiOnly(false) //默认设置只在wifi下检查版本更新
                .isGet(true)  //默认设置使用get请求检查版本
                .isAutoMode(false) //默认设置非自动模式，可根据具体使用配置
                .setApkCacheDir(getDiskCachePath(instance))
                .param("VersionCode", UpdateUtils.getVersionCode(this))
                .param("VersionName", getPackageName())
                .setOnUpdateFailureListener(new OnUpdateFailureListener() {
                    @Override
                    public void onFailure(UpdateError error) {
                        error.printStackTrace();
                        updateString(error);
                    } //设置版本更新出错的监听
                })
                .supportSilentInstall(false) //设置是否支持静默安装，默认是true
                .setIUpdateHttpService(new UpdateHttpService()) // 实现网络请求功能。
                .init(this); // 这个必须初始化
    }
    /**
     * 获取cache路径
     */
    private static String getDiskCachePath(Context context) {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
            return context.getExternalCacheDir().getPath();
        } else {
            return context.getCacheDir().getPath();
        }
    }

    private void updateString(UpdateError error) {
        switch (error.getCode()) {
            case 2000:
                // ToastUtils.showShort("查询更新失败");
                Toast.makeText(this, getString(R.string.update_code_2000), Toast.LENGTH_SHORT).show();
                break;
            case 2001:
                // ToastUtils.showShort( "没有wifi");
                Toast.makeText(this, getString(R.string.update_code_2001), Toast.LENGTH_SHORT).show();
                break;
            case 2002:
                // ToastUtils.showShort("没有网络");
                Toast.makeText(this, getString(R.string.update_code_2002), Toast.LENGTH_SHORT).show();
                break;
            case 2003:
                // ToastUtils.showShort( "正在进行版本更新");
                Toast.makeText(this, getString(R.string.update_code_2003), Toast.LENGTH_SHORT).show();
                break;
            case 2004:
                // ToastUtils.showShort( "已是最新版本");
                Toast.makeText(this, getString(R.string.update_code_2004), Toast.LENGTH_SHORT).show();
                break;
            case 2005:
                // ToastUtils.showShort( "版本检查返回空");
                Toast.makeText(this, getString(R.string.update_code_2005), Toast.LENGTH_SHORT).show();
                break;
            case 2006:
                // ToastUtils.showShort( "版本检查返回json解析失败");
                Toast.makeText(this, getString(R.string.update_code_2006), Toast.LENGTH_SHORT).show();
                break;
            case 2007:
                // ToastUtils.showShort( "已经被忽略的版本");
                //Toast.makeText(this, getString(R.string.update_code_2007), Toast.LENGTH_SHORT).show();
                break;
            case 2008:
                // ToastUtils.showShort( "应用下载的缓存目录为空");
                Toast.makeText(this, getString(R.string.update_code_2008), Toast.LENGTH_SHORT).show();
                break;
            case 3000:
                // ToastUtils.showShort( "版本提示器异常错误");
                Toast.makeText(this, getString(R.string.update_code_3000), Toast.LENGTH_SHORT).show();
                break;
            case 3001:
                // ToastUtils.showShort( "版本提示器所在Activity页面被销毁");
                Toast.makeText(this, getString(R.string.update_code_3001), Toast.LENGTH_SHORT).show();
                break;
            case 4000:
                // ToastUtils.showShort( "新应用安装包下载失败");
                Toast.makeText(this, getString(R.string.update_code_4000), Toast.LENGTH_SHORT).show();
                break;
            case 5000:
                // ToastUtils.showShort( "apk安装失败");
                Toast.makeText(this, getString(R.string.update_code_5000), Toast.LENGTH_SHORT).show();
                break;
            case 5100:
                // ToastUtils.showShort( "未知错误");
                Toast.makeText(this, getString(R.string.update_code_5100), Toast.LENGTH_SHORT).show();
                break;
        }
    }
}