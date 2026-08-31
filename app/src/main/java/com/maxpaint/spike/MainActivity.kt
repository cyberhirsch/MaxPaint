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

/**
 * Canvas first. Tools live in a narrow rail; the settings panel stays shut until
 * you tap the tool you already have selected. Nothing else covers the paint.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FluidRenderer
    private lateinit var hud: TextView
    private lateinit var rail: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelBody: LinearLayout
    private lateinit var panelTitle: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var versionLabel = ""

    private var selected: Brush = Brush.GAS
    private var panelOpen = false
    private var showingGlobal = false

    private val toolButtons = HashMap<Brush, Button>()

    private lateinit var layerRail: LinearLayout
    private lateinit var layerPanel: LinearLayout
    private lateinit var layerPanelBody: LinearLayout
    private var layerPanelOpen = false
    private var lastStack = -1 to -1

    private val lastX = HashMap<Int, Float>()
    private val lastY = HashMap<Int, Float>()

    /** Distance walked since the last dab, per pointer. */
    private val carry = HashMap<Int, Float>()
    private var twoFingerDownAt = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Bounds the work one touch event can queue. */
    private val MAX_DABS = 64

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = FluidRenderer(this)

        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        }.apply {
            setEGLContextClientVersion(3)
            // Backgrounding the app otherwise destroys the GL context, and the
            // painting lives in GL textures: coming back would mean starting
            // over even with a perfect recreation path. Preservation is
            // best-effort per device, so the recreation path stays correct
            // regardless -- see FlipSystem.init().
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(buildHud())
        root.addView(buildPanel())
        root.addView(buildRail())
        root.addView(buildLayerPanel())
        root.addView(buildLayerRail())
        setContentView(root)

        renderer.onExported = { bitmap ->
            // compression is slow enough to stutter the canvas; keep it off both
            // the GL thread and the UI thread
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            Thread {
                val msg = PngExport.save(applicationContext, bitmap, "maxpaint-$stamp")
                ui.post { toast(msg) }
            }.start()
        }

        versionLabel = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")

        refreshLayerRail()
        selectTool(Brush.GAS, fromUser = false)
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
                // banked in full, so the first dab lands on the touch point
                // rather than one spacing into the stroke
                carry[event.getPointerId(i)] = renderer.sim.stampSpacing
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    renderer.queueStrokeBegin()
                    hold(event.getX(i), event.getY(i), w, h)
                }
                if (event.pointerCount == 2) twoFingerDownAt = System.currentTimeMillis()
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    // Tilt widens the mark; it is a property of the pen, not of
                    // any one sample, so it is set once for the batch.
                    renderer.tiltSpread = 1f + runCatching {
                        event.getAxisValue(MotionEvent.AXIS_TILT, i)
                    }.getOrDefault(0f).coerceIn(0f, 1.4f) * 0.8f

                    // Android batches several positions into one MOVE event.
                    // Reading only the last one throws away most of what the
                    // digitiser reported and corners get cut across.
                    for (hIdx in 0 until event.historySize) {
                        strokeTo(id,
                                 event.getHistoricalX(i, hIdx),
                                 event.getHistoricalY(i, hIdx),
                                 event.getHistoricalPressure(i, hIdx), w, h)
                    }
                    strokeTo(id, event.getX(i), event.getY(i),
                             event.getPressure(i), w, h)
                    if (i == 0) hold(event.getX(i), event.getY(i), w, h)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val held = System.currentTimeMillis() - twoFingerDownAt
                if (event.pointerCount == 2 && twoFingerDownAt > 0L && held < 250) {
                    renderer.freezeRequested = true
                    toast("Frozen")
                }
                twoFingerDownAt = 0L
                releasePointer(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingerDownAt = 0L
                releasePointer(event)
                renderer.holding = false
                renderer.sim.endPour()
                renderer.queueStrokeEnd()
            }
        }
        return true
    }

    /**
     * Walks from where this pointer was to where it now is, stamping at a fixed
     * spacing in canvas units. A stroke is a path, not the handful of points the
     * digitiser happened to report: sampling per event alone beads the mark, and
     * the faster the stroke the wider the gaps.
     */
    private fun strokeTo(id: Int, x: Float, y: Float, rawPressure: Float,
                         w: Float, h: Float) {
        val px = lastX[id] ?: x
        val py = lastY[id] ?: y
        lastX[id] = x
        lastY[id] = y

        // UV space, y flipped: GL textures put v=0 at the bottom
        val u = x / w
        val v = 1f - y / h
        val pu = px / w
        val pv = 1f - py / h

        // Pressure scales how much ink lands. A finger reports about 1.0, so
        // touch is unaffected.
        val pressure = rawPressure
            .let { if (it <= 0f) 1f else it }
            .coerceIn(0.15f, 1.6f)

        val sim = renderer.sim

        // The nib and smear draw a capsule across the whole segment, so they
        // are continuous already and want one call per reported point.
        if (!sim.stampsDabs) {
            renderer.queueSplat(u, v, (u - pu) * 12f, (v - pv) * 12f,
                                0f, 0f, 0f, pressure, pu, pv)
            return
        }

        // x is scaled by the aspect so distance matches what the shaders measure
        val dx = (u - pu) * sim.canvasAspect
        val dy = v - pv
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist <= 0f) return

        val spacing = sim.stampSpacing
        // Distance carried over from the last segment. Without it the spacing
        // would restart at every touch event and the dab count -- and so the
        // weight of the mark -- would follow the report rate again.
        var next = spacing - (carry[id] ?: 0f)

        // one dab's share of the segment's momentum, so the impulse a segment
        // delivers is what it was when this was a single splat per event
        val impulse = (spacing / dist).coerceAtMost(1f)
        val du = (u - pu) * 12f * impulse
        val dv = (v - pv) * 12f * impulse
        val ink = pressure * sim.inkPerDab

        var stamps = 0
        while (next <= dist && stamps < MAX_DABS) {
            val t = next / dist
            val prevT = ((next - spacing) / dist).coerceAtLeast(0f)
            renderer.queueSplat(
                pu + (u - pu) * t, pv + (v - pv) * t,
                du, dv, 0f, 0f, 0f, ink,
                pu + (u - pu) * prevT, pv + (v - pv) * prevT
            )
            next += spacing
            stamps++
        }
        // MAX_DABS caps one absurd jump -- a pointer reappearing across the
        // canvas. The mark thins there rather than locking up the frame.
        carry[id] = if (stamps < MAX_DABS) dist - (next - spacing) else 0f
    }

    /** The point paint pours from while a finger rests there. */
    private fun hold(x: Float, y: Float, w: Float, h: Float) {
        renderer.heldU = x / w
        renderer.heldV = 1f - y / h
        renderer.holding = true
    }

    private fun releasePointer(event: MotionEvent) {
        val id = event.getPointerId(event.actionIndex)
        lastX.remove(id)
        carry.remove(id)
        lastY.remove(id)
    }

    // ---------------- the rail ----------------

    private fun buildRail(): View {
        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        Brush.entries.forEach { b ->
            val btn = toolButton(b.short) {
                if (selected == b && !showingGlobal) togglePanel() else selectTool(b, true)
            }
            toolButtons[b] = btn
            rail.addView(btn)
        }

        rail.addView(divider())
        rail.addView(toolButton("undo") {
            if (renderer.sim.canUndo) renderer.undoRequested = true else toast("Nothing to undo")
        })
        rail.addView(toolButton("redo") {
            if (renderer.sim.canRedo) renderer.redoRequested = true else toast("Nothing to redo")
        })
        rail.addView(toolButton("set") {
            if (showingGlobal && panelOpen) togglePanel() else showGlobal()
        })
        rail.addView(toolButton("clr") { renderer.clearRequested = true })
        rail.addView(toolButton("frz") { renderer.freezeRequested = true; toast("Frozen") })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(rail)
        }
        val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        scroll.layoutParams = lp
        return scroll
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).also {
            it.topMargin = dp(3); it.bottomMargin = dp(3)
        }
        setBackgroundColor(Color.argb(90, 255, 255, 255))
    }

    /** Deliberately small: the canvas matters more than the chrome. */
    private fun toolButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minHeight = 0
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(34)).also {
                it.bottomMargin = dp(2)
            }
            setOnClickListener { onClick() }
        }

    private fun selectTool(b: Brush, fromUser: Boolean) {
        selected = b
        showingGlobal = false
        renderer.sim.brush = b
        toolButtons.forEach { (brush, btn) ->
            btn.alpha = if (brush == b) 1f else 0.55f
        }
        if (panelOpen) showToolSettings()
        if (fromUser) toast(b.label)
    }

    // ---------------- layers, on the right ----------------

    /**
     * Layer work touches GL objects, so it has to happen on the GL thread. The
     * rail is redrawn afterwards, once the change has actually been made.
     */
    private fun onGl(work: () -> Unit) {
        glView.queueEvent {
            work()
            ui.post { refreshLayerRail() }
        }
    }

    private fun buildLayerRail(): View {
        layerRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(layerRail)
        }
        val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        scroll.layoutParams = lp
        return scroll
    }

    /**
     * Top of the stack sits at the top of the rail, so the buttons read the way
     * the paint is layered rather than in index order.
     */
    private fun refreshLayerRail() {
        if (!::layerRail.isInitialized) return
        layerRail.removeAllViews()

        layerRail.addView(toolButton("png") {
            toast("Saving…")
            renderer.exportRequested = true
        })
        layerRail.addView(divider())

        // snapshot: the stack itself is owned by the GL thread
        val layers = ArrayList(renderer.sim.layers)
        val active = renderer.sim.activeLayer
        for (i in layers.indices.reversed()) {
            val layer = layers[i]
            val mark = if (!layer.visible) "·" else "${i + 1}"
            val btn = toolButton(mark) {
                if (i == active) toggleLayerPanel() else onGl { renderer.sim.activeLayer = i }
            }
            btn.alpha = if (i == active) 1f else 0.55f
            layerRail.addView(btn)
        }

        if (layers.size < renderer.sim.maxLayers) {
            layerRail.addView(toolButton("+") {
                onGl { renderer.sim.addLayer() }
            })
        }

        if (layerPanelOpen) showLayerSettings()
    }

    private fun buildLayerPanel(): View {
        layerPanelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(layerPanelBody)
        }

        layerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(205, 16, 16, 20))
            visibility = View.GONE
            addView(ScrollView(this@MainActivity).apply { addView(inner) })
        }

        val lp = FrameLayout.LayoutParams(dp(240), MATCH_PARENT)
        lp.gravity = Gravity.END
        lp.rightMargin = dp(46)
        layerPanel.layoutParams = lp
        return layerPanel
    }

    private fun toggleLayerPanel() {
        layerPanelOpen = !layerPanelOpen
        layerPanel.visibility = if (layerPanelOpen) View.VISIBLE else View.GONE
        if (layerPanelOpen) showLayerSettings()
    }

    private fun showLayerSettings() {
        val sim = renderer.sim
        val layer = sim.layers.getOrNull(sim.activeLayer) ?: return
        layerPanelBody.removeAllViews()

        layerPanelBody.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(6))
            text = "${layer.name} — tap the layer again to close"
        })

        layerPanelBody.addView(slider("Opacity", (layer.opacity * 100).toInt(), 100) { p, l ->
            l.text = "Opacity: $p%"
            onGl {
                layer.opacity = p / 100f
                sim.layersDirty = true
            }
        })

        layerPanelBody.addView(rowOfButtons(
            "hide" to {
                onGl { layer.visible = !layer.visible; sim.layersDirty = true }
            },
            "down" to { onGl { sim.moveActiveLayer(-1) } },
            "up" to { onGl { sim.moveActiveLayer(1) } }
        ))

        layerPanelBody.addView(rowOfButtons(
            "wipe" to { onGl { sim.clearActiveLayer() } },
            "del" to {
                if (sim.layers.size <= 1) toast("The last layer stays")
                else confirmDelete(layer.name)
            }
        ))

        layerPanelBody.addView(TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, dp(8), 0, 0)
            text = "Paint always goes onto the selected layer. " +
                   "‘png’ saves the whole stack at canvas resolution. " +
                   "Undo covers strokes and wipes; adding, deleting or " +
                   "reordering layers starts history over."
        })
    }

    /** Deleting a layer cannot be undone, so it asks first. */
    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setMessage("The paint on it goes with it. This cannot be undone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ -> onGl { renderer.sim.deleteActiveLayer() } }
            .show()
    }

    private fun checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View =
        android.widget.CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun rowOfButtons(vararg items: Pair<String, () -> Unit>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        items.forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minHeight = 0
                layoutParams = LinearLayout.LayoutParams(dp(62), dp(36)).also {
                    it.rightMargin = dp(4)
                }
                setOnClickListener { action() }
            })
        }
        return row
    }

    // ---------------- the settings panel ----------------

    private fun buildPanel(): View {
        panelTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(6))
        }
        panelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(panelTitle)
            addView(panelBody)
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(205, 16, 16, 20))
            visibility = View.GONE
            addView(ScrollView(this@MainActivity).apply { addView(inner) })
        }

        val lp = FrameLayout.LayoutParams(dp(260), MATCH_PARENT)
        lp.gravity = Gravity.START
        lp.leftMargin = dp(46)
        panel.layoutParams = lp
        return panel
    }

    private fun togglePanel() {
        panelOpen = !panelOpen
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        if (panelOpen) showToolSettings()
    }

    private fun openPanel() {
        panelOpen = true
        panel.visibility = View.VISIBLE
    }

    private fun showToolSettings() {
        showingGlobal = false
        panelTitle.text = "${selected.label} — tap the tool again to close"
        panelBody.removeAllViews()

        val presets = Presets.forBrush(selected)
        if (presets.size > 1) {
            panelBody.addView(labeled("Preset",
                spinner(presets.map { it.label }, 0, fireOnInit = false) { i ->
                    presets[i].apply(renderer.sim)
                }))
        }

        // Load is meaningful for every tool: it scales whatever that medium puts
        // down, or for the tools that deposit nothing, how hard they act.
        panelBody.addView(slider("Load", (renderer.sim.inkPerStroke * 100).toInt(), 400) { p, l ->
            renderer.sim.inkPerStroke = p / 100f
            l.text = String.format(
                if (selected.carriesPigment) "Load: %.2f  (opacity of the mark)"
                else "Load: %.2f  (how hard it acts)", p / 100f
            )
        })

        // Nib and smear carry their own size controls; everything else drives
        // the shared footprint, and every one of them needs its own.
        if (selected != Brush.NIB && selected != Brush.SMEAR) {
            panelBody.addView(slider("Brush size",
                                     (renderer.sim.splatRadius * 1000).toInt(), 100) { p, l ->
                renderer.sim.splatRadius = p / 1000f
                l.text = String.format("Brush size: %.3f", p / 1000f)
            })
        }

        when (selected) {
            Brush.GAS -> {
                panelBody.addView(slider("Swirl", renderer.sim.vorticity.toInt(), 60) { p, l ->
                    renderer.sim.vorticity = p.toFloat()
                    l.text = "Swirl: $p"
                })
            }

            Brush.NIB -> {
                panelBody.addView(slider("Nib size", (renderer.sim.nibRadius * 1000).toInt(), 40) { p, l ->
                    renderer.sim.nibRadius = p / 1000f
                    l.text = String.format("Nib size: %.3f", p / 1000f)
                })
                panelBody.addView(slider("Sharpness", (renderer.sim.nibHardness * 100).toInt(), 100) { p, l ->
                    renderer.sim.nibHardness = p / 100f
                    l.text = String.format("Sharpness: %.2f", p / 100f)
                })
                panelBody.addView(slider("Soak", (renderer.sim.nibSoak / 5f * 100).toInt(), 100) { p, l ->
                    renderer.sim.nibSoak = p / 100f * 5f
                    l.text = String.format("Soak: %.2f", p / 100f * 5f)
                })
                panelBody.addView(slider("Dry", (renderer.sim.nibDry / 3f * 100).toInt(), 100) { p, l ->
                    renderer.sim.nibDry = p / 100f * 3f
                    l.text = String.format("Dry: %.2f", p / 100f * 3f)
                })
                panelBody.addView(slider("Paper grain", (renderer.sim.nibGrain * 100).toInt(), 100) { p, l ->
                    renderer.sim.nibGrain = p / 100f
                    l.text = String.format("Paper grain: %.2f", p / 100f)
                })
            }

            Brush.FLIP -> {
                // Coarse on purpose. A cell has to hold several particles or
                // the pressure solve couples each one to nothing; measured
                // coupling peaks near eight per occupied cell.
                panelBody.addView(slider("Flow", renderer.sim.flip.flowRate.toInt(), 40) { p, l ->
                    renderer.sim.flip.flowRate = p.toFloat()
                    l.text = if (p == 0) "Flow: 0  (a still finger paints nothing)"
                             else "Flow: $p dabs/s held  " +
                                  "(~${p * renderer.sim.flip.countFor(
                                      renderer.sim.splatRadius * 0.5f,
                                      renderer.sim.canvasAspect) / 1000}k particles/s)"
                })
                panelBody.addView(slider("Volume",
                                         (renderer.sim.flip.compression * 1000).toInt(), 100) { p, l ->
                    renderer.sim.flip.compression = p / 1000f
                    l.text = if (p == 0) "Volume: 0  (paint stacks where it lands)"
                             else String.format("Volume: %.3f  (a full cell pushes back)", p / 1000f)
                })
                panelBody.addView(slider("Travel",
                                         (renderer.sim.flip.particleDrag * 50).toInt(), 200) { p, l ->
                    renderer.sim.flip.particleDrag = p / 50f
                    l.text = String.format("Travel: %.2f drag  (← flies further)", p / 50f)
                })
                panelBody.addView(slider("Coupling", (512 - renderer.sim.flipRes) / 8, 56) { p, l ->
                    val res = 512 - p * 8
                    onGl { renderer.sim.reshapeFlipGrid(res) }
                    l.text = "Coupling: grid ${res}  (← finer, thicker →)"
                })
                panelBody.addView(slider("Density", renderer.sim.flip.particlesPerCell.toInt(), 400) { p, l ->
                    val d = p.coerceAtLeast(1).toFloat()
                    renderer.sim.flip.particlesPerCell = d
                    val n = renderer.sim.flip.countFor(
                        renderer.sim.splatRadius * 0.5f, renderer.sim.canvasAspect)
                    l.text = "Density: ${d.toInt()}  ($n particles per dab)"
                })
                panelBody.addView(slider("Pressure", renderer.sim.flipIterations, 500) { p, l ->
                    renderer.sim.flipIterations = p.coerceAtLeast(4)
                    l.text = "Pressure: ${p.coerceAtLeast(4)} sweeps"
                })
                panelBody.addView(slider("Cohesion", renderer.sim.flip.cohesion.toInt(), 200) { p, l ->
                    renderer.sim.flip.cohesion = p.toFloat()
                    l.text = "Cohesion: $p  (beads up →)"
                })
                panelBody.addView(slider("Motion inheritance",
                                         (renderer.sim.flip.flipRatio * 100).toInt(), 100) { p, l ->
                    renderer.sim.flip.flipRatio = p / 100f
                    l.text = "Motion inheritance: $p%" + when {
                        p == 0 -> "  (takes the grid's)"
                        p >= 100 -> "  (keeps all of its own)"
                        else -> ""
                    }
                })
                panelBody.addView(slider("Drop size", (renderer.sim.flip.pointSize * 2).toInt(), 96) { p, l ->
                    renderer.sim.flip.pointSize = p / 2f
                    l.text = String.format("Drop size: %.1f px", p / 2f)
                })
                panelBody.addView(hint("Paint travels on the momentum of the " +
                    "stroke. There is no gravity — a canvas has no up."))
                panelBody.addView(button("How many particles fit?") {
                    AlertDialog.Builder(this)
                        .setTitle("Measure particle headroom?")
                        .setMessage("Fills the canvas with particles at several " +
                            "counts and times them. Clears the canvas.")
                        .setPositiveButton("Run") { _, _ ->
                            toast("Measuring…")
                            renderer.particleBenchmarkRequested = true
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }

            Brush.WATERCOLOR -> {
                panelBody.addView(slider("Wetness", (renderer.sim.wcLoadWater * 100).toInt(), 150) { p, l ->
                    renderer.sim.wcLoadWater = p / 100f
                    l.text = String.format("Wetness: %.2f", p / 100f)
                })
                panelBody.addView(slider("Pigment", (renderer.sim.wcLoadPigment * 100).toInt(), 100) { p, l ->
                    renderer.sim.wcLoadPigment = p / 100f
                    l.text = String.format("Pigment: %.2f", p / 100f)
                })
                panelBody.addView(slider("Dry rate", (renderer.sim.wcEvaporate * 100).toInt(), 100) { p, l ->
                    renderer.sim.wcEvaporate = p / 100f
                    l.text = String.format("Dry rate: %.2f", p / 100f)
                })
            }

            Brush.VORTEX -> {
                panelBody.addView(labeled("Mode", spinner(
                    ForceMode.labels, renderer.sim.forceMode.ordinal
                ) { i -> renderer.sim.forceMode = ForceMode.entries[i] }))
                panelBody.addView(slider("Strength", (renderer.sim.forceStrength * 10).toInt(), 40) { p, l ->
                    renderer.sim.forceStrength = p / 10f
                    l.text = String.format("Strength: %.1f", p / 10f)
                })
                panelBody.addView(slider("Pickup", (renderer.sim.pickup * 10).toInt(), 80) { p, l ->
                    renderer.sim.pickup = p / 10f
                    l.text = String.format("Pickup: %.1f  (lifts set paint)", p / 10f)
                })
            }

            Brush.SOLVENT -> {
                panelBody.addView(slider("Bite", (renderer.sim.solventBite * 100).toInt(), 100) { p, l ->
                    renderer.sim.solventBite = p / 100f
                    l.text = String.format("Bite: %.2f  (lower bites harder)", p / 100f)
                })
                panelBody.addView(slider("Pickup", (renderer.sim.pickup * 10).toInt(), 80) { p, l ->
                    renderer.sim.pickup = p / 10f
                    l.text = String.format("Pickup: %.1f  (lifts set paint)", p / 10f)
                })
            }

            Brush.SMEAR -> {
                panelBody.addView(slider("Finger size", (renderer.sim.smearRadius * 1000).toInt(), 200) { p, l ->
                    renderer.sim.smearRadius = p / 1000f
                    l.text = String.format("Finger size: %.3f", p / 1000f)
                })
                panelBody.addView(slider("Grab", (renderer.sim.smearStrength * 100).toInt(), 100) { p, l ->
                    renderer.sim.smearStrength = p / 100f
                    l.text = String.format("Grab: %.2f  (how much it takes with it)", p / 100f)
                })
                panelBody.addView(slider("Drag", (renderer.sim.smearReach * 1000).toInt(), 250) { p, l ->
                    renderer.sim.smearReach = p / 1000f
                    l.text = String.format("Drag: %.3f  (how far it pulls)", p / 1000f)
                })
                panelBody.addView(hint("Moves pigment that is already down. " +
                    "It adds none of its own."))
            }

            Brush.FREEZE, Brush.THAW -> {
                panelBody.addView(hint("Paint over the canvas to " +
                    (if (selected == Brush.FREEZE) "set" else "lift") + " just that area."))
            }

        }

        panelBody.addView(divider())
        panelBody.addView(hint("Two-finger tap freezes the whole canvas."))
    }

    private fun showGlobal() {
        openPanel()
        showingGlobal = true
        panelTitle.text = "Settings — tap ‘set’ again to close"
        panelBody.removeAllViews()

        val resLabels = FluidSim.RESOLUTIONS.map { "$it" }
        panelBody.addView(labeled("Detail", spinner(
            resLabels,
            FluidSim.RESOLUTIONS.indexOf(renderer.pendingSimRes).coerceAtLeast(0)
        ) { i -> renderer.pendingSimRes = FluidSim.RESOLUTIONS[i] }))
        panelBody.addView(labeled("Ink detail", spinner(
            listOf("1x", "2x"), renderer.pendingDyeScale - 1
        ) { i -> renderer.pendingDyeScale = i + 1 }))
        panelBody.addView(slider("Solver sweeps", renderer.sim.pressureIterations - 5, 75) { p, l ->
            renderer.sim.pressureIterations = p + 5
            l.text = "Solver sweeps: ${p + 5}"
        })

        panelBody.addView(divider())
        panelBody.addView(hint("How paint sets"))
        panelBody.addView(slider("Drag", (renderer.sim.velocityDrag / 3f * 100).toInt(), 100) { p, l ->
            renderer.sim.velocityDrag = p / 100f * 3f
            l.text = String.format("Drag: %.2f  (paint sets sooner →)", p / 100f * 3f)
        })
        panelBody.addView(slider("Set speed", (renderer.sim.bakeRate / 10f * 100).toInt(), 100) { p, l ->
            renderer.sim.bakeRate = p / 100f * 10f
            l.text = String.format("Set speed: %.1f%s", p / 100f * 10f,
                if (p == 0) "  (never sets)" else "")
        })
        panelBody.addView(slider("Hold", (renderer.sim.settleMinAge / 5f * 100).toInt(), 100) { p, l ->
            renderer.sim.settleMinAge = p / 100f * 5f
            l.text = String.format("Hold: %.2f s", p / 100f * 5f)
        })

        panelBody.addView(divider())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Pause") { b ->
            renderer.paused = !renderer.paused
            b.text = if (renderer.paused) "Play" else "Pause"
        })
        row.addView(button("Thaw") { renderer.thawRequested = true })
        row.addView(button("Heat") { b ->
            renderer.heatOverlay = !renderer.heatOverlay
            b.text = if (renderer.heatOverlay) "Paint" else "Heat"
        })
        panelBody.addView(row)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Velocity") { b ->
            renderer.debugView = 1 - renderer.debugView
            b.text = if (renderer.debugView == 1) "Paint" else "Velocity"
        })
        row2.addView(button("Sweep") {
            // it reallocates at every resolution, so the canvas does not survive
            AlertDialog.Builder(this)
                .setTitle("Run the resolution sweep?")
                .setMessage("This measures speed and solver quality at every " +
                    "resolution. It clears the canvas and takes a few seconds.")
                .setPositiveButton("Run") { _, _ ->
                    toast("Running resolution sweep…")
                    renderer.benchmarkRequested = true
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        panelBody.addView(row2)


    }

    // ---------------- small widgets ----------------

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.argb(170, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun slider(name: String, initial: Int, max: Int,
                       enabled: Boolean = true,
                       onChange: (Int, TextView) -> Unit): View {
        val label = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = name
            alpha = if (enabled) 1f else 0.4f
        }
        val bar = SeekBar(this).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ll.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, dp(8), 0)
        })
        ll.addView(v)
        return ll
    }

    /**
     * [fireOnInit] false suppresses the callback Android fires for the initial
     * selection. Without it, merely opening a panel re-applied preset 0 and
     * wiped whatever was chosen before.
     */
    private fun spinner(
        items: List<String>,
        initial: Int,
        fireOnInit: Boolean = true,
        onPick: (Int) -> Unit
    ): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items
            )
            setSelection(initial.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            var seen = fireOnInit
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (seen) onPick(pos) else seen = true
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

    private fun button(label: String, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setOnClickListener { onClick(this) }
        }

    private fun buildHud(): TextView {
        hud = TextView(this).apply {
            setTextColor(Color.argb(150, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        val lp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.END
        hud.layoutParams = lp
        return hud
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------------- lifecycle ----------------

    private fun pollRenderer() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                hud.text = buildString {
                    if (versionLabel.isNotEmpty()) append(versionLabel).append('\n')
                    append(renderer.statsLine)
                }
                renderer.benchmarkReport?.let {
                    renderer.benchmarkReport = null
                    showReport(it)
                }
                // the stack is built on the GL thread, and reallocating the
                // canvas rebuilds it, so the rail follows rather than leads
                val stack = renderer.sim.layers.size to renderer.sim.activeLayer
                if (stack != lastStack) {
                    lastStack = stack
                    refreshLayerRail()
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
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Resolution headroom")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("OK", null)
            .setNeutralButton("Log") { _, _ ->
                android.util.Log.i("MaxPaintSweep", "\n$report")
                toast("Written to ${file.absolutePath}")
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}
