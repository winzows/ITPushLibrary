package com.innotech.innotechpush.sdk;

import android.content.Context;
import android.os.Handler;
import android.text.TextUtils;

import com.innotech.innotechpush.InnotechPushMethod;
import com.innotech.innotechpush.callback.RequestCallback;
import com.innotech.innotechpush.callback.SocketSendCallback;
import com.innotech.innotechpush.config.LogCode;
import com.innotech.innotechpush.config.PushConstant;
import com.innotech.innotechpush.db.DbUtils;
import com.innotech.innotechpush.utils.CommonUtils;
import com.innotech.innotechpush.utils.DataAnalysis;
import com.innotech.innotechpush.utils.LogUtils;
import com.innotech.innotechpush.utils.NetWorkUtils;
import com.innotech.innotechpush.utils.SignUtils;
import com.innotech.innotechpush.utils.TokenUtils;
import com.innotech.innotechpush.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Random;

public class SocketManager {
    private static SocketManager instance;
    private static Context context;
    private static final int STATE_CONNECTED = 1;
    private static final int STATE_UNCONNECTED = 0;
    private Socket mSocket;
    //socket输入流
    private InputStream mInputStream;
    //socket输出流
    private DataOutputStream mDataOutputStream;
    //read线程
    private Thread readThread;
    //write线程
    private Thread writeThread;
    //是否正在重连
    private boolean isReconnecting;

    //长连接发送数据用的线程
    private Thread sendThread;
    private Runnable sendRunnable;

    public static SocketManager getInstance(Context ct) {
        if (instance == null) {
            synchronized (SocketManager.class) {
                if (instance == null) {
                    context = ct;
                    instance = new SocketManager();
                }
            }
        }
        return instance;
    }

    private SocketManager() {
        isReconnecting = false;
    }

