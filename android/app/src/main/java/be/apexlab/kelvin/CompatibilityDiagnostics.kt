package be.apexlab.kelvin

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Compatibility diagnostics: a shareable JSON report describing the phone, Android build, USB host
 * support, FLIR USB descriptors and what the [FlirCamera] driver saw (iAP2 identity, config
 * scalars, first frame layout, event journal).
 *
 * Hard rules:
 * - Reading only. [snapshot] never requests USB permission, claims or opens a device, and never
 *   sends a camera command; unknown transports stay untouched.
 * - Privacy. No photos, raw temperature/status payloads, protocol bytes, logcat, Android ID, phone
 *   serial, account ids or network addresses. Camera serials (iAP2 or USB) are excluded by default:
 *   when [snapshot] runs without includeCameraSerial, every known camera serial string is also
 *   masked inside event text and product-name fields, not just the explicit serial fields. The USB
 *   serial number is only read for FLIR candidates with permission already granted, and never for
 *   other devices. Nothing is ever uploaded; [export] just writes the file the share sheet sends.
 * - Robust. Cameras disconnect at any time and some descriptor fields need permission, so every
 *   getter is defensive: an unavailable field reports "unavailable" instead of losing the report.
 * - Bounded. A fixed event journal and at most [MAX_LAST_SEEN] last-seen devices, so memory and the
 *   single export file never grow.
 */
class CompatibilityDiagnostics(private val ctx: Context) {

    private val usb = ctx.getSystemService(UsbManager::class.java)
    private val pm = ctx.packageManager

    // All fields below are guarded by `this`; every public method is synchronized.
    private val events = ArrayDeque<JSONObject>()
    /** identity "VID:PID" (hex) -> {device summary, lastSeenAt, lastSeenElapsedMs}; MRU order. */
    private val lastSeen = LinkedHashMap<String, JSONObject>()
    /** Identities whose presence/permission state already produced an event. */
    private val seenNotes = HashMap<String, String>()
    /** iAP2 identification minus serial; serial kept separately for opt-in reports only. */
    private var identification: JSONObject? = null
    private var cameraSerial: String? = null
    /** Bounded recent camera serials, used to mask identity fields and journal text in opt-out reports. */
    private val knownSerials = LinkedHashSet<String>()
    private val cameraConfig = JSONObject()
    private val configTypesSeen = HashSet<String>()
    private var firstFrame: JSONObject? = null
    private var frameCount = 0L

    init {
        addEvent("diagnostics", "compatibility diagnostics started", false)
    }

    // ---- what the parent feeds us (camera threads, main thread, UI threads) --------------------

    /** One journal line. stage groups related events (usb, identify, config, error, ...). */
    fun record(stage: String, message: String) = synchronized(this) {
        addEvent(stage, clip(message), false)
    }

    /** Journal line with the exception class, message, first cause and a few top frames. */
    fun recordError(stage: String, error: Throwable) = synchronized(this) {
        addEvent(stage, clip(describeError(error)), true)
    }

    /** USB attach (or any device we get the chance to look at): keep its descriptor metadata. */
    fun observeDevice(device: UsbDevice) = synchronized(this) {
        val note = runCatching { deviceSummary(device, false) }
            .getOrElse { JSONObject().put("identity", identity(device)).put("unavailable", describeError(it)) }
        touchLastSeen(identity(device), note)
        // Reconnection re-enumerates every ~5 s: only report state changes, not every bounce.
        val state = identity(device) + " " + (if (hasUsbPermission(device)) "permission granted" else "no permission")
        if (seenNotes[identity(device)] != state) {
            seenNotes[identity(device)] = state
            addEvent("usb", "observed ${identity(device)} (${if (hasUsbPermission(device)) "permission granted" else "no permission"})", false)
        }
    }

    /** iAP2 identification (name/model/manufacturer/firmware/hardware; serial kept for opt-in). */
    fun identified(info: Map<String, String>) = synchronized(this) {
        val serial = info["serial"]?.takeIf { it.isNotBlank() }?.let(::clip)
        cameraSerial = serial
        serial?.let(::rememberSerial)
        val obj = JSONObject()
        for (key in IDENTIFICATION_KEYS) info[key]?.takeIf { it.isNotBlank() }?.let { obj.put(key, clip(it)) }
        if (identification?.toString() != obj.toString()) {
            identification = obj
            addEvent("identify", "Accessory identification metadata received", false)
        }
    }

