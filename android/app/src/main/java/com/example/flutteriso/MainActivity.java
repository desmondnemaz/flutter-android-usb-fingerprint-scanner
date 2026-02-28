package com.example.flutteriso;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.widget.Toast;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import java.util.Arrays;
import com.za.finger.ZAAPI;

import com.zaz.zazjni.ZAZJni;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
public class MainActivity extends FlutterActivity {

    private static final String CHANNEL = "com.finger.get/battery";
    private Context mContext;

    private ZAAPI zaclient;
    private ZAZJni za;
    private int usbdevice = 0;

    String fpchar01 = "";
    String fpchar02 = "";

    byte [] a = new byte[512];
    final  String  FINGER_POWER_PATCH="/sys/devices/platform/m536as_gpio_pin/usbhub4_power";
    public int IO_Switch(String cardpowerPath,int on){
        try{

            File powerFile = new File(cardpowerPath);
            if(!powerFile.exists()){

                return 0;

            }
            BufferedWriter bufWriter = new BufferedWriter(new FileWriter(powerFile));
            bufWriter.write(Integer.toString(on));
            bufWriter.close();
            return 1;

        } catch (IOException e) {
            e.printStackTrace();  return 0;
        }

    }
    /* 放在 MainActivity 类内，方法外 */
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();
    //private static final long TIMEOUT_MS = 15_000;
    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        mContext = this;
        zaclient = new ZAAPI();
        za = new ZAZJni();
        IO_Switch(FINGER_POWER_PATCH,0);
        Sleep(100);
        IO_Switch(FINGER_POWER_PATCH,1);
        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(),CHANNEL)
                .setMethodCallHandler(
                        (call,result) -> {
                            if (call.method.equals("opendev")){
                                int batteryLevel = opendev();
                                if (batteryLevel != -1){
                                    result.success(batteryLevel);
                                }else{
                                    result.error("UNAVAILABLE", "Battery level not available.", null);
                                }
                            }
                            if (call.method.equals("search")) {
                                /* 1. 把入参改成 final 列表，方便闭包里读索引 */
                                final List<String> strList = call.argument("fpcharlist");
                                final Number timeNum = call.argument("time");
                                final long TIMEOUT_MS = timeNum != null ? timeNum.longValue() : 15_000L;

                                if (strList == null || strList.isEmpty()) {
                                    result.error("INVALID_PARAM", "strList is null or empty", null);
                                    return;
                                }

                                byte[] Image = new byte[256 * 360];
                                Future<Map<String, Object>> future = bgPool.submit(() -> {
                                    int DEV_ADDR = 0xffffffff;
                                    int[] len = new int[1];
                                    byte[] fpchar = new byte[512];
                                    long start = System.currentTimeMillis();

                                    while (System.currentTimeMillis() - start < TIMEOUT_MS) {
                                        /* 采集指纹图像 … */
                                        if (zaclient.ZAZGetImage(DEV_ADDR) != 0) {
                                            Thread.sleep(100);
                                            continue;
                                        }
                                        if (zaclient.ZAZUpImage(DEV_ADDR, Image, len) != 0) continue;
                                        if (zaclient.ZAZGenChar(DEV_ADDR, 1) != 0) continue;
                                        if (zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len) != 0) continue;

                                        String fp = encodeBase64(fpchar);

                                        /* 2. 逐条比对，一成功就把 <score,index> 带出去 */
                                        for (int i = 0; i < strList.size(); i++) {
                                            int score = match2fp(fp, strList.get(i));
                                            if (score >= 30) {
                                                Map<String, Object> m = new HashMap<>();
                                                m.put("score", score);
                                                m.put("id", i);          // 这就是搜到的那条模板的 id
                                                m.put("bytes", Image);   // 当前指纹图
                                                return m;
                                            }
                                        }
                                    }
                                    /* 3. 超时返回统一失败格式 */
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("score", -2);
                                    m.put("id", -1);
                                    Arrays.fill(Image, (byte) 0xFF);   // 白图
                                    m.put("bytes", Image);
                                    return m;
                                });

                                /* 4. 主线程收结果 */
                                try {
                                    Map<String, Object> rsp = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                                    int score = (int) rsp.get("score");
                                    int id    = (int) rsp.get("id");
                                    byte[] img= (byte[]) rsp.get("bytes");

                                    if (score >= 30) {
                                        result.success(rsp);          // 把 score + id + 原图 一起扔给 Flutter
                                    } else {
                                        result.success(rsp);          // 失败也 success，只是 score 为负，Flutter 端弹提示即可
                                    }
                                } catch (Exception e) {
                                    future.cancel(true);
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("score", -1);
                                    m.put("id", -1);
                                    byte[] white = new byte[256 * 360];
                                    Arrays.fill(white, (byte) 0xFF);
                                    m.put("bytes", white);
                                    result.success(m);
                                }
                            }
//
                            if (call.method.equals("enroll")) {
//                                String text = "hello from Java";
//                                byte[] bytes = new byte[]{0x48, 0x65, 0x6C, 0x6C, 0x6F}; // "Hello"
                                int DEV_ADDR = 0xffffffff;
                                int ret =0;
                                int [] len = new int[1];
                                for(int i=0;i<360;i++)
                                {
                                    for(int j=0;j<256;j++)
                                    {
                                        Image[i*256+j] = (byte)255;
                                    }
                                }
                                String fp="";
                                byte[] fpchar = new byte[512];
                                ret = zaclient.ZAZGetImage(DEV_ADDR);
                                if(ret == 0) {
                                    ret = zaclient.ZAZUpImage(DEV_ADDR, Image, len);
//                                    ishavefinger = true;
                                    //fpchar01 = getchar1();
                                    ret = zaclient.ZAZGenChar(DEV_ADDR, 1);
                                    if (ret == 0) {
                                        ret = zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len);
                                        if (ret == 0) {
                                            fp = encodeBase64(fpchar);
                                        }
                                    }
                                }
                                // 一次返回两个值 → 用 Map 打包
                                java.util.Map<String, Object> map = new java.util.HashMap<>();
                                map.put("text", fp);
                                map.put("bytes", Image);
                                result.success(map);
                            }
                            if (call.method.equals("getimage")){
                                byte[] rawimg  = getimage();
                                result.success(rawimg);
                            }

                            else if (call.method.equals("getchar1")){
                                fpchar01 = getchar1();
                                result.success(fpchar01);
                            }
                            else if (call.method.equals("getchar2")){
                                fpchar02 = getchar2();
                                result.success(fpchar02);
                            }
                            else if (call.method.equals("match2fp")){
                                int ret =  match2fp(fpchar01,fpchar02);
                                result.success(ret);
                            }
                            else{
                                result.notImplemented();
                            }
                        }
                );
    }
    @Override
    public void onBackPressed() {
        // ִ   ˳     
        IO_Switch(FINGER_POWER_PATCH,0);
    }
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        IO_Switch(FINGER_POWER_PATCH,0);
        return super.onKeyDown(keyCode, event);
    }


    private int opendev() {
        Runnable r = new Runnable() {
            public void run() {
//                IO_Switch(FINGER_POWER_PATCH,0);
//                Sleep(100);
//                IO_Switch(FINGER_POWER_PATCH,1);
//                Sleep(700);
                OpenDev();
            }
        };
        Thread s = new Thread(r);
        s.start();

//        int batteryLevel = -1;
//        BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
//        batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return 0;// batteryLevel;
    }

    byte[] Image = new byte[256*360];
    byte[] itemplate1 = new byte[1024];
    byte[] itemplate2 = new byte[1024];
    boolean ishavefinger = false;
    private byte[] getimage()
    {
        int DEV_ADDR = 0xffffffff;
        int ret =0;
        int [] len = new int[1];
        ishavefinger = false;
        for(int i=0;i<360;i++)
        {
            for(int j=0;j<256;j++)
            {
                Image[i*256+j] = (byte)255;
            }
        }
        ret = zaclient.ZAZGetImage(DEV_ADDR);
        if(ret == 0)
        {
            ret = zaclient.ZAZUpImage(DEV_ADDR, Image, len);
            ishavefinger = true;
            return Image;
        }
        else
        {
            return null;
        }

    }
    private String getchar1()
    {
        int DEV_ADDR = 0xffffffff;
        byte[] fpchar = new byte[512];
        int[] len = new int[1];
        String fp="";
        if(ishavefinger) {
//             IO_Switch(FINGER_POWER_PATCH,0);
            int ret = zaclient.ZAZGenChar(DEV_ADDR, 1);
            if (ret == 0) {
                ret = zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len);
                if (ret == 0) {
                    fp = encodeBase64(fpchar);
                    return fp;
                } else {
                    return "error upchar:" + ret;
                }
            } else {
                return "error genchar:" + ret;
            }
        }
        else
        {
            return "" ;
        }
    }
    private String getchar2()
    {
        int DEV_ADDR = 0xffffffff;
       // IO_Switch(FINGER_POWER_PATCH,1);
        byte[] fpchar1 = new byte[512];
        int[] len = new int[1];
        String fp1="";
        if(ishavefinger) {
            int ret = zaclient.ZAZGenChar(DEV_ADDR, 1);
            if (ret == 0) {
                ret = zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar1, len);
                Log.e("aaaaa", " aaaaaa ");
                if (ret == 0) {
                    fp1 = encodeBase64(fpchar1);
                    a = fpchar1;
                    return fp1;
                } else {
                    return "error upchar:" + ret;
                }
            } else {
                return "error genchar:" + ret;
            }
        }
        else
        {
            return "" ;
        }

    }
    private int match2fp(String fpchar1,String fpchar2)
    {
        byte[] pTemplet = new byte[512];
        byte[] pTemplet1 = new byte[512];
        pTemplet = Base64.decode(fpchar1,Base64.DEFAULT);
        pTemplet1 = Base64.decode(fpchar2,Base64.DEFAULT);
        int ret = 0;
        if(pTemplet.length == 512 && pTemplet1.length ==  512 ) {
           ret = za.ZAZMatch2Fp(pTemplet, pTemplet1);
        }
        else {
           ret =0;
        }
        return ret;
    }

    String getversion()
    {
        return "ver 1.0.0.1";
    }
    //   豸
    private void OpenDev() {

          //  isshowbmp = true;
            int   status   = zaclient.opendevice(mContext, 1, 4, 6, 0, 0);
            if (status == 1)
            {
                m_fEvent.sendMessage(m_fEvent.obtainMessage(alertshow, 0, 0, "  USB open success" ));
                zaclient.ZAZSetImageSize(256*288);
            }


            else
                m_fEvent.sendMessage(m_fEvent.obtainMessage(alertshow, 0, 0, "  USB open fail"));


    }

    private static final char[] BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    public static String encodeBase64(byte[] input) {
        StringBuilder sb = new StringBuilder();
        int length = input.length;
        int remainder = length % 3;

        // 处理每3个字节
        for (int i = 0; i < length - remainder; i += 3) {
            int block = (input[i] & 0xFF) << 16 | (input[i + 1] & 0xFF) << 8 | (input[i + 2] & 0xFF);
            sb.append(BASE64_CHARS[(block >> 18) & 0x3F]);
            sb.append(BASE64_CHARS[(block >> 12) & 0x3F]);
            sb.append(BASE64_CHARS[(block >> 6) & 0x3F]);
            sb.append(BASE64_CHARS[block & 0x3F]);
        }

        // 处理剩余的字节
        if (remainder == 1) {
            int block = input[length - 1] & 0xFF;
            sb.append(BASE64_CHARS[(block >> 2) & 0x3F]);
            sb.append(BASE64_CHARS[(block << 4) & 0x3F]);
            sb.append("==");
        } else if (remainder == 2) {
            int block = (input[length - 2] & 0xFF) << 8 | (input[length - 1] & 0xFF);
            sb.append(BASE64_CHARS[(block >> 10) & 0x3F]);
            sb.append(BASE64_CHARS[(block >> 4) & 0x3F]);
            sb.append(BASE64_CHARS[(block << 2) & 0x3F]);
            sb.append("=");
        }

        return sb.toString();
    }


    public static final int msgshow = 101;
    public static final int bmpshow = 102;
    public static final int alertshow = 103;
    public static final int enbtn = 104;
    public static final int disbtn = 105;

    private final Handler m_fEvent = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String temp  = null;
            switch (msg.what) {
                case msgshow:
                  //  mHintTv.setText(msg.obj.toString());
                    break;
                case bmpshow:
                  //  ShowFingerBitmap((byte[])(msg.obj),256,(int)(msg.arg1)/256);
                    break;
                case alertshow:
                    alert(msg.obj.toString());
                    break;
                case enbtn:

                   // mHintTv.setText(msg.obj.toString());
                    break;
            }
        }
    };
    void alert(String str)
    {
        new AlertDialog.Builder(mContext)
                .setTitle("message" )
                .setMessage(str )
                .setPositiveButton("ok" , null )
                .show();
    }
    void showToast( String text) {
        Toast mToast = Toast.makeText(mContext, text, Toast.LENGTH_SHORT);
        mToast.show();
    }
    private void Sleep(int times)
    {
        try {
            Thread.sleep(times);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    @Override
    protected void onDestroy() {
        bgPool.shutdownNow();
        super.onDestroy();
    }
//if (call.method.equals("sreach")) {
//                                List<String> strList = call.argument("fpcharlist");
//                                Number timeNum = call.argument("time");   // 先当 Number 拿
//                                long TIMEOUT_MS = timeNum != null ? timeNum.longValue() : 15_000L;
//                                if (strList == null || strList.isEmpty()) {
//                                    result.error("INVALID_PARAM", "strList is null or empty", null);
//                                    return;
//                                }
//                                byte[] Image = new byte[256 * 360];
//                                /* 提交后台任务 */
//                                Future<Integer> future = bgPool.submit(() -> {
//                                    int DEV_ADDR = 0xffffffff;
//                                    int[] len = new int[1];
//                                    byte[] fpchar = new byte[512];
//                                    long start = System.currentTimeMillis();
//                                    for(int i=0;i<360;i++)
//                                    {
//                                        for(int j=0;j<256;j++)
//                                        {
//                                            Image[i*256+j] = (byte)255;
//                                        }
//                                    }
//                                    while (System.currentTimeMillis() - start < TIMEOUT_MS) {
//                                        /* 1. 循环采集 */
//                                        int ret = zaclient.ZAZGetImage(DEV_ADDR);
//                                        if (ret != 0) {                // 没按手指，稍等再采
//                                            try { Thread.sleep(100); } catch (InterruptedException ignore) {}
//                                            continue;
//                                        }
//                                        ret = zaclient.ZAZUpImage(DEV_ADDR, Image, len);
//                                        if(ret!=0){
//                                            continue;
//                                        }
//                                        String fp ="";
//                                        ret = zaclient.ZAZGenChar(DEV_ADDR, 1);
//                                        if (ret == 0) {
//                                            ret = zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len);
//                                            if (ret == 0) {
//                                                fp = encodeBase64(fpchar);
//                                            }
//                                        }
//                                        for (String tpl : strList) {
//                                            Log.e("tpl", tpl);
//                                            int score = match2fp(fp, tpl);
//                                            if (score >= 30) {
//                                                return score;          // 立即成功
//                                            }else {
//                                                for(int i=0;i<360;i++)
//                                                {
//                                                    for(int j=0;j<256;j++)
//                                                    {
//                                                        Image[i*256+j] = (byte)255;
//                                                    }
//                                                }
//                                                return score;
//                                            }
//                                        }
//                                    }
//                                    return -2;                         // 15 s 超时
//                                });
//                                /* 3. 等待结果（带超时） */
//                                try {
//                                    Integer score = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
//                                    java.util.Map<String, Object> map = new java.util.HashMap<>();
//                                    map.put("Integer", score);
//                                    map.put("bytes", Image);
//                                    if (score >= 30) {
//                                        result.success(score);
//                                    } else if (score == -2) {
//                                        result.error("TIMEOUT", "no match >= 40");
//                                    } else {
//                                        result.error("MATCH_FAIL", "no template matched");
//                                    }
//                                } catch (TimeoutException e) {
//                                    future.cancel(true);
//                                    result.error("TIMEOUT", "operation timeout ", null);
//                                } catch (Exception e) {
//                                    result.error("ERROR", e.getMessage(), null);
//                                }
//                            }
}