    /**
     * 初始化长连接
     */
    public synchronized void initSocket() {
        try {
            getSocketAddr(new RequestCallback() {
                @Override
                public void onSuccess(String hostAndPort) {
                    String[] array = hostAndPort.split(":");
                    try {
                        connectWithHostAndPort(array[0].trim(), Integer.parseInt(array[1].trim()));
                    } catch (JSONException e) {
                        isReconnecting = false;
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFail(String msg) {
                    isReconnecting = false;
                    LogUtils.e(context, msg);
                    DbUtils.addClientLog(context, LogCode.LOG_DATA_API, "获取长连接地址失败：" + msg);
                    try {
                        Thread.sleep(5000);
                        reConnect();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (JSONException e) {
            isReconnecting = false;
            LogUtils.e(context, "获取socket信息json参数有误" + e.getMessage());
            DbUtils.addClientLog(context, LogCode.LOG_EX_JSON, "获取socket信息json参数有误" + e.getMessage());
        }
    }

    /**
     * 获取长连接地址信息
     */
    private void getSocketAddr(RequestCallback callback) throws JSONException {
        Integer appId = CommonUtils.getMetaDataInteger(context, PushConstant.INNOTECH_APP_ID);
        String appKey = CommonUtils.getMetaDataString(context, PushConstant.INNOTECH_APP_KEY);
        String guid = TokenUtils.getGuid(context);
        if (TextUtils.isEmpty(guid)) {
            LogUtils.e(context, "guid不能为空");
            isReconnecting = false;
            return;
        }
        JSONObject object = new JSONObject();
        object.put("app_id", appId);
        object.put("app_key", appKey);
        object.put("guid", guid);
        String json = object.toString();
        String sign = SignUtils.sign("POST", NetWorkUtils.PATH_SOCKET_ADDR, json);
        NetWorkUtils.sendPostRequest(context, NetWorkUtils.URL_SOCKET_ADDR, json, sign, callback);
    }

    /**
     * 建立长连接
     */
    private void connectWithHostAndPort(final String host, final int port) throws JSONException {
        if (!TextUtils.isEmpty(host) && port != 0) {
            final Integer appId = CommonUtils.getMetaDataInteger(context, PushConstant.INNOTECH_APP_ID);
            final String appKey = CommonUtils.getMetaDataString(context, PushConstant.INNOTECH_APP_KEY);
            final String guid = TokenUtils.getGuid(context);
            if (TextUtils.isEmpty(guid)) {
                LogUtils.e(context, "guid不能为空");
                isReconnecting = false;
                return;
            }
            try {
                mSocket = new Socket(host, port);
                LogUtils.e(context, "与服务器(" + host + ":" + port + ")连接成功");
                mInputStream = mSocket.getInputStream();
//                mDataOutputStream = new DataOutputStream(mSocket.getOutputStream());
                isReconnecting = false;
                //登录
                loginCmd(guid, appId, appKey);
                readData();
            } catch (Exception e) {
                isReconnecting = false;
                LogUtils.e(context, "Exception异常：" + e.getMessage());
                try {
                    Thread.sleep(5000);
                    reConnect();
                } catch (InterruptedException e1) {
                    e.printStackTrace();
                }
                DbUtils.addClientLog(context, LogCode.LOG_EX_SOCKET, "Exception异常：" + e.getMessage());
            }
        }
    }

    /**
     * 读取数据
     */
    private void readData() {
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
            readThread = null;
        }
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        byte[] lenB = readByLen(16);
                        if (lenB == null) break;
                        int len = getLenByData(lenB);
                        long requestId = getRequestIDByData(lenB);
                        int command = getCommandByData(lenB);
                        String json = "";
                        if (len - 12 > 0) {
                            byte[] lenJ = readByLen(len - 12);
                            if (lenJ == null) break;
                            json = new String(lenJ);
                        }
                        switch (command) {
                            case 1://登录成功（LoginRespCmd）
                                LogUtils.e(context, "登录成功");
                                try {
                                    if (!TextUtils.isEmpty(json) && !"null".equals(json)) {
                                        ArrayList<String> list = new ArrayList<>();
                                        JSONArray array = new JSONArray(json);
                                        for (int i = 0; i < array.length(); i++) {
                                            JSONObject object = array.getJSONObject(i);
                                            PushMessage pushMessage = (PushMessage) DataAnalysis.jsonToT(PushMessage.class.getName(), object.toString());
                                            if (pushMessage != null) {
                                                pushMessage.setOffLineMsg(true);
                                                PushMessageManager.getInstance(context).setNewMessage(pushMessage);
                                                list.add(pushMessage.getMsg_id());
                                            }
                                        }
                                        if (list.size() > 0) {
                                            if (CommonUtils.isXiaomiDevice()
                                                    || CommonUtils.isMIUI()
                                                    || CommonUtils.isMeizuDevice()
                                                    || (Utils.isHuaweiDevice() && PushConstant.hasHuawei && HuaweiSDK.isUpEMUI41())
//                                        || (Utils.isOPPO() && PushConstant.hasOppo && com.coloros.mcssdk.PushManager.isSupportPush(context))
                                                    ) {
                                                ackCmd(list, 101);
                                            } else {
                                                ackCmd(list, 1001);
                                            }
                                            DbUtils.addClientLog(context, LogCode.LOG_DATA_COMMON, "收到服务器推送消息(offlinemsg)：" + list.toString());
                                        }
                                    }
                                    //长连接回执之前丢失的回执
                                    InnotechPushMethod.uploadSocketAck(context);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                                break;
                            case 4://服务器推送消息（ForwardCmd）
                                //处理推送消息
                                if (!TextUtils.isEmpty(json) && !"null".equals(json)) {
                                    PushMessage pushMessage = (PushMessage) DataAnalysis.jsonToT(PushMessage.class.getName(), json);
                                    if (pushMessage != null) {
                                        PushMessageManager.getInstance(context).setNewMessage(pushMessage);
                                        ArrayList<String> list = new ArrayList<>();
                                        list.add(pushMessage.getMsg_id());
                                        if (CommonUtils.isXiaomiDevice()
                                                || CommonUtils.isMIUI()
                                                || CommonUtils.isMeizuDevice()
                                                || (Utils.isHuaweiDevice() && PushConstant.hasHuawei && HuaweiSDK.isUpEMUI41())
//                                        || (Utils.isOPPO() && PushConstant.hasOppo && com.coloros.mcssdk.PushManager.isSupportPush(context))
                                                ) {
                                            ackCmd(list, 101);
                                        } else {
                                            ackCmd(list, 1);
                                        }
                                        DbUtils.addClientLog(context, LogCode.LOG_DATA_COMMON, "收到服务器推送消息：" + pushMessage.getMsg_id());
                                    }
                                }
                                break;
                            case 7://ack回值成功（AckRespCmd）
                                LogUtils.e(context, "ack回值成功");
                                break;
                            case 10://心跳回包（HeartBeatRespCmd）
                                LogUtils.e(context, "心跳回包成功");
                                break;
                            default:
                                break;
                        }
                    }
                    //while循环中阻塞读，当服务器断开连接后，读操作就会返回-1，从而跳出循环执行后续的代码。
                    //服务器断开了，需要重连
                    LogUtils.e(context, "服务器断开了，正在尝试重连...");
                    try {
                        Thread.sleep(5000);
                        reConnect();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } catch (IOException e) {
                    LogUtils.e(context, "IOException异常：" + e.getMessage());
                    try {
                        Thread.sleep(5000);
                        reConnect();
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    DbUtils.addClientLog(context, LogCode.LOG_EX_SOCKET, "IOException异常：" + e.getMessage());
                }
            }
        });
        readThread.start();
    }

    /**
     * 是否连接中
     */
    private boolean isConnecting() {
        if (mSocket != null && mSocket.isConnected() && !mSocket.isClosed() && !mSocket.isInputShutdown()) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 客户端断掉了的重连
     * isConnecting()只能判断客户端是否处于正常连接状态
     */
    public synchronized void reConnect() {
        if (isReconnecting) {
            return;
        }
        isReconnecting = true;
        sendData("", 9, new SocketSendCallback() {
            @Override
            public void onResult(boolean result) {
                if (!result) {
                    try {
                        if (mSocket != null) {
                            mSocket.close();
                        }
                        SocketManager.getInstance(context).initSocket();
                        LogUtils.e(context, "正在重连...");
                        DbUtils.addClientLog(context, LogCode.LOG_DATA_COMMON, "正在重连...");
                    } catch (IOException e) {
                        isReconnecting = false;
                        LogUtils.e(context, "socket close异常...");
                        DbUtils.addClientLog(context, LogCode.LOG_EX_IO, "socket close异常..." + e.getMessage());
                    }
                } else {
                    isReconnecting = false;
                }
            }
        });
    }

    /**
     * 登录
     */
    public void loginCmd(String guid, int appID, String appKey) {
        try {
            JSONObject object = new JSONObject();
            object.put("guid", guid);
            object.put("app_id", appID);
            object.put("app_key", appKey);
            object.put("version", PushConstant.INNOTECH_PUSH_VERSION);
            sendData(object.toString(), 0);
            LogUtils.e(context, "发送登录指令：" + object.toString());
        } catch (JSONException e) {
            LogUtils.e(context, "发送登录指令时，出现异常。" + e.getMessage());
            DbUtils.addClientLog(context, LogCode.LOG_EX_JSON, "发送登录指令时，出现异常。" + guid + ";" + appID + ";" + appKey);
        }
    }

    /**
     * 发送心跳信息
     */
    public void sendHeartData() {
        sendData("", 9);
        LogUtils.e(context, "发送心跳指令");
    }

    /**
     * 收到推送信息后向服务端发送回值
     *
     * @param type：1、到达 2、展示 3、点击
     */
    public void ackCmd(ArrayList<String> msgList, int type) {
        try {
            JSONObject object = new JSONObject();
            JSONArray array = new JSONArray();
            for (String msgID : msgList) {
                array.put(msgID);
            }
            object.put("msg_ids", array);
            object.put("type", type);
            sendData(object.toString(), 6);
            LogUtils.e(context, "发送ack指令：" + object.toString());
            //客户端回执
            JSONArray paramArray = new JSONArray();
            paramArray.put(object);
            InnotechPushMethod.clientMsgNotify(context, paramArray, 0);
        } catch (JSONException e) {
            LogUtils.e(context, "发送ack指令时，出现异常。" + e.getMessage());
            DbUtils.addClientLog(context, LogCode.LOG_EX_JSON, "发送ack指令时，出现异常。" + msgList.toString());
        }
    }

    /**
     * 向服务端发消息
     */
    public void sendData(String json, int cmd) {
        sendData(json, cmd, null);
    }

    public void sendData(final String json, final int cmd, final SocketSendCallback callback) {
        if (isConnecting()) {
            LogUtils.e(context, "socket状态为：连接中...");
            sendRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        DataOutputStream dos = new DataOutputStream(mSocket.getOutputStream());
                        //4字节剩余包长度
                        byte[] len = CommonUtils.big_intToByte(!TextUtils.isEmpty(json) ? json.length() + 12 : 12, 4);
                        byte[] requestsID = getRequestID();
                        byte[] command = CommonUtils.big_intToByte(cmd, 4);
                        byte[] jsonb = json.getBytes();
                        byte[] data = new byte[len.length + requestsID.length + command.length + jsonb.length];
                        System.arraycopy(len, 0, data, 0, len.length);
                        System.arraycopy(requestsID, 0, data, len.length, requestsID.length);
                        System.arraycopy(command, 0, data, len.length + requestsID.length, command.length);
                        System.arraycopy(jsonb, 0, data, len.length + requestsID.length + command.length, jsonb.length);
                        dos.write(data);
                        if (callback != null) {
                            callback.onResult(true);
                        }
                    } catch (IOException e) {
                        LogUtils.e(context, "socket发送信息失败，cmd：" + cmd + "，异常信息：" + e.getMessage());
                        //存放本次发送的回执
                        if (cmd == 6) {
                            DbUtils.addSocketAck(context, json, cmd);
                        }
                        if (callback != null) {
                            callback.onResult(false);
                        } else {
                            reConnect();
                        }
                        DbUtils.addClientLog(context, LogCode.LOG_EX_IO, "socket发送信息失败，cmd：" + cmd + "，异常信息：" + e.getMessage());
                    }
                }
            };
            sendThread = new Thread(sendRunnable);
            sendThread.start();
        } else {
            LogUtils.e(context, "socket状态为：已断开连接...");
            //存放本次发送的回执
            if (cmd == 6) {
                DbUtils.addSocketAck(context, json, cmd);
            }
            if (callback != null) {
                callback.onResult(false);
            } else {
                reConnect();
            }
            DbUtils.addClientLog(context, LogCode.LOG_DATA_COMMON, "socket状态为：已断开连接...");
        }
    }

    /**
     * 生成8字节随机数
     *
     * @return 8字节随机数
     */
    private byte[] getRequestID() {
        byte[] b = new byte[8];
        Random random = new Random();
        random.nextBytes(b);
        return b;
    }

    /**
     * 获取服务端回包的信息
     * 4字节剩余包长
     */
    private int getLenByData(byte[] data) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = data[i];
        }
        return CommonUtils.big_bytesToInt(bytes);
    }

