package com.example.fingerprintsy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.NonNull
import com.za.finger.ZAAPI
import com.zaz.zazjni.ZAZJni
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.finger.get/battery"
    private lateinit var zaclient: ZAAPI
    private lateinit var za: ZAZJni
    private val bgPool = Executors.newSingleThreadExecutor()
    private val FINGER_POWER_PATCH = "/sys/devices/platform/m536as_gpio_pin/usbhub4_power"
    
    private var ishavefinger = false
    private val Image = ByteArray(256 * 360)
    private var fpchar01 = ""
    private var fpchar02 = ""

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        zaclient = ZAAPI()
        za = ZAZJni()

        // Power cycle the hardware on startup
        IO_Switch(FINGER_POWER_PATCH, 0)
        Thread.sleep(100)
        IO_Switch(FINGER_POWER_PATCH, 1)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "opendev" -> {
                    // Start opening in a background thread to avoid UI freeze
                    bgPool.execute {
                        val status = zaclient.opendevice(this, 1, 4, 6, 0, 0)
                        Handler(Looper.getMainLooper()).post {
                            if (status == 1) {
                                zaclient.ZAZSetImageSize(256 * 288)
                                result.success(0)
                            } else {
                                result.error("UNAVAILABLE", "Device open failed: $status", null)
                            }
                        }
                    }
                }

                "enroll" -> {
                    val DEV_ADDR = -0x1 // 0xffffffff
                    val len = IntArray(1)
                    Arrays.fill(Image, 255.toByte())
                    
                    val ret = zaclient.ZAZGetImage(DEV_ADDR)
                    if (ret == 0) {
                        zaclient.ZAZUpImage(DEV_ADDR, Image, len)
                        if (zaclient.ZAZGenChar(DEV_ADDR, 1) == 0) {
                            val fpchar = ByteArray(512)
                            if (zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len) == 0) {
                                val map = HashMap<String, Any>()
                                map["text"] = encodeBase64(fpchar)
                                map["bytes"] = Image
                                result.success(map)
                                return@setMethodCallHandler
                            }
                        }
                    }
                    result.error("ENROLL_FAIL", "Capture failed", null)
                }

                "search" -> {
                    val strList = call.argument<List<String>>("fpcharlist") ?: emptyList()
                    val timeNum = call.argument<Number>("time")?.toLong() ?: 15000L
                    
                    bgPool.execute {
                        val DEV_ADDR = -0x1
                        val len = IntArray(1)
                        val fpchar = ByteArray(512)
                        val start = System.currentTimeMillis()
                        val searchImage = ByteArray(256 * 360)

                        var found = false
                        while (System.currentTimeMillis() - start < timeNum) {
                            if (zaclient.ZAZGetImage(DEV_ADDR) != 0) {
                                Thread.sleep(100)
                                continue
                            }
                            if (zaclient.ZAZUpImage(DEV_ADDR, searchImage, len) != 0) continue
                            if (zaclient.ZAZGenChar(DEV_ADDR, 1) != 0) continue
                            if (zaclient.ZAZUpChar(DEV_ADDR, 1, fpchar, len) != 0) continue

                            val currentFp = encodeBase64(fpchar)

                            for (i in strList.indices) {
                                val score = match2fp(currentFp, strList[i])
                                if (score >= 30) {
                                    val m = HashMap<String, Any>()
                                    m["score"] = score
                                    m["id"] = i
                                    m["bytes"] = searchImage
                                    Handler(Looper.getMainLooper()).post { result.success(m) }
                                    found = true
                                    break
                                }
                            }
                            if (found) break
                        }
                        if (!found) {
                            val m = HashMap<String, Any>()
                            m["score"] = -2
                            m["id"] = -1
                            m["bytes"] = ByteArray(256 * 360) { 255.toByte() }
                            Handler(Looper.getMainLooper()).post { result.success(m) }
                        }
                    }
                }

                "match2fp" -> {
                    // Use arguments or the stored fpchar variables
                    val ret = match2fp(fpchar01, fpchar02)
                    result.success(ret)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun IO_Switch(path: String, on: Int): Int {
        return try {
            val powerFile = File(path)
            if (!powerFile.exists()) return 0
            val writer = BufferedWriter(FileWriter(powerFile))
            writer.write(on.toString())
            writer.close()
            1
        } catch (e: IOException) {
            0
        }
    }

    private fun match2fp(fp1: String, fp2: String): Int {
        if (fp1.isEmpty() || fp2.isEmpty()) return 0
        return try {
            val b1 = Base64.decode(fp1, Base64.DEFAULT)
            val b2 = Base64.decode(fp2, Base64.DEFAULT)
            if (b1.size == 512 && b2.size == 512) za.ZAZMatch2Fp(b1, b2) else 0
        } catch (e: Exception) { 0 }
    }

    private fun encodeBase64(input: ByteArray): String {
        return Base64.encodeToString(input, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        IO_Switch(FINGER_POWER_PATCH, 0) // Turn off sensor to save battery
        bgPool.shutdownNow()
        super.onDestroy()
    }
}