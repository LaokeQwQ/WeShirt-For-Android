package com.laoke.weshirt.room

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceView
import android.view.View as AndroidView
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Native SurfaceView/Filament room. All public calls must run on the Android main thread. */
class LaundryRoomView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), Choreographer.FrameCallback {

    var onMachineSelected: ((String) -> Unit)? = null
    var onQualityChanged: ((QualityState) -> Unit)? = null
    var onRendererError: ((String) -> Unit)? = null
    val qualityState: QualityState get() = controller.state()
    val renderStatistics: RoomRenderStatistics get() = RoomRenderStatistics(
        renderedFrames, rejectedFrames, engine != null, swapChain != null && uiHelper?.isReadyToRender == true,
        materials.size, meshes.size, machines.size, latestGpuDurationNanos, rendererFailure, disposed,
        pendingMaterialCompilations, nativeDiagnostics(),
    )

    private val surfaceView = SurfaceView(context)
    private val controller = AdaptiveFrameController()
    private val choreographer = Choreographer.getInstance()
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var uiHelper: UiHelper? = null
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var cameraEntity = 0
    private var lightEntity = 0
    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private var swapChain: SwapChain? = null
    private val materials = mutableListOf<Material>()
    private val instances = mutableMapOf<RoomMaterial, MaterialInstance>()
    private val meshes = mutableListOf<NativeMesh>()
    private val environmentMeshes = mutableListOf<NativeMesh>()
    private val bodyMeshes = mutableListOf<NativeMesh>()
    private val statusMeshes = mutableMapOf<String, NativeMesh>()
    private val garmentMeshes = mutableMapOf<String, NativeMesh>()
    private var machines = emptyMap<String, RoomMachine>()
    private var positions = emptyList<RoomSlot>()
    private var extraDepth = 0f
    private val frameHistory = Array(16) { Renderer.FrameInfo() }
    private var lastGpuFrame = -1
    private var renderedFrames = 0L
    private var rejectedFrames = 0L
    private var pendingMaterialCompilations = 0
    private var initialSceneCompilationQueued = false
    private var latestGpuDurationNanos: Long? = null
    private var lastQuality: QualityState? = null
    private var lastAppliedFps = -1
    private var lastAppliedScale = -1f
    private var nextFrameNanos = 0L
    private var previousFrameNanos = 0L
    private var callbackScheduled = false
    private var dirty = true
    private var urgent = true
    private var resumed = true
    private var disposed = false
    private var observersRegistered = false
    private var cleaningRenderer = false
    private var rendererFailurePending = false
    private var rendererFailure: String? = null
    private var layoutVersion = 0L
    private var gestureLayoutVersion = -1L
    private var yaw = .18
    private var pitch = .46
    private var zoom = 1.0
    private var yawVelocity = 0.0
    private var touching = false
    private var lastScaleAt = 0L
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayPeriodNanos = 16_666_667L
    private var eye = Vec3(0.0, 6.0, 12.0)
    private var target = Vec3(0.0, .8, -.15)
    private val transform = FloatArray(16)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshEnvironment(); requestFrame(immediate = true) }
    }
    private val thermalListener = if (Build.VERSION.SDK_INT >= 29) {
        PowerManager.OnThermalStatusChangedListener { status ->
            controller.setThermalStatus(status)
            requestFrame(immediate = true)
        }
    } else null

    private val scaling = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom / detector.scaleFactor).coerceIn(.46, 1.7)
            yawVelocity = 0.0
            lastScaleAt = SystemClock.uptimeMillis()
            requestFrame()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean { yawVelocity = 0.0; return true }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaling.isInProgress && current.pointerCount == 1) {
                yaw = (yaw + distanceX * .0035).coerceIn(-1.12, 1.12)
                pitch = (pitch + distanceY * .0030).coerceIn(.16, 1.04)
                requestFrame()
            }
            return true
        }
        override fun onFling(first: MotionEvent?, current: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!scaling.isInProgress && SystemClock.uptimeMillis() - lastScaleAt >= 300L && motionEnabled())
                yawVelocity = (-velocityX * .0017).coerceIn(-2.0, 2.0)
            requestFrame()
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!canRender() || gestureLayoutVersion != layoutVersion || SystemClock.uptimeMillis() - lastScaleAt < 300L) return false
            performClick()
            val ray = RoomLayout.screenRay(event.x, event.y, surfaceView.width, surfaceView.height, eye, target, FOV)
            val slot = RoomLayout.pick(ray, positions) ?: return true
            machines[slot]?.let { onMachineSelected?.invoke(it.id) }
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean { resetCamera(); return true }
    })

    init {
        contentDescription = "原生三维洗衣房，可拖动旋转、双指缩放、双击复位，点按机器查看详情"
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        surfaceView.setOnTouchListener { _, event ->
            touching = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gestureLayoutVersion = layoutVersion; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_POINTER_DOWN -> lastScaleAt = SystemClock.uptimeMillis()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            scaling.onTouchEvent(event)
            gestures.onTouchEvent(event)
            requestFrame()
            true
        }
        runCatching { initializeRenderer() }.onFailure { error ->
            failRenderer(error)
            addView(TextView(context).apply {
                text = "当前设备暂时无法开启三维场景，请切换列表查看机器。"
                gravity = android.view.Gravity.CENTER
                setPadding(32, 32, 32, 32)
                contentDescription = text
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun onMachineSelected(listener: (String) -> Unit) { onMachineSelected = listener }

    /** Door center projected into view coordinates, also usable by native accessibility overlays. */
    fun machineScreenPosition(machineId: String): PointF? {
        if(viewportWidth <= 0 || viewportHeight <= 0 || disposed) return null
        val position = positions.firstOrNull { it.slot == machineId } ?: return null
        val forward = (target - eye).normalized()
        val right = forward.cross(Vec3(0.0, 1.0, 0.0)).normalized()
        val up = right.cross(forward).normalized()
        val relative = Vec3(position.x.toDouble(), .8, position.z + .58) - eye
        val depth = relative.dot(forward)
        if(depth <= 0) return null
        val halfHeight = tan(Math.toRadians(FOV) / 2)
        val aspect = viewportWidth.toDouble() / viewportHeight
        val x = (relative.dot(right) / (depth * halfHeight * aspect) + 1) / 2 * surfaceView.width
        val y = (1 - relative.dot(up) / (depth * halfHeight)) / 2 * surfaceView.height
        return PointF(x.toFloat(), y.toFloat()).takeIf { it.x >= 0 && it.x < width && it.y >= 0 && it.y < height }
    }

    fun setMachines(value: List<RoomMachine>) {
        if (disposed) return
        val next = value.filter { it.id.isNotBlank() }.distinctBy { it.id }.associateBy { it.id }
        if (next == machines) return
        val previous = machines
        val nextPositions = RoomLayout.forMachines(next.values.toList())
        val layoutChanged = positions != nextPositions
        machines = next
        if (engine != null) runCatching {
            if (layoutChanged) {
                layoutVersion++
                bodyMeshes.toList().forEach(::destroyMesh); bodyMeshes.clear()
                statusMeshes.values.toList().forEach(::destroyMesh); statusMeshes.clear()
                garmentMeshes.values.toList().forEach(::destroyMesh); garmentMeshes.clear()
                positions = nextPositions
                val depth = (positions.maxOfOrNull { it.z } ?: -1f) + 1f
                if (depth != extraDepth) {
                    extraDepth = depth
                    environmentMeshes.toList().forEach(::destroyMesh); environmentMeshes.clear()
                    RoomGeometry.environment(extraDepth).forEach { (kind, geometry) -> environmentMeshes += createMesh(geometry, kind) }
                    target = Vec3(0.0, .8, -.15 + extraDepth / 2)
                }
                if (positions.isNotEmpty()) RoomGeometry.machineBodies(positions).forEach { (kind, geometry) -> bodyMeshes += createMesh(geometry, kind) }
            }
            positions.forEachIndexed { index, slot ->
                val machine = next[slot.slot]
                if (previous[slot.slot]?.state != machine?.state || statusMeshes[slot.slot] == null) {
                    statusMeshes.remove(slot.slot)?.let(::destroyMesh)
                    statusMeshes[slot.slot] = createMesh(RoomGeometry.status(slot, machine?.state ?: RoomMachineState.UNKNOWN), RoomMaterial.GLOW)
                }
                if (machine?.state in CONTENT_STATES && garmentMeshes[slot.slot] == null) {
                    garmentMeshes[slot.slot] = createMesh(RoomGeometry.garments(index), RoomMaterial.CLOTH)
                    updateGarmentTransform(slot, 0f)
                } else if (machine?.state !in CONTENT_STATES) {
                    garmentMeshes.remove(slot.slot)?.let(::destroyMesh)
                }
            }
        }.onFailure(::failRenderer)
        requestFrame(immediate = true)
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        if (controller.mode == mode || disposed) return
        controller.setMode(mode); nextFrameNanos = 0L; requestFrame(immediate = true)
    }

    fun resetCamera() {
        yaw = .18; pitch = .46; zoom = 1.0; yawVelocity = 0.0
        requestFrame(immediate = true)
    }

    fun lifecycleResume() {
        if (disposed) return
        resumed = true
        controller.setForeground(true)
        registerEnvironmentObservers()
        refreshEnvironment()
        previousFrameNanos = 0L
        nextFrameNanos = 0L
        requestFrame()
        rendererFailure?.let { onRendererError?.invoke(it) }
    }

    fun lifecyclePause() {
        resumed = false
        controller.setForeground(false)
        choreographer.removeFrameCallback(this)
        callbackScheduled = false
        previousFrameNanos = 0L
        yawVelocity = 0.0
        touching = false
        cancelGesture()
        unregisterEnvironmentObservers()
        publishQuality()
    }

    fun dispose() {
        if (disposed) return
        lifecyclePause()
        disposed = true
        cleanupRenderer()
        onMachineSelected = null
        onQualityChanged = null
        onRendererError = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (resumed && !disposed) lifecycleResume()
    }

    override fun onDetachedFromWindow() {
        // Keep ownership with AndroidView.onRelease; a reattachment can reuse GPU resources.
        choreographer.removeFrameCallback(this)
        callbackScheduled = false
        unregisterEnvironmentObservers()
        controller.setForeground(false)
        touching = false
        yawVelocity = 0.0
        cancelGesture()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == AndroidView.VISIBLE && resumed && !disposed) {
            controller.setForeground(true)
            requestFrame()
        } else {
            controller.setForeground(false)
            choreographer.removeFrameCallback(this)
            callbackScheduled = false
            touching = false
            yawVelocity = 0.0
            cancelGesture()
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun doFrame(frameTimeNanos: Long) {
        runCatching { renderFrame(frameTimeNanos) }.onFailure(::failRenderer)
    }

    private fun renderFrame(frameTimeNanos: Long) {
        callbackScheduled = false
        if (!canRender()) return
        val animated = hasAnimatedGarments()
        val moving = touching || abs(yawVelocity) > .015
        controller.setActivity(moving, animated)
        val quality = controller.state()
        val period = 1_000_000_000L / quality.targetFps.coerceAtLeast(1)
        if ((dirty && (urgent || (!moving && !animated))) || nextFrameNanos == 0L || frameTimeNanos >= nextFrameNanos) {
            val elapsed = if (previousFrameNanos == 0L) 0.0 else ((frameTimeNanos - previousFrameNanos) / 1e9).coerceIn(0.0, .08)
            previousFrameNanos = frameTimeNanos
            if (!touching && motionEnabled() && abs(yawVelocity) > .015) {
                yaw = (yaw + yawVelocity * elapsed).coerceIn(-1.12, 1.12)
                yawVelocity *= exp(-6 * elapsed)
                if (abs(yaw) >= 1.12) yawVelocity = 0.0
            }
            updateCamera()
            applyQuality(quality)
            if (animated) {
                val angle = ((frameTimeNanos / 1e9 * .48) % (Math.PI * 2)).toFloat()
                positions.forEachIndexed { index, position ->
                    if (machines[position.slot]?.state in ANIMATED_STATES) updateGarmentTransform(position, angle + index * .73f)
                }
            }
            val started = System.nanoTime()
            val accepted = renderer?.beginFrame(requireNotNull(swapChain), frameTimeNanos) == true
            if (accepted) {
                try { renderer?.render(requireNotNull(view)) }
                finally { renderer?.endFrame() }
                renderedFrames++
                if (!initialSceneCompilationQueued) {
                    initialSceneCompilationQueued = true
                    // Skybox, depth and post-processing programs are requested lazily by
                    // the first real render. A barrier queued before that render cannot
                    // cover them. Wait for the driver's complete first-scene queue too.
                    val e = requireNotNull(engine)
                    queueMaterialCompilation(e, materials.first(), LIT_VARIANTS)
                    e.flush()
                }
            } else rejectedFrames++
            val cpu = System.nanoTime() - started
            // 90 FPS on a 120 Hz display intentionally alternates VSync intervals. A frame
            // landing on the next available VSync is pacing, not a dropped rendered frame.
            val late = if (urgent || nextFrameNanos == 0L) 0L else
                (frameTimeNanos - nextFrameNanos - displayPeriodNanos).coerceAtLeast(0L)
            controller.observeFrame(frameTimeNanos, readGpuDuration(), cpu, period, !accepted, late)
            dirty = !accepted
            urgent = false
            nextFrameNanos = if (nextFrameNanos == 0L || frameTimeNanos - nextFrameNanos > period * 2)
                frameTimeNanos + period else nextFrameNanos + period
            publishQuality()
        }
        // A still room has no render loop. State changes and input explicitly invalidate it.
        if (dirty || moving || animated || pendingMaterialCompilations > 0) scheduleFrame()
    }

    private fun initializeRenderer() {
        Filament.init()
        val e = Engine.create(Engine.Backend.OPENGL).also { engine = it }
        renderer = e.createRenderer()
        scene = e.createScene()
        view = e.createView().apply {
            scene = this@LaundryRoomView.scene
            setShadowingEnabled(true)
            antiAliasing = View.AntiAliasing.NONE
            dithering = View.Dithering.NONE
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
        }
        cameraEntity = EntityManager.get().create()
        camera = e.createCamera(cameraEntity).apply { setExposure(16f, 1f / 125f, 100f) }
        view?.camera = camera
        skybox = Skybox.Builder().color(.79f, .86f, .83f, 1f).build(e)
        scene?.skybox = skybox
        indirectLight = IndirectLight.Builder().irradiance(1, floatArrayOf(.8f, .9f, 1f)).intensity(25_000f).build(e)
        scene?.indirectLight = indirectLight
        lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f, .96f, .88f).intensity(80_000f)
            .direction(-.35f, -1f, -.4f).castShadows(true).build(e, lightEntity)
        scene?.addEntity(lightEntity)
        createMaterials(e)
        RoomGeometry.environment().forEach { (kind, geometry) -> environmentMeshes += createMesh(geometry, kind) }
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    if(disposed || cleaningRenderer || engine !== e) return
                    runCatching {
                        val old = swapChain
                        swapChain = null
                        old?.let(e::destroySwapChain)
                        swapChain = e.createSwapChain(surface)
                        lastAppliedFps = -1
                        lastGpuFrame = -1
                        previousFrameNanos = 0L
                        nextFrameNanos = 0L
                        refreshEnvironment()
                        requestFrame(immediate = true)
                    }.onFailure { error -> deferRendererFailure(error) }
                }
                override fun onDetachedFromSurface() {
                    choreographer.removeFrameCallback(this@LaundryRoomView)
                    callbackScheduled = false
                    val old = swapChain
                    swapChain = null
                    if(engine !== e) return
                    runCatching { old?.let(e::destroySwapChain); e.flushAndWait() }
                        .onFailure { error -> deferRendererFailure(error) }
                }
                override fun onResized(width: Int, height: Int) {
                    if(disposed || cleaningRenderer || engine !== e || width <= 0 || height <= 0) return
                    runCatching {
                        viewportWidth = width; viewportHeight = height
                        view?.viewport = Viewport(0, 0, width, height)
                        updateCamera()
                        // The driver must finish work using the old buffer dimensions before resize.
                        FilamentHelper.synchronizePendingFrames(e)
                        requestFrame(immediate = true)
                    }.onFailure { error -> deferRendererFailure(error) }
                }
            }
            attachTo(surfaceView)
        }
    }

    private fun createMaterials(e: Engine) {
        val lit = RoomMaterialCompiler.material(e, false).also(materials::add)
        val unlit = RoomMaterialCompiler.material(e, true).also(materials::add)
        RoomMaterial.entries.forEach { kind ->
            instances[kind] = (if (kind == RoomMaterial.GLOW) unlit else lit).createInstance().apply {
                if (kind != RoomMaterial.GLOW) {
                    setParameter("roughness", when (kind) { RoomMaterial.METAL -> .32f; RoomMaterial.CLOTH -> 1f; else -> .78f })
                    setParameter("metallic", if (kind == RoomMaterial.METAL) .65f else 0f)
                }
            }
        }
        // A valid material package still needs real driver shader programs. OpenGL can
        // amortize their compilation over several ticks, particularly on software GPUs.
        // Do not let a reduced-motion, otherwise static room sleep before that work finishes.
        queueMaterialCompilation(e, lit, LIT_VARIANTS)
        queueMaterialCompilation(e, unlit, 0)
        e.flush()
    }

    private fun queueMaterialCompilation(e: Engine, material: Material, variants: Int) {
        pendingMaterialCompilations++
        material.compile(Material.CompilerPriorityQueue.HIGH, variants, Handler(Looper.getMainLooper()), Runnable {
            // Filament also delivers completion callbacks when an engine is destroyed.
            // Never touch a disposed renderer or a newer engine from an old callback.
            if (!disposed && !cleaningRenderer && engine === e) {
                pendingMaterialCompilations = (pendingMaterialCompilations - 1).coerceAtLeast(0)
                requestFrame(immediate = true)
            }
        })
    }

    private fun createMesh(data: MeshData, material: RoomMaterial): NativeMesh {
        val e = requireNotNull(engine)
        val tangentBuffer = ByteBuffer.allocateDirect(data.vertexCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val orientation = SurfaceOrientation.Builder().vertexCount(data.vertexCount).normals(floatBuffer(data.normals)).build()
        orientation.getQuatsAsFloat(tangentBuffer)
        orientation.destroy()
        tangentBuffer.rewind()
        val vb = VertexBuffer.Builder().vertexCount(data.vertexCount).bufferCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4).build(e)
        vb.setBufferAt(e, 0, floatBuffer(data.positions)); vb.setBufferAt(e, 1, tangentBuffer); vb.setBufferAt(e, 2, floatBuffer(data.colors))
        val ib = IndexBuffer.Builder().indexCount(data.indices.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(e)
        val indexBuffer = ByteBuffer.allocateDirect(data.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply { put(data.indices); flip() }
        ib.setBuffer(e, indexBuffer)
        val entity = EntityManager.get().create()
        e.transformManager.create(entity)
        val minimum = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val maximum = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        data.positions.forEachIndexed { index, value ->
            val axis = index % 3
            minimum[axis] = min(minimum[axis], value); maximum[axis] = max(maximum[axis], value)
        }
        val bounds = Box((minimum[0] + maximum[0]) / 2, (minimum[1] + maximum[1]) / 2, (minimum[2] + maximum[2]) / 2,
            max(.001f, (maximum[0] - minimum[0]) / 2), max(.001f, (maximum[1] - minimum[1]) / 2), max(.001f, (maximum[2] - minimum[2]) / 2))
        RenderableManager.Builder(1).boundingBox(bounds).culling(true)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib).material(0, requireNotNull(instances[material]))
            .castShadows(material != RoomMaterial.GLOW).receiveShadows(material != RoomMaterial.GLOW).build(e, entity)
        scene?.addEntity(entity)
        return NativeMesh(entity, vb, ib).also(meshes::add)
    }

    private fun updateGarmentTransform(slot: RoomSlot, angle: Float) {
        val e = engine ?: return
        val mesh = garmentMeshes[slot.slot] ?: return
        transform.fill(0f)
        transform[0] = cos(angle); transform[1] = sin(angle)
        transform[4] = -sin(angle); transform[5] = cos(angle)
        transform[10] = 1f; transform[12] = slot.x; transform[13] = .80f
        transform[14] = slot.z + .51f; transform[15] = 1f
        val manager = e.transformManager
        manager.setTransform(manager.getInstance(mesh.entity), transform)
    }

    private fun updateCamera() {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val aspect = viewportWidth.toDouble() / viewportHeight
        val halfWidth = positions.maxOfOrNull { abs(it.x) + .7f }?.toDouble() ?: 6.2
        val horizontalFit = halfWidth / (tan(Math.toRadians(FOV) / 2) * aspect) * 1.12
        val distance = (max(max(10.2, horizontalFit), (6.7 + extraDepth) * 1.25) * zoom).coerceIn(5.0, 120.0)
        eye = target + Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)) * distance
        camera?.setProjection(FOV, aspect, .1, 240.0, Camera.Fov.VERTICAL)
        camera?.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z, 0.0, 1.0, 0.0)
    }

    private fun applyQuality(quality: QualityState) {
        if (lastAppliedScale != quality.resolutionScale) {
            lastAppliedScale = quality.resolutionScale
            view?.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
                enabled = quality.resolutionScale < 1f
                minScale = quality.resolutionScale; maxScale = quality.resolutionScale
                homogeneousScaling = true; this.quality = View.QualityLevel.HIGH
            }
        }
        if (quality.targetFps != lastAppliedFps) {
            lastAppliedFps = quality.targetFps
            if (Build.VERSION.SDK_INT >= 30 && surfaceView.holder.surface.isValid) {
                surfaceView.holder.surface.setFrameRate(quality.targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    private fun readGpuDuration(): Long? {
        val r = renderer ?: return null
        val count = r.getFrameInfoHistory(frameHistory).coerceIn(0, frameHistory.size)
        var latest: Renderer.FrameInfo? = null
        for (index in 0 until count) {
            val info = frameHistory[index]
            if (info.frameId > lastGpuFrame && info.gpuFrameDuration > 0L && (latest == null || info.frameId > latest.frameId)) latest = info
        }
        return latest?.let { info ->
            lastGpuFrame = info.frameId
            (info.denoisedGpuFrameDuration.takeIf { it > 0L } ?: info.gpuFrameDuration).also { latestGpuDurationNanos = it }
        }
    }

    private fun requestFrame(immediate: Boolean = false) { dirty = true; urgent = urgent || immediate; scheduleFrame() }
    private fun nativeDiagnostics(): String {
        val v = view ?: return "renderer-released"
        val c = camera ?: return "camera-missing"
        return "scene=${v.scene === scene},camera=${v.camera === c},viewport=${v.viewport.width}x${v.viewport.height}," +
            "renderables=${scene?.renderableCount},lights=${scene?.lightCount}," +
            "exposure=${c.aperture}/${c.shutterSpeed}/${c.sensitivity},eye=${c.getPosition(FloatArray(3)).contentToString()}," +
            "projection=${c.getProjectionMatrix(DoubleArray(16)).contentToString()}," +
            "colorWrite=${materials.map { it.isColorWriteEnabled }},post=${v.isPostProcessingEnabled},cull=${v.isFrustumCullingEnabled}"
    }
    private fun canRender() = !disposed && !rendererFailurePending && resumed && isAttachedToWindow && windowVisibility == AndroidView.VISIBLE &&
        engine != null && swapChain != null && uiHelper?.isReadyToRender == true && viewportWidth > 0 && viewportHeight > 0

    private fun scheduleFrame() {
        if (!callbackScheduled && canRender()) {
            callbackScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    private fun motionEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    private fun hasAnimatedGarments() = motionEnabled() && machines.values.any { it.state in ANIMATED_STATES }

    private fun publishQuality() {
        val quality = controller.state(consumeNotice = true)
        if (quality != lastQuality) {
            lastQuality = quality.copy(showDowngradeNotice = false)
            onQualityChanged?.invoke(quality)
        }
    }

    private fun refreshEnvironment() {
        controller.setPowerSaver(powerManager.isPowerSaveMode)
        if (Build.VERSION.SDK_INT >= 29) controller.setThermalStatus(powerManager.currentThermalStatus)
        display?.let { display ->
            controller.setDisplayRefreshRate(display.refreshRate)
            displayPeriodNanos = (1_000_000_000.0 / display.refreshRate.coerceAtLeast(1f)).toLong()
            renderer?.displayInfo = Renderer.DisplayInfo().apply {
                refreshRate = display.refreshRate
                presentationDeadlineNanos = display.presentationDeadlineNanos
                vsyncOffsetNanos = display.appVsyncOffsetNanos
            }
        }
    }

    private fun registerEnvironmentObservers() {
        if (observersRegistered || !isAttachedToWindow) return
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let { powerManager.addThermalStatusListener(context.mainExecutor, it) }
        observersRegistered = true
    }

    private fun unregisterEnvironmentObservers() {
        if (!observersRegistered) return
        context.unregisterReceiver(powerReceiver)
        if (Build.VERSION.SDK_INT >= 29) thermalListener?.let(powerManager::removeThermalStatusListener)
        observersRegistered = false
    }

    private fun destroyMesh(mesh: NativeMesh) {
        val e = engine ?: return
        scene?.remove(mesh.entity)
        e.destroyEntity(mesh.entity)
        EntityManager.get().destroy(mesh.entity)
        e.destroyVertexBuffer(mesh.vertices)
        e.destroyIndexBuffer(mesh.indices)
        meshes.remove(mesh)
    }

    private fun cleanupRenderer() {
        val e = engine ?: return
        if(cleaningRenderer) return
        cleaningRenderer = true
        pendingMaterialCompilations = 0
        val helper = uiHelper
        uiHelper = null
        try {
            helper?.detach()
            // A partial initialization can create a swap chain before UiHelper is retained.
            swapChain?.let { remaining -> swapChain = null; e.destroySwapChain(remaining); e.flushAndWait() }
            meshes.toList().forEach(::destroyMesh)
            statusMeshes.clear(); garmentMeshes.clear(); environmentMeshes.clear(); bodyMeshes.clear()
            if (lightEntity != 0) {
                scene?.remove(lightEntity); e.destroyEntity(lightEntity); EntityManager.get().destroy(lightEntity); lightEntity = 0
            }
            instances.values.forEach(e::destroyMaterialInstance); instances.clear()
            materials.forEach(e::destroyMaterial); materials.clear()
            indirectLight?.let(e::destroyIndirectLight); indirectLight = null
            skybox?.let(e::destroySkybox); skybox = null
            view?.let(e::destroyView); view = null
            scene?.let(e::destroyScene); scene = null
            renderer?.let(e::destroyRenderer); renderer = null
            if (cameraEntity != 0) {
                e.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity); cameraEntity = 0
            }
            e.destroy(); engine = null; camera = null
        } finally { cleaningRenderer = false }
    }

    private fun cancelGesture() {
        gestureLayoutVersion = -1L
        val time = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { gestures.onTouchEvent(cancel); scaling.onTouchEvent(cancel) } finally { cancel.recycle() }
    }

    private fun deferRendererFailure(error: Throwable) {
        // UiHelper changes its own Surface state after returning from callbacks. Defer teardown
        // until then so a driver failure cannot recursively detach the same native window.
        if(!cleaningRenderer && rendererFailure == null && !disposed && !rendererFailurePending) {
            rendererFailurePending = true
            post { if(!disposed) failRenderer(error) }
        }
    }

    private fun failRenderer(error: Throwable) {
        if (rendererFailure != null) return
        val eventId = UUID.randomUUID().toString().take(12)
        rendererFailure = "ROOM_RENDERER_UNAVAILABLE · 状态 503 · 事件 $eventId"
        lifecyclePause()
        runCatching { cleanupRenderer() }
        // Keep driver internals out of user-visible errors while retaining a traceable event.
        android.util.Log.w("WeShirtRoom", rendererFailure, error)
        onRendererError?.invoke(requireNotNull(rendererFailure))
    }

    private data class NativeMesh(val entity: Int, val vertices: VertexBuffer, val indices: IndexBuffer)

    companion object {
        private const val FOV = 45.0
        private val LIT_VARIANTS = Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or Material.UserVariantFilterBit.SHADOW_RECEIVER
        private val ANIMATED_STATES = setOf(RoomMachineState.RUNNING, RoomMachineState.FINISHING)
        private val CONTENT_STATES = ANIMATED_STATES + RoomMachineState.AWAITING_PICKUP
        private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); flip() }
    }
}

/** The compiler is initialized once and bytecode is cached across room recreation. */
private object RoomMaterialCompiler {
    private var initialized = false
    private val packages = mutableMapOf<Boolean, ByteArray>()

    fun material(engine: Engine, unlit: Boolean): Material {
        if (!initialized) { MaterialBuilder.init(); initialized = true }
        val bytes = packages.getOrPut(unlit) {
            val builder = MaterialBuilder().name(if (unlit) "room-lights" else "room-surfaces")
                .platform(MaterialBuilder.Platform.MOBILE).targetApi(MaterialBuilder.TargetApi.OPENGL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .require(MaterialBuilder.VertexAttribute.COLOR)
                .shading(if (unlit) MaterialBuilder.Shading.UNLIT else MaterialBuilder.Shading.LIT)
            if (!unlit) builder.uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "metallic")
            builder.material(if (unlit)
                "void material(inout MaterialInputs material) { prepareMaterial(material); material.baseColor = getColor(); }"
            else "void material(inout MaterialInputs material) { prepareMaterial(material); material.baseColor = getColor(); material.roughness = materialParams.roughness; material.metallic = materialParams.metallic; }")
            val compiled = builder.build(engine)
            check(compiled.isValid) { "ROOM_MATERIAL_INVALID" }
            val buffer = compiled.buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
        return Material.Builder().payload(payload, payload.remaining()).build(engine)
    }
}