    /**
     * 获取服务端回包的信息
     * 8字节requestID
     */
    private long getRequestIDByData(byte[] data) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = data[i + 4];
        }
        return CommonUtils.longFrom8Bytes(bytes, 0, false);
    }

    /**
     * 获取服务端回包的信息
     * 3字节命令的值
     */
    private int getCommandByData(byte[] data) {
        byte[] bytes = new byte[4];
        bytes[0] = 0;
        for (int i = 1; i < 4; i++) {
            bytes[i] = data[i + 12];
        }
        return CommonUtils.big_bytesToInt(bytes);
    }

    /**
     * 读取长度为len的字符数组
     *
     * @param len：长度
     * @return 字符数组
     */
    private byte[] readByLen(int len) throws IOException {
        byte[] result = new byte[len];
        boolean isRead = true;
        int readLen = 0;
        while (isRead) {
            int curReadLen = 0;
            if (result.length - readLen < 1024) {
                curReadLen = mInputStream.read(result, readLen, result.length - readLen);
            } else {
                curReadLen = mInputStream.read(result, readLen, 1024);
            }
            readLen += curReadLen;
//            LogUtils.e(context, "readLen：" + readLen);
            LogUtils.e(context, "curReadLen：" + curReadLen);
            if (readLen == len) isRead = false;
            if (curReadLen == -1) {
                result = null;
                break;
            }
        }
        return result;
    }

}
