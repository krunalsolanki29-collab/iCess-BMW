package com.example.bmwcarcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Minimal replacement controller for the "iCess" BMW X6 1:14 Bluetooth toy car.
 *
 * Protocol reverse-engineered from the original iCess/iCess-plus APK
 * (package com.weccan.icess, model key "WECCAN_CAR_is600"):
 *
 *  - Classic Bluetooth SPP, UUID 00001101-0000-1000-8000-00805F9B34FB
 *  - Connects to an already-PAIRED device (no live discovery needed,
 *    so we only need BLUETOOTH_CONNECT, never location/BLUETOOTH_SCAN)
 *  - 5 logical protocol bytes, built in this field order:
 *      [0] checkNum   = (pitch + yaw + 1 + trimer) & 0xFF
 *      [1] trimerByte = (trimer & 0x0F) << 4
 *      [2] pitchByte  = throttle magnitude, 0-255
 *      [3] yawByte    = steering magnitude, 0-255
 *      [4] flagByte   = (yawFlag & 0x3) | ((pitchFlag & 0x3) << 2)
 *          pitchFlag: 0 = stop, 1 = forward, 2 = backward
 *          yawFlag:   0 = straight, 1 = left, 2 = right
 *  - The 5 bytes are reversed (byte[4] first ... byte[0] last), each
 *    hex-encoded as 2 lowercase ASCII chars, and prefixed with the
 *    literal 2-character header "0p" (sent as raw ASCII, NOT hex).
 *  - Final packet is always exactly 12 ASCII bytes.
 */
class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var deviceSpinner: Spinner
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    // Continuous-send loop while a direction button is held (mirrors how the
    // original app's game loop kept transmitting at a fixed interval).
    private val sendHandler = Handler(Looper.getMainLooper())
    private var currentPitchFlag = 0
    private var currentYawFlag = 0
    private var lightsOn = false
    private val sendIntervalMs = 100L
    private val drivePower = 200 // 0-255 magnitude; 200 is a strong-but-safe default

    private val sendRunnable = object : Runnable {
        override fun run() {
            sendPacket()
            sendHandler.postDelayed(this, sendIntervalMs)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                loadPairedDevices()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permission is required to list paired devices",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceSpinner = findViewById(R.id.deviceSpinner)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { ensurePermissionsThenLoad() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectToSelected() }

        setupHoldButton(R.id.btnForward, pitchFlag = 1, yawFlag = 0)
        setupHoldButton(R.id.btnBackward, pitchFlag = 2, yawFlag = 0)
        setupHoldButton(R.id.btnLeft, pitchFlag = 0, yawFlag = 1)
        setupHoldButton(R.id.btnRight, pitchFlag = 0, yawFlag = 2)

        findViewById<Button>(R.id.btnStop).setOnClickListener { stopDriving() }

        findViewById<Button>(R.id.btnLights).setOnClickListener {
            // The exact meaning of the trimer/aux nibble on is600 hardware wasn't
            // confirmed from the decompiled UI. This toggles it experimentally —
            // on real hardware it may control lights, a horn, or nothing at all.
            lightsOn = !lightsOn
            sendPacket()
            Toast.makeText(this, if (lightsOn) "AUX byte: ON" else "AUX byte: OFF", Toast.LENGTH_SHORT).show()
        }

        ensurePermissionsThenLoad()
    }

    private fun ensurePermissionsThenLoad() {
        if (bluetoothAdapter == null) {
            statusText.text = "This device has no Bluetooth adapter"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            val notGranted = needed.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                requestPermissionLauncher.launch(notGranted.toTypedArray())
                return
            }
        }
        loadPairedDevices()
    }

    @SuppressLint("MissingPermission") // permission verified above for API 31+; legacy BLUETOOTH is normal-protection pre-31
    private fun loadPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            statusText.text = "Please turn on Bluetooth and pair the car first"
            return
        }
        pairedDevices = adapter.bondedDevices.toList()
        val names = pairedDevices.map { "${it.name} (${it.address})" }
        if (names.isEmpty()) {
            statusText.text = "No paired devices found. Pair the car in Android Bluetooth settings first (PIN usually 0000)."
        } else {
            statusText.text = "Select the car below, then tap Connect"
        }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }

    @SuppressLint("MissingPermission")
    private fun connectToSelected() {
        val position = deviceSpinner.selectedItemPosition
        if (position < 0 || position >= pairedDevices.size) {
            Toast.makeText(this, "Pick a paired device first", Toast.LENGTH_SHORT).show()
            return
        }
        val device = pairedDevices[position]
        statusText.text = "Connecting to ${device.name}..."
        ioExecutor.execute {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                sock.connect()
                socket = sock
                outputStream = sock.outputStream
                runOnUiThread { statusText.text = "Connected to ${device.name}" }
            } catch (e: Exception) {
                socket = null
                outputStream = null
                runOnUiThread {
                    statusText.text = "Connection failed: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldButton(viewId: Int, pitchFlag: Int, yawFlag: Int) {
        findViewById<Button>(viewId).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> startDriving(pitchFlag, yawFlag)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDriving()
            }
            false // let the button still show its normal press animation
        }
    }

    private fun startDriving(pitchFlag: Int, yawFlag: Int) {
        currentPitchFlag = pitchFlag
        currentYawFlag = yawFlag
        sendHandler.removeCallbacks(sendRunnable)
        sendHandler.post(sendRunnable)
    }

    private fun stopDriving() {
        currentPitchFlag = 0
        currentYawFlag = 0
        sendHandler.removeCallbacks(sendRunnable)
        sendPacket() // send one final "stop" packet
    }

    private fun sendPacket() {
        val stream = outputStream ?: return
        val trimer = if (lightsOn) 15 else 0
        val packet = buildPacket(
            pitchFlag = currentPitchFlag,
            pitch = if (currentPitchFlag != 0) drivePower else 0,
            yawFlag = currentYawFlag,
            yaw = if (currentYawFlag != 0) drivePower else 0,
            trimer = trimer
        )
        ioExecutor.execute {
            try {
                stream.write(packet)
                stream.flush()
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Lost connection: ${e.message}" }
                outputStream = null
            }
        }
    }

    /**
     * Rebuilds the exact 12-byte ASCII packet the original iCess app sent
     * for the WECCAN_CAR_is600 protocol. See class doc for the field layout.
     */
    private fun buildPacket(pitchFlag: Int, pitch: Int, yawFlag: Int, yaw: Int, trimer: Int): ByteArray {
        val pClamped = pitch.coerceIn(0, 255)
        val yClamped = yaw.coerceIn(0, 255)
        val trimClamped = trimer.coerceIn(0, 15)

        val flagByte = (yawFlag and 0x3) or ((pitchFlag and 0x3) shl 2)
        val trimerByte = (trimClamped shl 4) and 0xFF
        val checkNum = (pClamped + yClamped + 1 + trimClamped) and 0xFF

        // Logical field order [checkNum, trimerByte, pitch, yaw, flagByte]
        // gets reversed on the wire (reverse=1 in the original protocol config):
        val wireOrder = intArrayOf(flagByte, yClamped, pClamped, trimerByte, checkNum)

        val sb = StringBuilder("0p")
        for (b in wireOrder) {
            sb.append(String.format("%02x", b and 0xFF))
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    override fun onDestroy() {
        super.onDestroy()
        sendHandler.removeCallbacks(sendRunnable)
        ioExecutor.execute {
            try {
                outputStream?.flush()
                outputStream?.close()
                socket?.close()
            } catch (_: Exception) {
            }
        }
        ioExecutor.shutdown()
    }
}
