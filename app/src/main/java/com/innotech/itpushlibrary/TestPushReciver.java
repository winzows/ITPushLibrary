package com.innotech.itpushlibrary;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.innotech.innotechpush.InnotechPushMethod;
import com.innotech.innotechpush.bean.InnotechMessage;
import com.innotech.innotechpush.receiver.PushReciver;
import com.innotech.innotechpush.utils.LogUtils;

/**
 * Created by admin on 2018/4/3.
 */

public class TestPushReciver extends PushReciver {

    public static Handler handler;

    @Override
    public void onReceiveGuid(Context context, String guid) {
        super.onReceiveGuid(context, guid);
        Message msg = new Message();
        msg.what = 2;
        Bundle b = new Bundle();// 存放数据
        b.putString("guid", guid);
        msg.setData(b);
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }

    @Override
    public void onReceivePassThroughMessage(Context context, InnotechMessage mPushMessage) {
        // super.onReceivePassThroughMessage(context, miPushMessage);
        showMessageInfoforTest(context, "onReceivePassThroughMessage", mPushMessage);
        Message msg = new Message();
        msg.what = 3;
        Bundle b = new Bundle();// 存放数据
        b.putString("custom", mPushMessage.getCustom());
        msg.setData(b);
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }

    @Override
    public void onNotificationMessageClicked(Context context, InnotechMessage mPushMessage) {
        // super.onNotificationMessageClicked(context, miPushMessage);
        showMessageInfoforTest(context, "onNotificationMessageClicked", mPushMessage);
        Intent intent = new Intent(context, Main2Activity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ComponentName comp = ComponentName
                .unflattenFromString("com.innotech.itpushlibrary/.Main2Activity");
        intent.setComponent(comp);
        context.startActivity(intent);
    }

    @Override
    public void onNotificationMessageArrived(Context context, InnotechMessage mPushMessage) {
        //  super.onNotificationMessageArrived(context, miPushMessage);
        showMessageInfoforTest(context, "onNotificationMessageArrived", mPushMessage);
    }


    private void showMessageInfoforTest(Context context, String metodName, InnotechMessage mPushMessage) {
        String contentStr = mPushMessage.getContent();
        String titleStr = mPushMessage.getTitle();
        String data = mPushMessage.getData();
        String custom = mPushMessage.getCustom();
        LogUtils.d(context, "custom:" + custom);
        String dataInfo = " ==app== contentStr:" + contentStr + " titleStr:" + titleStr + " contentStr:" + contentStr + " data:" + data + " custom:" + custom;
        LogUtils.d(context, "metodName:" + metodName + dataInfo);
    }
}