    /** Config-channel event: keep only whitelisted scalar metadata, ignore payloads otherwise. */
    fun config(type: String, data: JSONObject) = synchronized(this) {
        var kept = false
        for ((path, reportKey) in SAFE_CONFIG) {
            val (eventKey, dataKey) = path
            if (eventKey != type) continue
            when (val v = data.opt(dataKey)) {
                is String -> if (v.isNotBlank()) { cameraConfig.put(reportKey, clip(v)); kept = true }
                is Number -> { cameraConfig.put(reportKey, v); kept = true }
                is Boolean -> { cameraConfig.put(reportKey, v); kept = true }
            }
        }
        val eventType = type.take(64)
        if (configTypesSeen.size < 16 && configTypesSeen.add(eventType)) {
            addEvent("config", "config channel delivered $eventType${if (kept) "" else " (nothing whitelisted kept)"}", false)
        }
    }

    /** Frame accounting only: the first layout and a counter. Frames/JPEG/status content are never stored. */
    fun recordFrame(thermalBytes: Int, jpegBytes: Int, statusBytes: Int) = synchronized(this) {
        frameCount++
        if (firstFrame == null) {
            firstFrame = JSONObject()
                .put("thermalBytes", thermalBytes)
                .put("jpegBytes", jpegBytes)
                .put("statusBytes", statusBytes)
                .put(
                    "thermalLayout",
                    if (thermalBytes == LEPTON_VO_SPI_BYTES) "80x60 Lepton VoSPI layout (decoded by this driver)"
                    else "unrecognized thermal block (${thermalBytes} bytes): no driver support for this layout",
                )
            addEvent("frame", "first frame: thermal=$thermalBytes jpeg=$jpegBytes status=$statusBytes", false)
        }
    }

    // ---- report --------------------------------------------------------------------------------

    /**
     * Pretty JSON report. [connection] is the human connection label from the UI. Camera serials
     * appear only with includeCameraSerial. Never touches USB permission/claims or the camera.
     */
    fun snapshot(connection: String, includeCameraSerial: Boolean): String = synchronized(this) {
        // Refresh last-seen metadata for whatever is plugged in right now, FLIR or not.
        val connected = HashSet<String>()
        runCatching { usb?.deviceList?.values }.getOrNull()?.forEach { d ->
            runCatching {
                connected.add(identity(d))
                touchLastSeen(identity(d), deviceSummary(d, false))
            }
        }

        val out = JSONObject()
        out.put("schemaVersion", SCHEMA_VERSION)
        out.put("generatedAt", Instant.now().toString())
        out.put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        out.put("connection", connection)
        section(out, "app", ::appInfo)
        section(out, "phone", ::phoneInfo)
        section(out, "android", ::androidInfo)
        section(out, "usb") { usbInfo(connected, includeCameraSerial) }
        section(out, "camera") { cameraInfo(includeCameraSerial) }
        section(out, "driver", ::driverInfo)
        out.put("events", JSONArray(events.toList()))
        (if (includeCameraSerial) out else redact(out) as JSONObject).toString(2)
    }

