package com.example.hovercraftusb

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var angleLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var angleBar: SeekBar
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var speed = 0
    private var angle = 90
    private val permissionAction = "com.example.hovercraftusb.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null)
                        connectDevice(device)
                    else setStatus("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnect()
                    setStatus("USB disconnected")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        findUsbDevice()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        fun label(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(0xFF172B4D.toInt())
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(label("Hovercraft USB Controller", 24f, true))
        root.addView(label("USB Serial • 9600 baud", 14f))
        status = label("Searching for USB device…", 16f, true).apply { setPadding(0, 22, 0, 22) }
        root.addView(status)
        speedLabel = label("Thrust speed: 0 / 255", 18f, true)
        root.addView(speedLabel)
        speedBar = SeekBar(this).apply { max = 255; progress = 0 }
        root.addView(speedBar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        angleLabel = label("Angle value: 90°", 18f, true).apply { setPadding(0, 22, 0, 0) }
        root.addView(angleLabel)
        angleBar = SeekBar(this).apply { max = 180; progress = 90 }
        root.addView(angleBar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                speed = value; speedLabel.text = "Thrust speed: $speed / 255"
                if (fromUser) sendCommand()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { sendCommand() }
        })
        angleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                angle = value; angleLabel.text = "Angle value: $angle°"
                if (fromUser) sendCommand()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { sendCommand() }
        })
        root.addView(Button(this).apply {
            text = "Rescan / Connect USB"
            setOnClickListener { findUsbDevice() }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "EMERGENCY STOP"; textSize = 18f
            setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFFC62828.toInt())
            setOnClickListener {
                speed = 0; angle = 90; speedBar.progress = 0; angleBar.progress = 90
                sendCommand()
                Toast.makeText(this@MainActivity, "Stop command sent if connected", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, 64)
        root.addView(label("LED test only. Do not connect motors without a suitable motor driver and power supply.", 13f).apply { setPadding(0, 20, 0, 0) })
        setContentView(root)
    }

    private fun findUsbDevice() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) { setStatus("No compatible USB serial device found"); return }
        val device = drivers[0].device
        if (usbManager.hasPermission(device)) connectDevice(device)
        else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags)
            usbManager.requestPermission(device, pi)
            setStatus("Waiting for USB permission…")
        }
    }

    private fun connectDevice(device: UsbDevice) {
        try {
            disconnect()
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .firstOrNull { it.device.deviceId == device.deviceId } ?: throw IOException("No serial driver")
            val connection = usbManager.openDevice(driver.device) ?: throw IOException("Cannot open USB device")
            val serialPort = driver.ports[0]
            serialPort.open(connection)
            serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = serialPort
            ioManager = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {}
                override fun onRunError(e: Exception) { runOnUiThread { setStatus("Serial error: ${e.message}") } }
            }).also { it.start() }
            setStatus("Connected to ${driver.device.deviceName} at 9600")
            sendCommand()
        } catch (e: Exception) {
            disconnect(); setStatus("Connection failed: ${e.message}")
        }
    }

    private fun sendCommand() {
        val p = port ?: return
        try { p.write("<$speed,$angle>\n".toByteArray(Charsets.US_ASCII), 1000) }
        catch (e: Exception) { setStatus("Send failed: ${e.message}") }
    }
    private fun setStatus(message: String) { if (::status.isInitialized) status.text = "Status: $message" }
    private fun disconnect() {
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        port = null
    }
    override fun onDestroy() {
        disconnect()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
