package com.kitchsync.app.printer

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Built-in High-Performance Kotlin Thermal Printer Engine for KitchSynx.
 * 
 * Supports:
 * 1. RAW TCP Socket Printing (Port 9100 / ESC-POS) over WiFi / LAN
 * 2. Instant Connection Ping & Socket Diagnostics
 * 3. Native ESC/POS Binary Protocol Generation (Format, Fonts, Cut, Align)
 * 4. Bluetooth SPP Thermal Receipt Printers (RFCOMM UUID 00001101)
 * 5. Android Native PrintManager Service (AirPrint / Mopria / System Print)
 */
class ThermalPrinter(
    private val activity: Activity,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "ThermalPrinter"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Standard ESC/POS Command Constants
        val ESC_INIT = byteArrayOf(0x1B, 0x40) // Initialize printer
        val ESC_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
        val ESC_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
        val ESC_ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02)
        val ESC_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
        val ESC_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
        val ESC_DOUBLE_ON = byteArrayOf(0x1D, 0x21, 0x11) // 2x width & height
        val ESC_DOUBLE_OFF = byteArrayOf(0x1D, 0x21, 0x00)
        val ESC_UNDERLINE_ON = byteArrayOf(0x1B, 0x2D, 0x01)
        val ESC_UNDERLINE_OFF = byteArrayOf(0x1B, 0x2D, 0x00)
        val ESC_FEED_AND_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x03) // Cut with 3-line feed
        val ESC_FEED_LINES_3 = byteArrayOf(0x1B, 0x64, 0x03)
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun isAvailable(): Boolean = true

    @JavascriptInterface
    fun getEngineVersion(): String = "Kotlin-ThermalEngine/2.0-Native"

    /**
     * Test connection to a LAN / WiFi thermal printer socket.
     */
    @JavascriptInterface
    fun testConnection(host: String, port: Int, callbackId: String) {
        executor.execute {
            val startMs = System.currentTimeMillis()
            var socket: Socket? = null
            try {
                Log.i(TAG, "Testing connection to thermal printer $host:$port...")
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), 4000)
                val latency = System.currentTimeMillis() - startMs

                val res = JSONObject().apply {
                    put("success", true)
                    put("latencyMs", latency)
                    put("host", host)
                    put("port", port)
                    put("message", "Connected to thermal printer at $host:$port ($latency ms)")
                }
                dispatchResult(callbackId, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed for $host:$port", e)
                val res = JSONObject().apply {
                    put("success", false)
                    put("host", host)
                    put("port", port)
                    put("error", e.message ?: "Failed to connect to printer at $host:$port")
                }
                dispatchResult(callbackId, res.toString())
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Send raw base64 or text byte stream directly to a network thermal printer over TCP.
     */
    @JavascriptInterface
    fun printTcp(host: String, port: Int, base64OrText: String, isBase64: Boolean, callbackId: String) {
        executor.execute {
            var socket: Socket? = null
            var out: OutputStream? = null
            try {
                val data = if (isBase64) {
                    val clean = if (base64OrText.contains(",")) base64OrText.substringAfter(",") else base64OrText
                    Base64.decode(clean, Base64.DEFAULT)
                } else {
                    base64OrText.toByteArray(Charset.forName("CP437"))
                }

                socket = Socket()
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(host, port), 5000)
                out = socket.getOutputStream()
                out.write(data)
                out.flush()

                val res = JSONObject().apply {
                    put("success", true)
                    put("host", host)
                    put("port", port)
                    put("bytesSent", data.size)
                    put("message", "Sent ${data.size} bytes to thermal printer ($host:$port)")
                }
                dispatchResult(callbackId, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "TCP print error for $host:$port", e)
                val res = JSONObject().apply {
                    put("success", false)
                    put("host", host)
                    put("port", port)
                    put("error", e.message ?: "Printer communication error")
                }
                dispatchResult(callbackId, res.toString())
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Build and print a full structured kitchen dispatch chit directly from JSON.
     * Generates compliant ESC/POS binary bytecode and streams directly to thermal printer.
     */
    @JavascriptInterface
    fun printChitJson(host: String, port: Int, chitJsonStr: String, callbackId: String) {
        executor.execute {
            var socket: Socket? = null
            var out: OutputStream? = null
            try {
                val chit = JSONObject(chitJsonStr)
                val paperWidth = chit.optInt("widthMm", 80)
                val maxCols = if (paperWidth <= 58) 32 else 48
                val charset = Charset.forName("CP437")

                val baos = ByteArrayOutputStream()

                // 1. Initialize Printer
                baos.write(ESC_INIT)

                // 2. Restaurant Masthead
                baos.write(ESC_ALIGN_CENTER)
                baos.write(ESC_DOUBLE_ON)
                val masthead = chit.optString("masthead", "HJH MAIMUNAH RESTAURANT")
                baos.write("$masthead\n".toByteArray(charset))
                baos.write(ESC_DOUBLE_OFF)

                val subheader = chit.optString("subheader", "Catering & Kitchen Production")
                baos.write("$subheader\n".toByteArray(charset))

                // Divider line
                val divider = "-".repeat(maxCols)
                val doubleDivider = "=".repeat(maxCols)
                baos.write("$divider\n".toByteArray(charset))

                // 3. Station / Mode Title
                baos.write(ESC_ALIGN_CENTER)
                baos.write(ESC_BOLD_ON)
                val title = chit.optString("title", "KITCHEN PRODUCTION CHIT")
                baos.write("*** $title ***\n".toByteArray(charset))
                baos.write(ESC_BOLD_OFF)

                // 4. Delivery Date & Dispatch Info
                baos.write(ESC_ALIGN_LEFT)
                val dateStr = chit.optString("deliveryDate", "TODAY")
                val deliveryTime = chit.optString("deliveryTime", "")
                baos.write(ESC_BOLD_ON)
                baos.write("EVENT DATE: $dateStr".toByteArray(charset))
                if (deliveryTime.isNotEmpty()) {
                    baos.write(" @ $deliveryTime".toByteArray(charset))
                }
                baos.write("\n".toByteArray(charset))
                baos.write(ESC_BOLD_OFF)

                val customer = chit.optString("customer", "")
                if (customer.isNotEmpty()) {
                    val mobile = chit.optString("mobile", "")
                    val custLine = if (mobile.isNotEmpty()) "CLIENT: $customer (Tel: $mobile)" else "CLIENT: $customer"
                    baos.write("$custLine\n".toByteArray(charset))
                }

                val formatTag = chit.optString("format", "")
                if (formatTag.isNotEmpty()) {
                    baos.write("FORMAT: $formatTag\n".toByteArray(charset))
                }

                val docRef = chit.optString("docRef", "")
                if (docRef.isNotEmpty()) {
                    baos.write("DOC REF: $docRef\n".toByteArray(charset))
                }

                baos.write("$doubleDivider\n".toByteArray(charset))

                // 5. Table Header
                baos.write(ESC_BOLD_ON)
                if (maxCols >= 48) {
                    val line = padRight("DISH DESCRIPTION", 28) + padCenter("TIME", 8) + padLeft("QTY", 12) + "\n"
                    baos.write(line.toByteArray(charset))
                } else {
                    val line = padRight("DISH", 18) + padCenter("TIME", 6) + padLeft("QTY", 8) + "\n"
                    baos.write(line.toByteArray(charset))
                }
                baos.write(ESC_BOLD_OFF)
                baos.write("$divider\n".toByteArray(charset))

                // 6. Dishes & Portions List
                val items = chit.optJSONArray("items") ?: JSONArray()
                var totalPortions = 0

                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val dish = it.optString("dish", "Item")
                    val time = it.optString("time", "--:--")
                    val qty = it.optInt("qty", 1)
                    totalPortions += qty

                    baos.write(ESC_BOLD_ON)
                    if (maxCols >= 48) {
                        val dishTrunc = if (dish.length > 27) dish.substring(0, 27) else dish
                        val qtyStr = "x$qty"
                        val rowLine = padRight(dishTrunc, 28) + padCenter(time, 8) + padLeft(qtyStr, 12) + "\n"
                        baos.write(rowLine.toByteArray(charset))
                    } else {
                        val dishTrunc = if (dish.length > 17) dish.substring(0, 17) else dish
                        val qtyStr = "x$qty"
                        val rowLine = padRight(dishTrunc, 18) + padCenter(time, 6) + padLeft(qtyStr, 8) + "\n"
                        baos.write(rowLine.toByteArray(charset))
                    }
                    baos.write(ESC_BOLD_OFF)

                    val remarks = it.optString("remarks", "")
                    if (remarks.isNotEmpty()) {
                        baos.write("  >> $remarks\n".toByteArray(charset))
                    }
                }

                // 7. Total Portions Summary
                baos.write("$divider\n".toByteArray(charset))
                baos.write(ESC_BOLD_ON)
                baos.write(ESC_ALIGN_RIGHT)
                baos.write("TOTAL PRODUCTION: $totalPortions PORTIONS\n".toByteArray(charset))
                baos.write(ESC_BOLD_OFF)
                baos.write(ESC_ALIGN_LEFT)
                baos.write("$doubleDivider\n".toByteArray(charset))

                // 8. Sign-off Blocks
                val driverBlock = chit.optBoolean("includeSignoff", true)
                if (driverBlock) {
                    baos.write("\nDRIVER / PACKING VERIFICATION:\n".toByteArray(charset))
                    baos.write("Packed By:  ________________   Time: _______\n".toByteArray(charset))
                    baos.write("Driver Sig: ________________   Time: _______\n\n".toByteArray(charset))
                }

                // 9. Footer & Cut
                val footer = chit.optString("footer", "KitchSynx Catering Automation System")
                baos.write(ESC_ALIGN_CENTER)
                baos.write("$footer\n".toByteArray(charset))
                baos.write(ESC_FEED_AND_CUT)

                // Transmit binary ESC/POS stream
                val payload = baos.toByteArray()
                socket = Socket()
                socket.soTimeout = 6000
                socket.connect(InetSocketAddress(host, port), 5000)
                out = socket.getOutputStream()
                out.write(payload)
                out.flush()

                Log.i(TAG, "Successfully printed chit (${payload.size} bytes) to $host:$port")
                val res = JSONObject().apply {
                    put("success", true)
                    put("host", host)
                    put("port", port)
                    put("totalPortions", totalPortions)
                    put("bytesSent", payload.size)
                    put("message", "Chit successfully printed to thermal printer at $host:$port!")
                }
                dispatchResult(callbackId, res.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Failed to print chit to $host:$port", e)
                val res = JSONObject().apply {
                    put("success", false)
                    put("host", host)
                    put("port", port)
                    put("error", e.message ?: "Printer execution error")
                }
                dispatchResult(callbackId, res.toString())
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Print HTML content via Android System PrintManager.
     */
    @JavascriptInterface
    fun printSystem(jobName: String) {
        mainHandler.post {
            try {
                val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("thermal", "Thermal", 203, 203))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
                printManager?.print(jobName, printAdapter, printAttributes)
            } catch (e: Exception) {
                Log.e(TAG, "Android PrintManager error", e)
            }
        }
    }

    /**
     * Print raw bytes to a paired Bluetooth thermal printer by MAC address.
     */
    @JavascriptInterface
    fun printBluetooth(macAddress: String, base64Payload: String, callbackId: String) {
        executor.execute {
            var btSocket: BluetoothSocket? = null
            var out: OutputStream? = null
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("Device does not support Bluetooth")
                val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                btSocket.connect()
                out = btSocket.outputStream

                val data = Base64.decode(base64Payload, Base64.DEFAULT)
                out.write(data)
                out.flush()

                val res = JSONObject().apply {
                    put("success", true)
                    put("device", macAddress)
                    put("bytesSent", data.size)
                    put("message", "Printed ${data.size} bytes to Bluetooth printer $macAddress")
                }
                dispatchResult(callbackId, res.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth print failed for $macAddress", e)
                val res = JSONObject().apply {
                    put("success", false)
                    put("device", macAddress)
                    put("error", e.message ?: "Bluetooth printing failed")
                }
                dispatchResult(callbackId, res.toString())
            } finally {
                try { out?.close() } catch (_: Exception) {}
                try { btSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun dispatchResult(callbackId: String, jsonPayload: String) {
        mainHandler.post {
            val js = "if(window.onNativePrintComplete){ window.onNativePrintComplete('" +
                    escapeJsString(callbackId) + "', " + jsonPayload + "); }"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun escapeJsString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun padRight(s: String, width: Int): String {
        return if (s.length >= width) s.substring(0, width) else s + " ".repeat(width - s.length)
    }

    private fun padLeft(s: String, width: Int): String {
        return if (s.length >= width) s.substring(0, width) else " ".repeat(width - s.length) + s
    }

    private fun padCenter(s: String, width: Int): String {
        if (s.length >= width) return s.substring(0, width)
        val pad = width - s.length
        val left = pad / 2
        val right = pad - left
        return " ".repeat(left) + s + " ".repeat(right)
    }
}
