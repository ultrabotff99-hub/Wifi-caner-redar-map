package com.tonix.wifireader

import android.content.Context
import android.graphics.*
import android.net.wifi.ScanResult
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.*
import kotlin.random.Random

/** A pin placed on the map for one WiFi access point. */
data class ApPin(val ssid: String, val point: GeoPoint, val approximate: Boolean)

class PulseOverlay(private val pins: () -> List<ApPin>) : Overlay() {
    private var phase = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FFC6"); textSize = 26f; isFakeBoldText = true
    }
    fun tick() { phase = (phase + 0.02f) % 1f }

    override fun draw(c: Canvas, proj: Projection) {
        for (pin in pins()) {
            val p = proj.toPixels(pin.point, null)
            val base = Color.parseColor(if (pin.approximate) "#FFC400" else "#00E5FF")
            ring.color = base; ring.alpha = (255 * (1 - phase)).toInt()
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 14f + 40f * phase, ring)
            dot.color = base; dot.alpha = 255
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot)
            dot.color = Color.WHITE; dot.alpha = 200
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 10f, dot.apply { style = Paint.Style.STROKE; strokeWidth = 2f })
            dot.style = Paint.Style.FILL
            c.drawText(pin.ssid, p.x + 18f, p.y + 8f, label)
        }
    }
}

object WifiGeolocator {
    private val pool = Executors.newFixedThreadPool(4)

    /** Looks up an access point's approximate real-world location via Mozilla's free,
     *  crowd-sourced WiFi geolocation service. Returns null if that BSSID isn't in its database. */
    fun locate(bssid: String, cb: (GeoPoint?) -> Unit) {
        pool.execute {
            val result = try {
                val url = URL("https://location.services.mozilla.com/v1/geolocate?key=test")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 6000
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("wifiAccessPoints", JSONArray().put(
                    JSONObject().put("macAddress", bssid)
                ))
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val loc = json.getJSONObject("location")
                    GeoPoint(loc.getDouble("lat"), loc.getDouble("lng"))
                } else null
            } catch (e: Exception) { null }
            Handler(Looper.getMainLooper()).post { cb(result) }
        }
    }
}

class MapScreen(ctx: Context, private val myLocation: GeoPoint, networks: List<ScanResult>) : FrameLayout(ctx) {
    private val map = MapView(ctx)
    private val pins = mutableListOf<ApPin>()
    private val pulse = PulseOverlay { pins.toList() }
    private val tickHandler = Handler(Looper.getMainLooper())

    init {
        Configuration.getInstance().userAgentValue = ctx.packageName
        val darkTiles = XYTileSource(
            "TonixDark", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/"
            )
        )
        map.setTileSource(darkTiles)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(myLocation)
        map.overlays.add(pulse)
        addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val note = TextView(ctx).apply {
            text = "📍 Locations are approximate (public WiFi database); unmapped networks are shown near you"
            setTextColor(Color.parseColor("#B2EBF2")); textSize = 11f; setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#CC05070D"))
        }
        addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))

        for (r in networks) {
            @Suppress("DEPRECATION") val ssid = r.SSID.ifEmpty { "<hidden>" }
            // Placeholder near device location until (or unless) a real position resolves.
            val jitter = 0.0006
            val fallback = GeoPoint(myLocation.latitude + Random.nextDouble(-jitter, jitter),
                myLocation.longitude + Random.nextDouble(-jitter, jitter))
            val pin = ApPin(ssid, fallback, approximate = true)
            pins.add(pin)
            WifiGeolocator.locate(r.BSSID) { real ->
                if (real != null) {
                    val idx = pins.indexOf(pin)
                    if (idx >= 0) pins[idx] = ApPin(ssid, real, approximate = false)
                    map.invalidate()
                }
            }
        }
        startPulse()
    }

    private fun startPulse() {
        tickHandler.post(object : Runnable {
            override fun run() { pulse.tick(); map.invalidate(); tickHandler.postDelayed(this, 40) }
        })
    }

    fun onResumeMap() = map.onResume()
    fun onPauseMap() = map.onPause()
}
