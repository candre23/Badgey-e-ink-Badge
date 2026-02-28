package com.example.badgey

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import android.annotation.SuppressLint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.floor


private val SERVICE_UUID: UUID = UUID.fromString("6a3a7b52-2d6d-4a2b-8d1a-0d4d6c3a1c10")
private val FB_UUID: UUID      = UUID.fromString("f3a7e19c-5f21-4c3c-8b64-3c2f6fd1c4ab")

private const val BADGE_W = 128
private const val BADGE_H = 296
private const val FB_BYTES = BADGE_W * BADGE_H / 8 // 4736

private fun needsBluetoothRuntimePerms(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

private const val PREFS_NAME = "ebadge_prefs"
private const val PREF_BOUND_ADDR = "bound_ble_addr"

private fun loadBoundAddress(ctx: Context): String? {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getString(PREF_BOUND_ADDR, null)
}

private fun saveBoundAddress(ctx: Context, addr: String) {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().putString(PREF_BOUND_ADDR, addr).apply()
}

private fun clearBoundAddress(ctx: Context) {
    val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    sp.edit().remove(PREF_BOUND_ADDR).apply()
}


// ---------- Editor model ----------

enum class BadgeFont(val label: String, val tf: Typeface) {
    SANS("Sans", Typeface.SANS_SERIF),
    SERIF("Serif", Typeface.SERIF),
    MONO("Mono", Typeface.MONOSPACE)
}
sealed class Block {
    data class Text(
        val text: String,
        val sizeSp: Float = 24f,
        val bold: Boolean = false,
        val align: Paint.Align = Paint.Align.CENTER,
        val font: BadgeFont = BadgeFont.SANS
    ) : Block()

    data class Image(
        val uri: Uri,
        val scale: Float = 1.0f,
        val gamma: Float = 1.0f,
        val nativePacked: ByteArray? = null  // if this is a native 128x296 1bpp BMP, store it here
    ) : Block()
}

// ---------- BLE manager ----------
class BadgeBle(private val ctx: Context) {

    private val bluetoothManager =
        ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private var scanCb: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var fbChar: BluetoothGattCharacteristic? = null
    private val writeQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var writing = false
    private var expectedTotal = 0          // total bytes we intend to send (header + payload)
    private var sentTotal = 0              // bytes successfully ACKed by GATT
    private var negotiatedMtu = 23         // default ATT MTU; we request bigger after connect
    private var lastChunkLen = 0           // size of the chunk currently in-flight

    @SuppressLint("MissingPermission")
    private fun kickWriteQueue() {
        val g = gatt ?: run { updateStatus("Not connected"); return }
        val c = fbChar ?: run { updateStatus("FB char missing"); return }

        if (writing) return
        val next = writeQueue.removeFirstOrNull() ?: run {
            updateStatus("Send done ($sentTotal/$expectedTotal)")
            return
        }
        lastChunkLen = next.size

        writing = true
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // require ack
            c.value = next
            val ok = g.writeCharacteristic(c)
            if (!ok) {
                writing = false
                updateStatus("writeCharacteristic returned false")
            }
        } catch (se: SecurityException) {
            writing = false
            updateStatus("Write blocked by permissions")
        }
    }

    private fun makeGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    updateStatus("Connected. Discovering...")
                    try { g.requestMtu(247) } catch (_: SecurityException) {}
                } else {
                    updateStatus("Disconnected")
                    fbChar = null
                    try { g.close() } catch (_: Exception) {}
                    gatt = null
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS && mtu >= 23) {
                    negotiatedMtu = mtu
                    updateStatus("MTU = $mtu. Discovering services...")
                } else {
                    updateStatus("MTU request failed ($statusCode). Discovering services...")
                }

                try { g.discoverServices() } catch (_: SecurityException) {
                    updateStatus("Discover blocked by permissions")
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
                mainHandler.post {
                    writing = false
                    if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                        // Count bytes that were actually ACKed
                        sentTotal += lastChunkLen
                        updateStatus("Sending... ($sentTotal/$expectedTotal)")
                        kickWriteQueue()
                    } else {
                        updateStatus("Write failed: $statusCode (sent $sentTotal/$expectedTotal)")
                        writeQueue.clear()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
                val svc = g.getService(SERVICE_UUID)
                if (svc == null) {
                    updateStatus("Service not found")
                    return
                }
                val c = svc.getCharacteristic(FB_UUID)
                if (c == null) {
                    updateStatus("Framebuffer characteristic not found")
                    return
                }
                fbChar = c
                updateStatus("Ready")
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var status by mutableStateOf("Idle")
        private set


    var scanHits by mutableStateOf(0)
        private set

    var lastScanFail by mutableStateOf<Int?>(null)
        private set

    private fun updateStatus(s: String) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            status = s
        } else {
            mainHandler.post { status = s }
        }
    }

    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPerm(): Boolean =
        !needsBluetoothRuntimePerms() || hasPerm(Manifest.permission.BLUETOOTH_SCAN)

    private fun hasConnectPerm(): Boolean =
        !needsBluetoothRuntimePerms() || hasPerm(Manifest.permission.BLUETOOTH_CONNECT)


    @SuppressLint("MissingPermission")
    fun connectByAddress(address: String) {
        if (!hasConnectPerm()) {
            updateStatus("Missing permission: BLUETOOTH_CONNECT")
            return
        }
        val a = adapter
        if (a == null) {
            updateStatus("Bluetooth not available")
            return
        }
        if (!a.isEnabled) {
            updateStatus("Bluetooth is off")
            return
        }

        updateStatus("Connecting to $address...")
        stopScan()

        try {
            val device = a.getRemoteDevice(address)
            gatt = device.connectGatt(ctx, false, makeGattCallback())
        } catch (_: IllegalArgumentException) {
            updateStatus("Bad address: $address")
        } catch (_: SecurityException) {
            updateStatus("Connect blocked by permissions")
        }
    }

    fun startScan(onFound: (ScanResult) -> Unit) {
        stopScan()
        mainHandler.post {
            scanHits = 0
            lastScanFail = null
        }


        if (adapter == null) {
            updateStatus("Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            updateStatus("Bluetooth is off")
            return
        }
        if (!hasScanPerm()) {
            updateStatus("Missing permission: BLUETOOTH_SCAN")
            return
        }

        val scn = scanner
        if (scn == null) {
            updateStatus("BLE scanner not available")
            return
        }

        updateStatus("Scanning...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                mainHandler.post {
                    scanHits++
                    onFound(result)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                mainHandler.post {
                    scanHits += results.size
                    results.forEach(onFound)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                mainHandler.post { lastScanFail = errorCode }
                updateStatus("Scan failed: $errorCode")
            }
        }



        try {
            // No filter: more reliable for 128-bit UUID cases
            scn.startScan(null, settings, scanCb)
        } catch (_: SecurityException) {
            updateStatus("Scan blocked by permissions")
            scanCb = null
        }
    }


    fun stopScan() {
        val scn = scanner ?: run {
            scanCb = null
            return
        }
        val cb = scanCb ?: return

        if (!hasScanPerm()) {
            // Can't call stopScan safely without SCAN permission.
            scanCb = null
            if (status.startsWith("Scanning")) updateStatus("Idle")
            return
        }

        try {
            scn.stopScan(cb)
        } catch (se: SecurityException) {
            // ignore
        } finally {
            scanCb = null
            if (status.startsWith("Scanning")) updateStatus("Idle")
        }
    }
    @SuppressLint("MissingPermission")
    fun connect(result: ScanResult) {
        if (!hasConnectPerm()) {
            updateStatus("Missing permission: BLUETOOTH_CONNECT")
            return
        }

        updateStatus("Connecting...")
        stopScan()

        try {
            gatt = result.device.connectGatt(ctx, false, makeGattCallback())
        } catch (_: SecurityException) {
            updateStatus("Connect blocked by permissions")
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        updateStatus("Disconnected")
        fbChar = null

        if (!hasConnectPerm()) {
            // Can't legally call disconnect/close if CONNECT isn't granted
            gatt = null
            return
        }

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
            // ignore
        } finally {
            gatt = null
        }
    }

    fun isReady(): Boolean =
        gatt != null && fbChar != null && status == "Ready"
    @SuppressLint("MissingPermission")
    fun sendFramebuffer(packed: ByteArray) {
        val g = gatt
        if (g == null || fbChar == null) {
            updateStatus("Not ready (connect first)")
            return
        }

        // Frame protocol: send a header then raw bytes (we’ll match this on ESP32)
        // Header: "FB" + uint16 length (little-endian)
        val len = packed.size
        val header = byteArrayOf(
            'F'.code.toByte(), 'B'.code.toByte(),
            (len and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte()
        )

        writeQueue.clear()
        writing = false
        expectedTotal = header.size + packed.size
        sentTotal = 0
        lastChunkLen = 0

// enqueue header
        writeQueue.add(header)

// enqueue data in MTU-friendly chunks
// ATT payload for a single write is (MTU - 3). Cap it to something sane.
        val mtuPayload = (negotiatedMtu - 3).coerceIn(20, 244)

        var off = 0
        while (off < packed.size) {
            val n = min(mtuPayload, packed.size - off)
            writeQueue.add(packed.copyOfRange(off, off + n))
            off += n
        }

        updateStatus("Sending... (0/$expectedTotal) mtuPayload=$mtuPayload")
        kickWriteQueue()
    }

}


// ---------- Rendering + dithering ----------

private fun applyGammaToBitmap(src: Bitmap, gamma: Float): Bitmap {
    if (gamma == 1.0f) return src
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    val invGamma = (1.0 / gamma.toDouble())
    for (y in 0 until h) {
        for (x in 0 until w) {
            val px = src.getPixel(x, y)
            val a = Color.alpha(px)
            val r0 = Color.red(px) / 255.0
            val g0 = Color.green(px) / 255.0
            val b0 = Color.blue(px) / 255.0

            val r = (Math.pow(r0, invGamma) * 255.0).toInt().coerceIn(0, 255)
            val g = (Math.pow(g0, invGamma) * 255.0).toInt().coerceIn(0, 255)
            val b = (Math.pow(b0, invGamma) * 255.0).toInt().coerceIn(0, 255)

            out.setPixel(x, y, Color.argb(a, r, g, b))
        }
    }
    return out
}
private fun renderBlocksToBitmap(ctx: Context, blocks: List<Block>): Bitmap {
    val bmp = Bitmap.createBitmap(BADGE_W, BADGE_H, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.WHITE)

    // If a native full-screen BMP is present, render it bit-for-bit as the screen.
// This matches "saved badge screen" behavior.
    val nativeScreen = blocks.firstOrNull { it is Block.Image && it.nativePacked != null } as? Block.Image
    if (nativeScreen?.nativePacked != null) {
        val bw = Bitmap.createBitmap(BADGE_W, BADGE_H, Bitmap.Config.ARGB_8888)
        val rowBytes = (BADGE_W + 7) / 8
        var src = 0
        for (y in 0 until BADGE_H) {
            for (xByte in 0 until rowBytes) {
                val b = nativeScreen.nativePacked[src++].toInt() and 0xFF
                for (bit in 0..7) {
                    val x = xByte * 8 + bit
                    if (x >= BADGE_W) break
                    val isBlack = ((b shr (7 - bit)) and 1) == 1
                    bw.setPixel(x, y, if (isBlack) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
        canvas.drawBitmap(bw, 0f, 0f, null)
        return bmp
    }

    var y = 6f
    val paddingX = 6f

    for (b in blocks) {
        when (b) {

            is Block.Text -> {
                // Render text in a way that converts to 1-bit more cleanly
                val tp = TextPaint().apply {
                    isAntiAlias = false
                    isSubpixelText = false
                    isLinearText = true
                    color = Color.BLACK
                    textSize = b.sizeSp * ctx.resources.displayMetrics.scaledDensity

                    typeface = if (b.bold) Typeface.create(b.font.tf, Typeface.BOLD) else b.font.tf
                }

                val contentWidth = (BADGE_W - 2 * paddingX).toInt().coerceAtLeast(1)

                val layoutAlign = when (b.align) {
                    Paint.Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    Paint.Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                }

                val sl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    StaticLayout.Builder.obtain(b.text, 0, b.text.length, tp, contentWidth)
                        .setAlignment(layoutAlign)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.0f)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    StaticLayout(b.text, tp, contentWidth, layoutAlign, 1.0f, 0f, false)
                }

                canvas.save()
                canvas.translate(paddingX, y)
                sl.draw(canvas)
                canvas.restore()

                y += sl.height + 6f
            }

            is Block.Image -> {
                val img0 = runCatching {
                    ctx.contentResolver.openInputStream(b.uri).use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }.getOrNull() ?: continue

                // Apply gamma BEFORE scaling so the downscale reflects the adjustment
                val img = applyGammaToBitmap(img0, b.gamma)

                val maxH = max(20f, (BADGE_H - y - 6f))
                val maxW = (BADGE_W - 2 * paddingX)

                // Fit-to-box scale
                val fit = min(maxW / img.width.toFloat(), maxH / img.height.toFloat())

                // Apply user scale multiplier
                val s = (fit * b.scale).coerceAtLeast(0.05f)

                val w = (img.width * s).toInt().coerceAtLeast(1)
                val h = (img.height * s).toInt().coerceAtLeast(1)

                // IMPORTANT: scale and draw the gamma-adjusted image, not the original
                val scaled = Bitmap.createScaledBitmap(img, w, h, true)

                val left = ((BADGE_W - w) / 2f)
                canvas.drawBitmap(scaled, left, y, null)
                y += h + 6f
            }
        }

        if (y >= BADGE_H - 4f) break
    }

    return bmp
}

// Ordered Bayer 4x4 dithering to 1-bit packed buffer (row-major, MSB first)
private fun ditherTo1BitPacked(src: Bitmap, gamma: Float): ByteArray {
    val out = ByteArray(FB_BYTES)

    val bayer4 = arrayOf(
        intArrayOf( 0,  8,  2, 10),
        intArrayOf(12,  4, 14,  6),
        intArrayOf( 3, 11,  1,  9),
        intArrayOf(15,  7, 13,  5)
    )

    var byteIndex = 0
    var bitMask = 0x80
    var curByte = 0

    for (y in 0 until BADGE_H) {
        for (x in 0 until BADGE_W) {
            val px = src.getPixel(x, y)
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            // Luma
            val lum0 = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            val lumG = Math.pow(lum0.coerceIn(0.0, 1.0), (1.0 / gamma.toDouble()))
            val lum = (lumG * 255.0).toInt()

            // Threshold with Bayer matrix (0..15) scaled into 0..255
            val t = (bayer4[y and 3][x and 3] * 16) // 0..240
            val black = lum < (128 + (t - 120) / 2)

            if (black) curByte = curByte or bitMask

            bitMask = bitMask shr 1
            if (bitMask == 0) {
                out[byteIndex++] = curByte.toByte()
                curByte = 0
                bitMask = 0x80
            }
        }
    }
    return out
}

private fun packed1BitToBitmap(packed: ByteArray): Bitmap {
    val bmp = Bitmap.createBitmap(BADGE_W, BADGE_H, Bitmap.Config.ARGB_8888)

    var i = 0
    for (y in 0 until BADGE_H) {
        for (x in 0 until BADGE_W step 8) {
            val b = packed[i++].toInt() and 0xFF
            for (bit in 0..7) {
                val mask = 0x80 shr bit
                val isBlack = (b and mask) != 0
                val px = if (isBlack) Color.BLACK else Color.WHITE
                val xx = x + bit
                if (xx < BADGE_W) bmp.setPixel(xx, y, px)
            }
        }
    }
    return bmp
}

private data class NativeBmpResult(
    val packed: ByteArray, // row-major 1bpp packed, rowBytes = (w+7)/8
    val w: Int,
    val h: Int
)

private fun tryLoadNative1BitBmp(ctx: Context, uri: Uri, expectW: Int, expectH: Int): NativeBmpResult? {
    // Supports: BMP 1bpp, BI_RGB, 2-color palette, 128x296 (or expected), bottom-up OR top-down.
    // Returns packed bytes in top-to-bottom row order, each row is rowBytes bytes, MSB-first per byte.
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val bytes = ins.readBytes()
            if (bytes.size < 62) return null
            if (bytes[0].toInt().toChar() != 'B' || bytes[1].toInt().toChar() != 'M') return null

            fun u16(off: Int): Int = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            fun s32(off: Int): Int =
                (bytes[off].toInt() and 0xFF) or
                        ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[off + 3].toInt() and 0xFF) shl 24)

            val pixelOffset = s32(10)
            val dibSize = s32(14)
            if (dibSize < 40) return null // require BITMAPINFOHEADER or larger

            val w = s32(18)
            val hRaw = s32(22)
            val planes = u16(26)
            val bpp = u16(28)
            val compression = s32(30)
            val colorsUsed = s32(46)

            if (planes != 1) return null
            if (bpp != 1) return null
            if (compression != 0) return null // BI_RGB
            if (w != expectW) return null

            val topDown = hRaw < 0
            val h = kotlin.math.abs(hRaw)
            if (h != expectH) return null

            // Row size in file is padded to 4 bytes
            val rowBytes = (w + 7) / 8
            val rowStride = ((rowBytes + 3) / 4) * 4
            if (pixelOffset + rowStride * h > bytes.size) return null

            // Palette sanity: 2 colors. Some BMPs omit colorsUsed, so accept 0 or 2.
            if (colorsUsed != 0 && colorsUsed != 2) return null

            // BMP bit convention: bit=0 is palette index 0, bit=1 is palette index 1.
            // For our badge: 0=white, 1=black.
            // Ensure palette[0]=white and palette[1]=black (BGRA)
            // Palette starts immediately after DIB header for BITMAPINFOHEADER (usually at 14+40=54)
            val paletteOff = 14 + dibSize
            if (paletteOff + 8 > bytes.size) return null
            val b0 = bytes[paletteOff + 0].toInt() and 0xFF
            val g0 = bytes[paletteOff + 1].toInt() and 0xFF
            val r0 = bytes[paletteOff + 2].toInt() and 0xFF
            val b1 = bytes[paletteOff + 4].toInt() and 0xFF
            val g1 = bytes[paletteOff + 5].toInt() and 0xFF
            val r1 = bytes[paletteOff + 6].toInt() and 0xFF

            val pal0IsWhite = (r0 > 200 && g0 > 200 && b0 > 200)
            val pal1IsBlack = (r1 < 55 && g1 < 55 && b1 < 55)
            if (!(pal0IsWhite && pal1IsBlack)) {
                // If palette is inverted (0=black, 1=white) we could invert bits,
                // but user asked "bit-for-bit native", so only accept the native mapping.
                return null
            }

            val packed = ByteArray(rowBytes * h)
            for (row in 0 until h) {
                val srcRow = if (topDown) row else (h - 1 - row)
                val srcOff = pixelOffset + srcRow * rowStride
                val dstOff = row * rowBytes
                bytes.copyInto(packed, destinationOffset = dstOff, startIndex = srcOff, endIndex = srcOff + rowBytes)
            }

            NativeBmpResult(packed = packed, w = w, h = h)
        }
    } catch (_: Exception) {
        null
    }
}
private fun packed1BitToBmpBytes(packed: ByteArray, w: Int, h: Int): ByteArray {
    // 1-bit BMP:
    // - BITMAPFILEHEADER (14)
    // - BITMAPINFOHEADER (40)
    // - palette (2 entries, 8 bytes)
    // - pixel data (rows padded to 4-byte boundary)
    //
    // We write a TOP-DOWN bitmap by setting biHeight negative, so we do not have to flip rows.

    val rowBytes = (w + 7) / 8
    val rowStride = ((rowBytes + 3) / 4) * 4
    val imageSize = rowStride * h

    val fileHeaderSize = 14
    val infoHeaderSize = 40
    val paletteSize = 8
    val pixelOffset = fileHeaderSize + infoHeaderSize + paletteSize
    val fileSize = pixelOffset + imageSize

    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )



    val out = ByteArray(fileSize)
    var p = 0

    // ---- BITMAPFILEHEADER ----
    out[p++] = 'B'.code.toByte()
    out[p++] = 'M'.code.toByte()
    le32(fileSize).copyInto(out, p); p += 4
    le16(0).copyInto(out, p); p += 2 // bfReserved1
    le16(0).copyInto(out, p); p += 2 // bfReserved2
    le32(pixelOffset).copyInto(out, p); p += 4

    // ---- BITMAPINFOHEADER ----
    le32(infoHeaderSize).copyInto(out, p); p += 4
    le32(w).copyInto(out, p); p += 4
    le32(-h).copyInto(out, p); p += 4   // negative => top-down
    le16(1).copyInto(out, p); p += 2    // planes
    le16(1).copyInto(out, p); p += 2    // bitcount = 1
    le32(0).copyInto(out, p); p += 4    // BI_RGB (no compression)
    le32(imageSize).copyInto(out, p); p += 4
    le32(2835).copyInto(out, p); p += 4 // 72 DPI ≈ 2835 ppm (x)
    le32(2835).copyInto(out, p); p += 4 // (y)
    le32(2).copyInto(out, p); p += 4    // colors used
    le32(0).copyInto(out, p); p += 4    // important colors

    // ---- Palette (BGRA) ----
    // Your packed buffer is "0 = white, 1 = black" (matches preview & badge).
    // BMP palette index 0 => bit 0, palette index 1 => bit 1.
    // index0 = white, index1 = black
    out[p++] = 0xFF.toByte(); out[p++] = 0xFF.toByte(); out[p++] = 0xFF.toByte(); out[p++] = 0x00.toByte() // white
    out[p++] = 0x00.toByte(); out[p++] = 0x00.toByte(); out[p++] = 0x00.toByte(); out[p++] = 0x00.toByte() // black

    // ---- Pixel data ----
    // packed is row-major, rowBytes per row. BMP needs rowStride with padding.
    // Since we use top-down height, we write rows in natural order (top-to-bottom).
    val pad = rowStride - rowBytes
    var src = 0
    for (y in 0 until h) {
        // copy row bytes
        packed.copyInto(out, destinationOffset = p, startIndex = src, endIndex = src + rowBytes)
        p += rowBytes
        src += rowBytes

        // padding
        for (i in 0 until pad) out[p++] = 0x00
    }

    return out
}


// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val ble = remember { BadgeBle(ctx) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // ---- Binding state ----
    var boundAddr by remember { mutableStateOf(loadBoundAddress(ctx)) }

    data class SeenDevice(val address: String, val name: String, val rssi: Int)
    val seen = remember { mutableStateMapOf<String, SeenDevice>() }
    val seenList by remember {
        derivedStateOf { seen.values.sortedByDescending { it.rssi } }
    }

    var showBindDialog by remember { mutableStateOf(false) }

    // ---- Permissions flow ----
    var pendingScanForBind by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val scanOk = grants[Manifest.permission.BLUETOOTH_SCAN] == true
        val connOk = grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        val locOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true


        if (pendingScanForBind) {
            pendingScanForBind = false
            if (scanOk && connOk && locOk) {
                seen.clear()
                ble.startScan { r ->
                    val name = r.scanRecord?.deviceName ?: r.device.name ?: "(no name)"
                    val addr = r.device.address ?: return@startScan
                    val rssi = r.rssi

                    mainHandler.post {
                        // scanHits++
                        seen[addr] = SeenDevice(addr, name, rssi)
                    }
                }

            }
        }
    }

    fun startBindScan() {
        // On Android < 12, runtime perms aren't needed. On Android 12+, request.
        if (!needsBluetoothRuntimePerms()) {
            seen.clear()
            ble.startScan { r ->
                val name = r.scanRecord?.deviceName ?: r.device.name ?: "(no name)"
                val addr = r.device.address ?: return@startScan
                mainHandler.post {
                    seen[addr] = SeenDevice(addr, name, r.rssi)
                }

            }
            return
        }

        val scanGranted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED

        val connectGranted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        if (scanGranted && connectGranted) {
            seen.clear()
            ble.startScan { r ->
                val name = r.scanRecord?.deviceName ?: r.device.name ?: "(no name)"
                val addr = r.device.address ?: return@startScan
                mainHandler.post {
                    seen[addr] = SeenDevice(addr, name, r.rssi)
                }

            }
            return
        }

        pendingScanForBind = true
        permLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

    }

    // ---- Editor state ----
    val editorBlocks = remember {
        mutableStateListOf<Block>(
            Block.Text("Your Name", sizeSp = 26f, bold = true, align = Paint.Align.CENTER),
            Block.Text("😀", sizeSp = 28f, bold = false, align = Paint.Align.CENTER),
            Block.Text("Company / Handle", sizeSp = 18f, bold = false, align = Paint.Align.CENTER),
        )
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val native = tryLoadNative1BitBmp(ctx, uri, BADGE_W, BADGE_H)

            if (native != null) {
                // Store bit-for-bit packed data. No dithering or scaling.
                editorBlocks.add(
                    Block.Image(
                        uri = uri,
                        scale = 1.0f,
                        gamma = 1.0f,
                        nativePacked = native.packed
                    )
                )
            } else {
                // Normal path: decode + scale + gamma + dither during render
                editorBlocks.add(
                    Block.Image(
                        uri = uri,
                        scale = 1.0f,
                        gamma = 1.0f,
                        nativePacked = null
                    )
                )
            }

        }
    }

    // ---- Preview: show true 1-bit output, scaled with integer pixels ----
    var previewPacked by remember { mutableStateOf<ByteArray?>(null) }
    var previewBmp1Bit by remember { mutableStateOf<Bitmap?>(null) }
    val saveBmp = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/bmp")
    ) { uri ->
        if (uri != null) {
            val packed = previewPacked
            if (packed == null) {
                // nothing to save
                return@rememberLauncherForActivityResult
            }

            val bytes = packed1BitToBmpBytes(packed, BADGE_W, BADGE_H)
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                os.flush()
            }
        }
    }

    fun rebuildPreview() {
        // Native full-screen BMP fast path: if any Image block has nativePacked,
        // use it bit-for-bit (no render, no dither).
        val nativeScreen = editorBlocks
            .firstOrNull { it is Block.Image && it.nativePacked != null } as? Block.Image

        if (nativeScreen?.nativePacked != null) {
            previewPacked = nativeScreen.nativePacked
            previewBmp1Bit = packed1BitToBitmap(nativeScreen.nativePacked)
            return
        }

        // Normal path: render -> dither -> preview
        val src = renderBlocksToBitmap(ctx, editorBlocks.toList())
        val packed = ditherTo1BitPacked(src, 1.0f)
        previewPacked = packed
        previewBmp1Bit = packed1BitToBitmap(packed)
    }

    // Rebuild when blocks added/removed; also updated on edits manually
    LaunchedEffect(editorBlocks.size) { rebuildPreview() }

    // ---- Formatting popup (minimal version for now) ----
    var formatIdx by remember { mutableStateOf<Int?>(null) }
    val formatBlock = formatIdx?.let { idx ->
        editorBlocks.getOrNull(idx) as? Block.Text
    }

    if (formatBlock != null && formatIdx != null) {
        AlertDialog(
            onDismissRequest = { formatIdx = null },
            title = { Text("Text Formatting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = formatBlock.bold,
                            onClick = {
                                val i = formatIdx ?: return@FilterChip
                                editorBlocks[i] = formatBlock.copy(bold = !formatBlock.bold)
                                rebuildPreview()
                            },
                            label = { Text("Bold") }
                        )
                        var fontMenuOpen by remember { mutableStateOf(false) }

                        Text("Font")
                        Box {
                            OutlinedButton(onClick = { fontMenuOpen = true }) {
                                Text(formatBlock.font.label)
                            }
                            DropdownMenu(
                                expanded = fontMenuOpen,
                                onDismissRequest = { fontMenuOpen = false }
                            ) {
                                BadgeFont.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(f.label) },
                                        onClick = {
                                            val i = formatIdx ?: return@DropdownMenuItem
                                            editorBlocks[i] = formatBlock.copy(font = f)
                                            fontMenuOpen = false
                                            rebuildPreview()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Text("Alignment")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = formatBlock.align == Paint.Align.LEFT,
                            onClick = {
                                val i = formatIdx ?: return@FilterChip
                                editorBlocks[i] = formatBlock.copy(align = Paint.Align.LEFT)
                                rebuildPreview()
                            },
                            label = { Text("Left") }
                        )
                        FilterChip(
                            selected = formatBlock.align == Paint.Align.CENTER,
                            onClick = {
                                val i = formatIdx ?: return@FilterChip
                                editorBlocks[i] = formatBlock.copy(align = Paint.Align.CENTER)
                                rebuildPreview()
                            },
                            label = { Text("Center") }
                        )
                        FilterChip(
                            selected = formatBlock.align == Paint.Align.RIGHT,
                            onClick = {
                                val i = formatIdx ?: return@FilterChip
                                editorBlocks[i] = formatBlock.copy(align = Paint.Align.RIGHT)
                                rebuildPreview()
                            },
                            label = { Text("Right") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { formatIdx = null }) { Text("Done") }
            }
        )
    }

    // ---- Bind dialog ----
    if (showBindDialog) {
        AlertDialog(
            onDismissRequest = {
                ble.stopScan()
                showBindDialog = false
            },
            title = { Text("Bind to a Badge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tap your badge in the list to bind by BLE address.")
                    Text("Current: ${boundAddr ?: "(none)"}", style = MaterialTheme.typography.bodySmall)
                    Text("Status: ${ble.status}", style = MaterialTheme.typography.bodySmall)
                    Text("Scan hits: ${ble.scanHits}", style = MaterialTheme.typography.bodySmall)
                    Text("Last scan fail: ${ble.lastScanFail ?: "(none)"}", style = MaterialTheme.typography.bodySmall)



                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startBindScan() }) { Text("Scan") }
                        OutlinedButton(onClick = { ble.stopScan() }) { Text("Stop") }
                        OutlinedButton(onClick = {
                            clearBoundAddress(ctx)
                            boundAddr = null
                        }) { Text("Clear") }
                    }

                    Divider()

                    // Show only likely candidates first: name == "E-Badge" OR unnamed but close
                    // val filtered = seenList.filter { it.name == "E-Badge" || it.name == "(no name)" || it.name == "N/A" }
                    val filtered = seenList

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(filtered.size) { i ->
                            val d = filtered[i]
                            val title = if (d.name.isBlank()) "(no name)" else d.name
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        saveBoundAddress(ctx, d.address)
                                        boundAddr = d.address
                                        ble.stopScan()
                                        showBindDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.bodyMedium)
                                    Text(d.address, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ble.stopScan()
                    showBindDialog = false
                }) { Text("Close") }
            }
        )
    }

    // ---- Layout ----
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { pad ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(10.dp)
        ) {
            val totalH = maxHeight
            val topH = totalH * 0.62f
            val bottomH = totalH - topH

            Column(Modifier.fillMaxSize()) {

                // TOP (2/3): left controls + right preview
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topH),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left vertical control strip
                    Column(
                        modifier = Modifier
                            .width(150.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Badgey", style = MaterialTheme.typography.titleMedium)
                        Text("BLE: ${ble.status}", style = MaterialTheme.typography.bodySmall)
                        // Text("Bound:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            boundAddr ?: "(none)",
                            fontSize = 10.sp,
                            maxLines = 1,
                            softWrap = false
                        )

                        Button(
                            onClick = { showBindDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Bind") }

                        Button(
                            onClick = { boundAddr?.let { ble.connectByAddress(it) } },
                            enabled = boundAddr != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Connect") }

                        OutlinedButton(
                            onClick = { ble.disconnect() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Disconnect") }

                        OutlinedButton(
                            onClick = { editorBlocks.add(Block.Text("New line", 18f, false, Paint.Align.CENTER)); rebuildPreview() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add Text") }

                        OutlinedButton(
                            onClick = { pickImage.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add Image") }

                        OutlinedButton(
                            onClick = {
                                // Suggest a filename in the save dialog
                                saveBmp.launch("badgey_screen.bmp")
                            },
                            enabled = previewPacked != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save BMP") }
                        Spacer(Modifier.weight(1f))

                        val canSend = ble.isReady() && previewPacked != null
                        Button(
                            onClick = {
                                val packed = previewPacked ?: return@Button
                                ble.sendFramebuffer(packed)
                            },
                            enabled = canSend,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Send") }
                    }

                    // Right preview panel
                    Card(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                val bmp = previewBmp1Bit
                                if (bmp == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No preview yet")
                                    }
                                } else {
                                    // Integer scaling so pixels stay square
                                    val density = LocalDensity.current
                                    val maxWpx = with(density) { maxWidth.toPx() }
                                    val maxHpx = with(density) { maxHeight.toPx() }

                                    val scale = max(
                                        1,
                                        min(
                                            floor(maxWpx / BADGE_W).toInt(),
                                            floor(maxHpx / BADGE_H).toInt()
                                        )
                                    )

                                    val drawW = BADGE_W * scale
                                    val drawH = BADGE_H * scale

                                    val leftPx = (maxWpx - drawW) / 2f
                                    val topPx = (maxHpx - drawH) / 2f

                                    val img = bmp.asImageBitmap()

                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        // White background
                                        drawRect(androidx.compose.ui.graphics.Color.White)

                                        // Draw scaled with nearest-neighbor
                                        drawImage(
                                            image = img,
                                            srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                                            srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                                            dstOffset = androidx.compose.ui.unit.IntOffset(leftPx.toInt(), topPx.toInt()),
                                            dstSize = androidx.compose.ui.unit.IntSize(drawW, drawH),
                                            alpha = 1f,
                                            filterQuality = FilterQuality.None
                                        )

                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // BOTTOM (1/3): condensed editor list (scroll)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bottomH)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(editorBlocks) { idx, block ->
                            when (block) {
                                is Block.Text -> {
                                    Card(Modifier.fillMaxWidth()) {
                                        Box(Modifier.fillMaxWidth().padding(10.dp)) {
                                            // Close X top-right
                                            IconButton(
                                                onClick = {
                                                    editorBlocks.removeAt(idx)
                                                    rebuildPreview()
                                                },
                                                modifier = Modifier.align(Alignment.TopEnd)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Text", style = MaterialTheme.typography.labelMedium)

                                                OutlinedTextField(
                                                    value = block.text,
                                                    onValueChange = { newText ->
                                                        editorBlocks[idx] = block.copy(text = newText)
                                                        rebuildPreview()
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )

                                                // Row: size slider (3/4 width) + formatting button
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text("Size: ${block.sizeSp.toInt()}", style = MaterialTheme.typography.bodySmall)
                                                        Slider(
                                                            value = block.sizeSp,
                                                            onValueChange = { v ->
                                                                editorBlocks[idx] = block.copy(sizeSp = v)
                                                                rebuildPreview()
                                                            },
                                                            valueRange = 2f..32f
                                                        )
                                                    }

                                                    OutlinedButton(
                                                        onClick = { formatIdx = idx },
                                                    ) {
                                                        Icon(Icons.Filled.Settings, contentDescription = "Formatting")


                                                        Spacer(Modifier.width(6.dp))
                                                        Text("Format")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                is Block.Image -> {
                                    Card(Modifier.fillMaxWidth()) {
                                        Box(Modifier.fillMaxWidth().padding(10.dp)) {
                                            IconButton(
                                                onClick = {
                                                    editorBlocks.removeAt(idx)
                                                    rebuildPreview()
                                                },
                                                modifier = Modifier.align(Alignment.TopEnd)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove")
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("Image", style = MaterialTheme.typography.labelMedium)
                                                Text(block.uri.toString(), style = MaterialTheme.typography.bodySmall)
                                                Text("Scale: %.2f".format(block.scale), style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = block.scale,
                                                    onValueChange = { v ->
                                                        editorBlocks[idx] = block.copy(scale = v)
                                                        rebuildPreview()
                                                    },
                                                    valueRange = 0.2f..2.5f
                                                )
                                                Text("Gamma: %.2f".format(block.gamma), style = MaterialTheme.typography.bodySmall)
                                                Slider(
                                                    value = block.gamma,
                                                    onValueChange = { v ->
                                                        editorBlocks[idx] = block.copy(gamma = v)
                                                        rebuildPreview()
                                                    },
                                                    valueRange = 0.6f..2.2f
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
