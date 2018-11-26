package com.innotech.innotechpush.bean;

import android.text.TextUtils;

import com.innotech.innotechpush.utils.CommonUtils;

import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 长连接写的数据结构
 */
public class WriteData {
    //4字节剩余包长度
    private byte[] len;
    //8字节随机数
    private byte[] requestsID;
    //指令
    private byte[] command;
    //内容
    private byte[] jsonb;
    //合并上述四项
    private byte[] data;
    //写的结果
    private LinkedBlockingQueue<Boolean> resultQueue;

    public WriteData(Integer cmd, String json) {
        resultQueue = new LinkedBlockingQueue<>();
        this.len = CommonUtils.big_intToByte(!TextUtils.isEmpty(json) ? json.length() + 12 : 12, 4);
        this.requestsID = getRequestID();
        this.command = CommonUtils.big_intToByte(cmd, 4);
        this.jsonb = json.getBytes();
        this.data = new byte[len.length + requestsID.length + command.length + jsonb.length];
        System.arraycopy(len, 0, data, 0, len.length);
        System.arraycopy(requestsID, 0, data, len.length, requestsID.length);
        System.arraycopy(command, 0, data, len.length + requestsID.length, command.length);
        System.arraycopy(jsonb, 0, data, len.length + requestsID.length + command.length, jsonb.length);
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

    public LinkedBlockingQueue<Boolean> getResultQueue() {
        return resultQueue;
    }

    public byte[] getData() {
        return data;
    }
}
