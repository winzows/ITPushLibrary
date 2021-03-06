package com.innotech.innotechpush;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.innotech.innotechpush.bean.UserInfoModel;
import com.innotech.innotechpush.config.BroadcastConstant;
import com.innotech.innotechpush.config.PushConstant;
import com.innotech.innotechpush.receiver.PushReciver;
import com.innotech.innotechpush.receiver.SocketClientRevicer;
import com.innotech.innotechpush.sdk.HuaweiSDK;
import com.innotech.innotechpush.sdk.MeizuSDK;
import com.innotech.innotechpush.sdk.MiSDK;
import com.innotech.innotechpush.sdk.PushMessageReceiver;
import com.innotech.innotechpush.sdk.PushReceiver;
import com.innotech.innotechpush.sdk.SocketClientService;
import com.innotech.innotechpush.service.OppoPushCallback;
import com.innotech.innotechpush.service.PushIntentService;
import com.innotech.innotechpush.service.PushService;
import com.innotech.innotechpush.utils.CommonUtils;
import com.innotech.innotechpush.utils.LogUtils;
import com.innotech.innotechpush.utils.Utils;
import com.meizu.cloud.pushsdk.PushManager;
import com.orm.SugarContext;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SDK核心类，SDK初始化用到
 * 单例类
 */

public class InnotechPushManager {
    private static InnotechPushManager mInnotechPushManager = null;
    private Application application;
    private static PushReciver mPushReciver;
    /**
     * 个推和集团长连接做幂等时需要加锁，防止两个回调相隔时间较近或同时到达。
     */
    private static Lock idempotentLock;
    /**
     * 通知栏图标
     */
    public static int pushIcon = R.mipmap.ic_launcher;

    public InnotechPushManager() {

    }

    public static InnotechPushManager getInstance() {
        if (mInnotechPushManager == null) {
            mInnotechPushManager = new InnotechPushManager();
        }
        return mInnotechPushManager;
    }

    /**
     * 初始化推送SDK
     *
     * @param application
     */
    public void initPushSDK(final Application application) {
        this.application = application;
        String processName = getProcessName(application, android.os.Process.myPid());
        LogUtils.e(application, "当前进程名字：" + processName);
        //动态注册广播
        if (CommonUtils.isMainProcess(application.getApplicationContext())) {
            registerMainReceiver(application.getApplicationContext());
        } else if (CommonUtils.isPushProcess(application.getApplicationContext())) {
            registerPushReceiver(application.getApplicationContext());
        }

        SugarContext.init(application);
        UserInfoModel.getInstance().init(application.getApplicationContext());

        if (CommonUtils.isMainProcess(application.getApplicationContext())) {
            if (Utils.isXiaomiDevice() || Utils.isMIUI()) {
                new MiSDK(application.getApplicationContext());
            } else if (Utils.isMeizuDevice()) {//魅族设备时，开启魅族推送
                new MeizuSDK(application.getApplicationContext());
            } else if (Utils.isHuaweiDevice() && PushConstant.hasHuawei && HuaweiSDK.isUpEMUI41()) {//华为设备时，开启华为推送
                new HuaweiSDK(application);
            }
//            else if (Utils.isOPPO() && PushConstant.hasOppo && com.coloros.mcssdk.PushManager.isSupportPush(application.getApplicationContext())) {//oppo设备时，开启oppo推送
//                String appKey = Utils.getMetaDataString(application, "OPPO_APP_KEY");
//                String appSecret = Utils.getMetaDataString(application, "OPPO_APP_SECRET");
//                com.coloros.mcssdk.PushManager.getInstance().register(application.getApplicationContext(), appKey, appSecret, new OppoPushCallback(application));
//            }
            else { //其他设备时，开启个推推送和socket长连接
                if (Utils.isOPPO() && PushConstant.hasOppo && com.coloros.mcssdk.PushManager.isSupportPush(application.getApplicationContext())) {//oppo设备时，开启oppo推送
                    String appKey = Utils.getMetaDataString(application, "OPPO_APP_KEY");
                    String appSecret = Utils.getMetaDataString(application, "OPPO_APP_SECRET");
                    com.coloros.mcssdk.PushManager.getInstance().register(application.getApplicationContext(), appKey, appSecret, new OppoPushCallback(application));
                }
                initGeTuiPush();
            }
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
//                application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
//                    @Override
//                    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityCreated" + activity.getLocalClassName());
//                    }
//
//                    @Override
//                    public void onActivityStarted(Activity activity) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityStarted");
//                    }
//
//                    @Override
//                    public void onActivityResumed(Activity activity) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityResumed");
//                    }
//
//                    @Override
//                    public void onActivityPaused(Activity activity) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityPaused");
//                    }
//
//                    @Override
//                    public void onActivityStopped(Activity activity) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityStopped");
//                    }
//
//                    @Override
//                    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
//                        LogUtils.e(application.getApplicationContext(), "onActivitySaveInstanceState");
//                    }
//
//                    @Override
//                    public void onActivityDestroyed(Activity activity) {
//                        LogUtils.e(application.getApplicationContext(), "onActivityDestroyed");
//                    }
//                });
//            }
        }
    }

