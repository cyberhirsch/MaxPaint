package com.maxpaint.spike

import android.app.AlertDialog
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FluidRenderer
    private lateinit var hud: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var twoFingerDownAt = 0L
    private var tiltGravity = false
    private var versionLabel = ""
    private var sensors: SensorManager? = null

    // last touch position per pointer, for momentum
    private val lastX = HashMap<Int, Float>()
    private val lastY = HashMap<Int, Float>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = FluidRenderer(this)

        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        }.apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(buildHud())
        root.addView(buildControls())
        setContentView(root)

        versionLabel = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")

        sensors = getSystemService(SENSOR_SERVICE) as? SensorManager
        pollRenderer()
    }

    // PRD FR-8: the device's own tilt can drive where the paint runs.
    override fun onSensorChanged(event: SensorEvent) {
        if (!tiltGravity || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val g = 0.06f
        renderer.sim.flip.gravityX = -event.values[0] * g
        renderer.sim.flip.gravityY = -event.values[1] * g
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------- input ----------------

    private fun handleTouch(event: MotionEvent): Boolean {
        val w = glView.width.toFloat()
        val h = glView.height.toFloat()
        if (w <= 0f || h <= 0f) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                lastX[event.getPointerId(i)] = event.getX(i)
                lastY[event.getPointerId(i)] = event.getY(i)
                if (event.pointerCount == 2) twoFingerDownAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val px = lastX[id] ?: x
                    val py = lastY[id] ?: y

                    // UV space, y flipped: GL textures put v=0 at the bottom
                    val u = x / w
                    val v = 1f - y / h
                    // momentum: gesture velocity in UV units, scaled to something
                    // that reads as a strong push at 60Hz
                    val du = (x - px) / w * 12f
                    val dv = -(y - py) / h * 12f

                    // Black ink on white paper. Colour is premultiplied by
                    // coverage, so the rgb stays 0 and the alpha carries how
                    // much ink landed. Stylus pressure drives that alpha, and
                    // tilt widens the mark (PRD FR-6). A finger reports a
                    // pressure of about 1.0, so this is a no-op for touch.
                    val pressure = event.getPressure(i).let {
                        if (it <= 0f) 1f else it
                    }.coerceIn(0.15f, 1.6f)

                    val tilt = try {
                        event.getAxisValue(MotionEvent.AXIS_TILT, i)
                    } catch (_: IllegalArgumentException) {
                        0f
                    }
                    renderer.tiltSpread = 1f + tilt.coerceIn(0f, 1.4f) * 0.8f

                    renderer.queueSplat(u, v, du, dv, 0f, 0f, 0f, pressure)

                    lastX[id] = x
                    lastY[id] = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Two-finger tap commits the painting: quick, and without
                // travelling far enough to count as a stroke.
                val held = System.currentTimeMillis() - twoFingerDownAt
                if (event.pointerCount == 2 && twoFingerDownAt > 0L && held < 250) {
                    renderer.freezeRequested = true
                    Toast.makeText(this, "Frozen", Toast.LENGTH_SHORT).show()
                }
                twoFingerDownAt = 0L
                val id = event.getPointerId(event.actionIndex)
                lastX.remove(id)
                lastY.remove(id)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingerDownAt = 0L
                val id = event.getPointerId(event.actionIndex)
                lastX.remove(id)
                lastY.remove(id)
            }
        }
        return true
    }

    // ---------------- UI ----------------

    private fun buildHud(): TextView {
        hud = TextView(this).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
        val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        hud.layoutParams = lp
        return hud
    }

    private fun buildControls(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setPadding(20, 12, 20, 12)
        }

        // --- brush ---
        // Declared before the picker that toggles them.
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val flipRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val presetSpinner = Spinner(this)
        val presetRow = labeled("Preset", presetSpinner)

        val flipLabel = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Splashy 0.92  (\u2190 viscous)"
        }
        flipRow.addView(flipLabel)
        flipRow.addView(SeekBar(this).apply {
            max = 100
            progress = 92
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    renderer.sim.flip.flipRatio = p / 100f
                    flipLabel.text = String.format("Splashy %.2f  (\u2190 viscous)", p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        panel.addView(labeled("Brush", spinner(Brush.labels, 0) { idx ->
            val b = Brush.entries[idx]
            renderer.sim.brush = b
            modeRow.visibility = if (b == Brush.VORTEX) View.VISIBLE else View.GONE
            flipRow.visibility = if (b == Brush.FLIP) View.VISIBLE else View.GONE
            bindPresets(presetSpinner, b)
        }))

        modeRow.addView(TextView(this).apply {
            text = "Mode"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 16, 0)
        })
        modeRow.addView(spinner(ForceMode.labels, 0) { idx ->
            renderer.sim.forceMode = ForceMode.entries[idx]
        })
        panel.addView(presetRow)
        panel.addView(modeRow)
        panel.addView(flipRow)
        bindPresets(presetSpinner, Brush.GAS)


        // --- resolution ---
        val resLabels = FluidSim.RESOLUTIONS.map { "$it²" }
        panel.addView(labeled("Sim resolution", spinner(resLabels, 3) { idx ->
            renderer.pendingSimRes = FluidSim.RESOLUTIONS[idx]
        }))

        // --- dye scale ---
        panel.addView(labeled("Dye scale", spinner(listOf("1x", "2x"), 0) { idx ->
            renderer.pendingDyeScale = idx + 1
        }))

        // --- pressure iterations ---
        val iterLabel = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Pressure iters: 30"
        }
        val iterBar = SeekBar(this).apply {
            max = 75
            progress = 25   // 5 + 25 = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val iters = p + 5
                    renderer.sim.pressureIterations = iters
                    iterLabel.text = "Pressure iters: $iters"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(iterLabel)
        panel.addView(iterBar)

        // --- drag: the single dial that decides how fast paint sets ---
        val dragLabel = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "Drag: 0.12  (paint sets sooner →)"
        }
        val dragBar = SeekBar(this).apply {
            max = 100
            progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val d = p / 100f * 3f
                    renderer.sim.velocityDrag = d
                    dragLabel.text = String.format("Drag: %.2f  (paint sets sooner \u2192)", d)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        panel.addView(dragLabel)
        panel.addView(dragBar)

        panel.addView(slider("Set speed", 25, 100) { p, label ->
            val v = p / 100f * 10f
            renderer.sim.bakeRate = v
            label.text = String.format("Set speed: %.1f", v)
        })
        panel.addView(slider("Hold", 7, 100) { p, label ->
            val v = p / 100f * 5f
            renderer.sim.settleMinAge = v
            label.text = String.format("Hold: %.2f s", v)
        })

        // --- buttons ---
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Clear") { renderer.clearRequested = true })
        row.addView(button("Pause") { b ->
            renderer.paused = !renderer.paused
            b.text = if (renderer.paused) "Play" else "Pause"
        })
        row.addView(button("Freeze") {
            renderer.freezeRequested = true
        })
        row.addView(button("Tilt") { b ->
            tiltGravity = !tiltGravity
            if (!tiltGravity) {
                renderer.sim.flip.gravityX = 0f
                renderer.sim.flip.gravityY = -0.55f
            }
            b.text = if (tiltGravity) "Tilt on" else "Tilt"
        })
        row.addView(button("Thaw") {
            renderer.thawRequested = true
        })
        row.addView(button("Heat") { b ->
            renderer.heatOverlay = !renderer.heatOverlay
            b.text = if (renderer.heatOverlay) "Paint" else "Heat"
        })
        row.addView(button("Vel") { b ->
            renderer.debugView = 1 - renderer.debugView
            b.text = if (renderer.debugView == 1) "Dye" else "Vel"
        })
        row.addView(button("RB-GS") { b ->
            renderer.sim.useRedBlack = !renderer.sim.useRedBlack
            b.text = if (renderer.sim.useRedBlack) "RB-GS" else "Jacobi"
        })
        row.addView(button("Sweep") {
            Toast.makeText(this, "Running resolution sweep…", Toast.LENGTH_SHORT).show()
            renderer.benchmarkRequested = true
        })
        panel.addView(row)

        val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        panel.layoutParams = lp
        return panel
    }

    /** A labelled slider whose label updates as it moves. */
    private fun slider(name: String, initial: Int, max: Int, onChange: (Int, TextView) -> Unit): View {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = name
        }
        val bar = SeekBar(this).apply {
            this.max = max
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) =
                    onChange(p, label)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        onChange(initial, label)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar)
        }
    }

    private fun labeled(label: String, v: View): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ll.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 16, 0)
        })
        ll.addView(v)
        return ll
    }

    /** Repoints the preset picker at whichever medium is now selected. */
    private fun bindPresets(sp: Spinner, brush: Brush) {
        val presets = Presets.forBrush(brush)
        sp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.label }
        )
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                presets.getOrNull(pos)?.apply?.invoke(renderer.sim)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sp.setSelection(0)
    }

    private fun spinner(items: List<String>, initial: Int, onPick: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items
            )
            setSelection(initial)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

    private fun button(label: String, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener { onClick(this) }
        }

    // ---------------- polling ----------------

    private fun pollRenderer() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                hud.text = if (versionLabel.isEmpty()) renderer.statsLine
                           else "MaxPaint ${'$'}versionLabel\n${'$'}{renderer.statsLine}"
                renderer.benchmarkReport?.let {
                    renderer.benchmarkReport = null
                    showReport(it)
                }
                ui.postDelayed(this, 250)
            }
        }, 250)
    }

    private fun showReport(report: String) {
        val file = File(getExternalFilesDir(null), "maxpaint-sweep.txt")
        runCatching { file.writeText(report) }

        val body = TextView(this).apply {
            text = report
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Resolution headroom")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Log") { _, _ ->
                android.util.Log.i("MaxPaintSweep", "\n$report")
                Toast.makeText(this, "Written to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        sensors?.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
}
