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
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FluidRenderer
    private lateinit var hud: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var hue = 0f

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

        pollRenderer()
    }

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
                hue = (hue + 0.13f) % 1f
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

                    val pressure = event.getPressure(i).coerceIn(0.05f, 1.5f)
                    val c = hsv(hue, 0.85f, 0.9f * pressure)
                    renderer.queueSplat(u, v, du, dv, c[0], c[1], c[2])

                    lastX[id] = x
                    lastY[id] = y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(event.actionIndex)
                lastX.remove(id)
                lastY.remove(id)
            }
        }
        return true
    }

    private fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val c = Color.HSVToColor(floatArrayOf(h * 360f, s, v))
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
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

        // --- buttons ---
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Clear") { renderer.clearRequested = true })
        row.addView(button("Pause") { b ->
            renderer.paused = !renderer.paused
            b.text = if (renderer.paused) "Play" else "Pause"
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
                hud.text = renderer.statsLine
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

    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onResume() { super.onResume(); glView.onResume() }
}