    public void initSocketPush() {
        application.getApplicationContext().startService(new Intent(application.getApplicationContext(), SocketClientService.class));
    }

    /**
     * 初始化并开启个推推送
     */
    private void initGeTuiPush() {
        LogUtils.e(application.getApplicationContext(), LogUtils.TAG_GETUI + "call initGeTuiPush()");
        com.igexin.sdk.PushManager.getInstance().initialize(application.getApplicationContext(), PushService.class);
        // com.getui.demo.DemoIntentService 为第三⽅方⾃自定义的推送服务事件接收类
        com.igexin.sdk.PushManager.getInstance().registerPushIntentService(application.getApplicationContext(), PushIntentService.class);
    }

    public void setPushRevicer(PushReciver mPushReciver) {
        this.mPushReciver = mPushReciver;
    }

    public static PushReciver getPushReciver() {
        return mPushReciver;
    }

    public static void innotechPushReciverIsNull(Context context) {
        LogUtils.e(context, "InnotechPushReciver is null!");
    }

    /**
     * 获得幂等锁
     *
     * @return
     */
    public static Lock getIdempotentLock() {
        if (idempotentLock == null) {
            idempotentLock = new ReentrantLock();
        }
        return idempotentLock;
    }

    /**
     * 是否
     *
     * @param cxt
     * @param pid
     * @return
     */
    public static String getProcessName(Context cxt, int pid) {
        ActivityManager am = (ActivityManager) cxt.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningApps = am.getRunningAppProcesses();
        if (runningApps == null) {
            return null;
        }
        for (ActivityManager.RunningAppProcessInfo procInfo : runningApps) {
            if (procInfo.pid == pid) {
                return procInfo.processName;
            }
        }
        return null;
    }

    //
    public void terminate() {
        SugarContext.terminate();
    }

    //动态注册广播
    public void registerPushReceiver(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BroadcastConstant.RECEIVE_MESSAGE);
        filter.addAction(BroadcastConstant.MESSAGE_CLICK);
        filter.addAction(BroadcastConstant.ACTION_FRESH_PUSH + context.getPackageName());
        context.registerReceiver(new PushReceiver(), filter);
    }

    //
    public void registerMainReceiver(Context context) {
        IntentFilter filter1 = new IntentFilter();
        filter1.addAction(BroadcastConstant.RECEIVE_MESSAGE);
        filter1.addAction(BroadcastConstant.MESSAGE_CLICK);
        filter1.addAction(BroadcastConstant.ERROR);
        context.registerReceiver(new SocketClientRevicer(), filter1);
    }

}