    /** Write [report] (the string [snapshot] returned) into the cache; one stable file, overwritten. */
    fun export(report: String): File {
        val dir = File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "kelvin-compatibility.json")
        file.writeText(report)
        return file
    }

    // ---- sections -------------------------------------------------------------------------------

    private fun appInfo(): JSONObject {
        val o = JSONObject()
        runCatching {
            val info = pm.getPackageInfo(ctx.packageName, 0)
            o.put("name", pm.getApplicationInfo(ctx.packageName, 0).loadLabel(pm).toString())
            o.put("package", ctx.packageName)
            o.put("versionName", info.versionName ?: "unavailable")
            o.put("versionCode", info.longVersionCode)
        }.onFailure { o.put("unavailable", describeError(it)) }
        return o
    }

    private fun phoneInfo(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER ?: "unavailable")
        .put("brand", Build.BRAND ?: "unavailable")
        .put("model", Build.MODEL ?: "unavailable")
        .put("device", Build.DEVICE ?: "unavailable")
        .put("abis", JSONArray(Build.SUPPORTED_ABIS?.toList().orEmpty()))

    private fun androidInfo(): JSONObject {
        val o = JSONObject()
        o.put("release", Build.VERSION.RELEASE ?: "unavailable")
        o.put("api", Build.VERSION.SDK_INT)
        runCatching { o.put("securityPatch", Build.VERSION.SECURITY_PATCH ?: "unavailable") }
            .onFailure { o.put("securityPatch", "unavailable") }
        runCatching { o.put("usbHostFeature", pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) }
            .onFailure { o.put("usbHostFeature", "unavailable") }
        return o
    }

    private fun usbInfo(connected: Set<String>, includeCameraSerial: Boolean): JSONObject {
        val o = JSONObject()
        val devices = JSONArray()
        runCatching {
            usb?.deviceList?.values?.forEach { d ->
                // An adapter from another vendor may be all Android sees: report every enumerated
                // device with full descriptors, marked as FLIR candidate or not, never claiming
                // support. Only FLIR candidates may ever carry a serial.
                runCatching {
                    devices.put(deviceSummary(d, includeCameraSerial && d.vendorId == FlirCamera.VENDOR_ID))
                }.onFailure {
                    devices.put(JSONObject().put("identity", identity(d)).put("flirCandidate", d.vendorId == FlirCamera.VENDOR_ID).put("unavailable", describeError(it)))
                }
            }
        }.onFailure { o.put("unavailable", describeError(it)) }
        o.put("devices", devices)
        val seen = JSONArray()
        for ((id, entry) in lastSeen) {
            seen.put(JSONObject(entry.toString()).put("identity", id).put("currentlyConnected", id in connected))
        }
        o.put("lastSeenDevices", seen)
        o.put("identityNote", "Devices are keyed by vendorId:productId. Android device paths are unstable across replug, so they are deliberately not reported.")
        return o
    }

    private fun cameraInfo(includeCameraSerial: Boolean): JSONObject {
        val o = JSONObject()
        val ident = identification
        o.put("identification", ident ?: JSONObject().put("status", "not identified (no iAP2 handshake completed)"))
        if (includeCameraSerial) {
            o.put("iap2Serial", cameraSerial?.takeIf { it.isNotBlank() } ?: "unavailable")
        } else {
            o.put("iap2Serial", "excluded (include camera serial to report)")
        }
        o.put("configMetadata", cameraConfig)
        o.put("frames", JSONObject()
            .put("count", frameCount)
            .put("firstFrame", firstFrame ?: JSONObject().put("status", "no frame received yet"))
            .put("frameContentsStored", false))
        return o
    }

    private fun driverInfo(): JSONObject = JSONObject()
        .put("tested", "09CB:1996 FLIR ONE (iOS/Lightning variant) over the iAP2 + frame-interface transport, 80x60 Lepton")
        .put("limitations", "Only the tested Lightning model and 80x60 layout are streamed. Other FLIR USB transports are described without opening them. Models identifying over the known Lightning transport may complete iAP2 identification, then stop before config/frame sessions if unsupported. Dimensions alone do not establish calibration compatibility.")

    // ---- device descriptors ----------------------------------------------------------------------

    /** Full descriptor dump: class, configs, interfaces, alternate settings, endpoints. */
    private fun deviceSummary(d: UsbDevice, includeSerial: Boolean): JSONObject {
        val o = JSONObject()
        o.put("identity", identity(d))
        o.put("vendorId", hex(d.vendorId))
        o.put("productId", hex(d.productId))
        o.put("flirCandidate", d.vendorId == FlirCamera.VENDOR_ID)
        o.put("deviceClass", d.deviceClass)
        o.put("deviceSubclass", d.deviceSubclass)
        o.put("deviceProtocol", d.deviceProtocol)
        o.put("permissionGranted", hasUsbPermission(d))
        o.put("usbProductName", runCatching { d.productName }.getOrNull()?.let(::clip) ?: "unavailable")
        o.put("usbManufacturer", runCatching { d.manufacturerName }.getOrNull()?.let(::clip) ?: "unavailable")
        o.put("usbVersion", runCatching { d.version }.getOrNull() ?: "unavailable")
        if (d.vendorId == FlirCamera.VENDOR_ID && hasUsbPermission(d)) {
            val serial = runCatching { d.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::clip)
            serial?.let(::rememberSerial)
            if (includeSerial) o.put("usbSerial", serial ?: "unavailable")
        } else if (includeSerial) o.put("usbSerial", "unavailable (USB permission not granted)")
        val configs = JSONArray()
        runCatching {
            for (ci in 0 until d.configurationCount) {
                val c = d.getConfiguration(ci) ?: continue
                val co = JSONObject().put("configId", c.id).put("maxPowerMa", c.maxPower)
                    .put("selfPowered", c.isSelfPowered).put("remoteWakeup", c.isRemoteWakeup)
                val intfs = JSONArray()
                for (ii in 0 until c.interfaceCount) {
                    val i = c.getInterface(ii) ?: continue
                    val io = JSONObject()
                        .put("interfaceId", i.id)
                        .put("alternateSetting", i.alternateSetting)
                        .put("interfaceName", runCatching { i.name }.getOrNull()?.let(::clip) ?: "unavailable")
                        .put("interfaceClass", i.interfaceClass)
                        .put("interfaceSubclass", i.interfaceSubclass)
                        .put("interfaceProtocol", i.interfaceProtocol)
                    val eps = JSONArray()
                    for (ei in 0 until i.endpointCount) {
                        val ep = i.getEndpoint(ei) ?: continue
                        eps.put(JSONObject()
                            .put("addressHex", hex(ep.address))
                            .put("direction", if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT")
                            .put("type", endpointTypeName(ep.type))
                            .put("maxPacketSize", ep.maxPacketSize)
                            .put("interval", ep.interval))
                    }
                    io.put("endpoints", eps)
                    intfs.put(io)
                }
                co.put("interfaces", intfs)
                configs.put(co)
            }
        }.onFailure { o.put("configurationsUnavailable", describeError(it)) }
        o.put("configurations", configs)
        return o
    }

    private fun touchLastSeen(id: String, summary: JSONObject) {
        val entry = JSONObject()
            .put("device", summary)
            .put("lastSeenAt", Instant.now().toString())
            .put("lastSeenElapsedMs", SystemClock.elapsedRealtime())
        lastSeen.remove(id)
        lastSeen[id] = entry
        while (lastSeen.size > MAX_LAST_SEEN) {
            // Detached cameras must stay diagnosable: evict a non-FLIR identity first.
            val victim = lastSeen.keys.firstOrNull { !it.startsWith(FLIR_IDENTITY_PREFIX) } ?: lastSeen.keys.first()
            lastSeen.remove(victim)
            seenNotes.remove(victim)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun addEvent(stage: String, message: String, error: Boolean) {
        events.addLast(JSONObject()
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("at", Instant.now().toString())
            .put("stage", stage.take(64))
            .put("message", message)
            .apply { if (error) put("error", true) })
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** One put failure anywhere must not lose the whole report: keep the section, flag it. */
    private fun section(target: JSONObject, name: String, block: () -> JSONObject) {
        target.put(name, runCatching(block).getOrElse { JSONObject().put("unavailable", describeError(it)) })
    }

    /** Copy before redacting: opt-out must not mutate a later opt-in report. Keys and numbers stay intact. */
    private fun redact(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { copy ->
            value.keys().forEach { key -> copy.put(key, redact(value.get(key))) }
        }
        is JSONArray -> JSONArray().also { copy ->
            for (i in 0 until value.length()) copy.put(redact(value.get(i)))
        }
        is String -> {
            var result: String = value
            for (serial in knownSerials) result = result.replace(serial, "[redacted-camera-serial]")
            result
        }
        else -> value
    }

    private fun rememberSerial(serial: String) {
        knownSerials.add(serial)
        while (knownSerials.size > MAX_EVENTS + MAX_LAST_SEEN) knownSerials.remove(knownSerials.first())
    }

    private fun hasUsbPermission(d: UsbDevice): Boolean = runCatching { usb?.hasPermission(d) == true }.getOrDefault(false)

    private fun identity(d: UsbDevice) = "%04X:%04X".format(d.vendorId, d.productId)

    private fun hex(v: Int) = "0x%04X".format(v)

    private fun endpointTypeName(type: Int) = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "unknown($type)"
    }

    private fun clip(s: String) = if (s.length <= MAX_MESSAGE) s else s.take(MAX_MESSAGE - 1) + "…"

    private fun describeError(t: Throwable): String {
        val sb = StringBuilder(t.javaClass.name)
        t.message?.let { sb.append(": ").append(it) }
        var cause = t.cause
        var depth = 0
        while (cause != null && cause !== t && cause !== cause.cause && depth < 2) {
            sb.append(" caused by ").append(cause.javaClass.name)
            cause.message?.let { sb.append(": ").append(it) }
            cause = cause.cause
            depth++
        }
        for (f in t.stackTrace.take(3)) sb.append(" at ${f.className}.${f.methodName}:${f.lineNumber}")
        return clip(sb.toString())
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_EVENTS = 200
        private const val MAX_LAST_SEEN = 8
        private const val MAX_MESSAGE = 400
        /** Identities are "%04X:%04X", so a FLIR candidate (vendor 09CB) keys start with this. */
        private const val FLIR_IDENTITY_PREFIX = "09CB:"
        /** 63 Lepton VoSPI packets of 164 bytes: the thermal block this driver decodes (see [FlirCamera]). */
        private const val LEPTON_VO_SPI_BYTES = 63 * 164
        private val IDENTIFICATION_KEYS = listOf("name", "model", "manufacturer", "firmware", "hardware")
        /** (event type, scalar data key) -> report key. Other payload fields are never kept. */
        private val SAFE_CONFIG = mapOf(
            ("sledInformation" to "versionLepton") to "leptonFirmware",
            ("batteryVoltageUpdate" to "percentage") to "batteryPercentage",
            ("batteryVoltageUpdate" to "voltage") to "batteryVoltage",
            ("batteryChargingStateUpdate" to "chargingState") to "chargingState",
        )
    }
}
