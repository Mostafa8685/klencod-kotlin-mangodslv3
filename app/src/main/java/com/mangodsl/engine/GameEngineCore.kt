package com.mangodsl.engine

import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*
import kotlin.random.Random

/**
 * محرك ألعاب Mango DSL 2D المطور للأندرويد
 * يدعم الفيزياء المتزنة، نظام إطلاق المقذوفات (Projectiles)، ذكاء الدوريات للأعداء (Patrol AI)،
 * المؤثرات الصوتية التوليدية والمستوردة، الرسوم المتحركة (Sprites)، الجسيمات، وشاشة معلومات اللعب (HUD).
 */

enum class ScriptType {
    ARABIC_DSL,
    PYTHON,
    NONE
}

data class Vector2(var x: Float = 0f, var y: Float = 0f) {
    fun set(newX: Float, newY: Float) {
        x = newX
        y = newY
    }
    fun add(dx: Float, dy: Float) {
        x += dx
        y += dy
    }
}

/**
 * جسيمات المؤثرات البصرية (انفجار، التقاط عملة، غبار القفز، شرار المقذوفات)
 */
data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    var color: Int,
    var life: Float,
    var maxLife: Float = life
)

/**
 * تمثيل الكائن داخل مشهد اللعبة (Entity / Sprite / Projectile / Enemy)
 */
class GameEntity(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "كائن_جديد",
    var tag: String = "افتراضي"
) {
    val position = Vector2(0f, 0f)
    val velocity = Vector2(0f, 0f)
    var width: Float = 64f
    var height: Float = 64f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    // الخصائص الفيزيائية والحركية
    var isSolid: Boolean = false
    var affectedByGravity: Boolean = false
    var isGrounded: Boolean = false
    var bounce: Float = 0f
    var friction: Float = 0.82f
    var isVisible: Boolean = true
    var isActive: Boolean = true
    var health: Float = 100f
    var maxHealth: Float = 100f
    var facingRight: Boolean = true
    var animTimer: Float = 0f

    // خصائص المقذوفات والقتال والدوريات الذكية
    var isProjectile: Boolean = false
    var ownerId: String = ""
    var projectileDamage: Float = 35f
    var lifeTime: Float = -1f // -1 يعني كائن دائم، وأكبر من 0 يعني مؤقت بالثواني (مثل المقذوف)
    var shootCooldown: Float = 0f
    var hitCooldown: Float = 0f
    var patrolDirection: Int = 1 // 1 لليمين، -1 لليسار

    // المظهر والرسوم
    var color: Int = Color.parseColor("#4CAF50")
    var spriteName: String = ""
    @Transient var spriteBitmap: Bitmap? = null

    // نصوص البرمجة
    var scriptType: ScriptType = ScriptType.ARABIC_DSL
    var scriptCode: String = ""

    // مخزن الخصائص المخصصة وحقيبة الكائن (Inventory & Custom Attributes)
    val inventory = CopyOnWriteArrayList<String>()
    val attributes = ConcurrentHashMap<String, Any>()

    fun getBoundingBox(): RectF {
        return RectF(position.x, position.y, position.x + width * scaleX, position.y + height * scaleY)
    }

    fun hasItem(itemName: String): Boolean {
        val clean = itemName.trim().lowercase()
        return inventory.any { it.trim().lowercase() == clean }
    }

    fun addItem(itemName: String): Boolean {
        val clean = itemName.trim()
        return if (clean.isNotEmpty() && !hasItem(clean)) {
            inventory.add(clean)
            true
        } else {
            false
        }
    }

    fun removeItem(itemName: String): Boolean {
        val clean = itemName.trim().lowercase()
        val item = inventory.firstOrNull { it.trim().lowercase() == clean }
        return if (item != null) {
            inventory.remove(item)
            true
        } else {
            false
        }
    }
}

/**
 * تمثيل مشهد اللعبة (Scene)
 */
class GameScene(var name: String = "المشهد_الرئيسي") {
    var backgroundColor: Int = Color.parseColor("#141824")
    val entities = CopyOnWriteArrayList<GameEntity>()
    val particles = CopyOnWriteArrayList<Particle>()
    val globalVariables = ConcurrentHashMap<String, Float>()
    var gravity: Float = 980f // بكسل لكل ثانية مربعة
    var worldWidth: Float = 2400f
    var worldHeight: Float = 1200f
    val camera = Camera2D()

    fun findEntityByName(name: String): GameEntity? {
        val clean = name.trim().lowercase()
        return entities.firstOrNull { it.name.trim().lowercase() == clean && it.isActive }
    }

    fun findEntitiesByTag(tag: String): List<GameEntity> {
        val clean = tag.trim().lowercase()
        return entities.filter { it.tag.trim().lowercase() == clean && it.isActive }
    }

    fun addEntity(entity: GameEntity) {
        entities.add(entity)
    }

    fun removeEntity(entity: GameEntity) {
        entities.remove(entity)
    }

    /**
     * إطلاق مقذوف طاقة من الكائن في اتجاه نظره الحالي مع فترة تهدئة تمنع التكدس
     */
    fun spawnProjectile(
        shooter: GameEntity,
        speed: Float = 560f,
        damage: Float = 35f,
        projectileColor: Int = Color.parseColor("#00E5FF")
    ): GameEntity? {
        if (shooter.shootCooldown > 0f) return null
        shooter.shootCooldown = 0.28f // فترة تهدئة بين كل طلقة وأخرى

        val dir = if (shooter.facingRight) 1f else -1f
        val projW = 24f
        val projH = 14f
        val startX = if (shooter.facingRight) {
            shooter.position.x + shooter.width * shooter.scaleX + 6f
        } else {
            shooter.position.x - projW - 6f
        }
        val startY = shooter.position.y + (shooter.height * shooter.scaleY) * 0.42f - (projH / 2f)

        val projectile = GameEntity(
            name = "مقذوف_${System.currentTimeMillis() % 10000}",
            tag = "مقذوف"
        ).apply {
            position.set(startX, startY)
            velocity.set(dir * speed, 0f)
            width = projW
            height = projH
            color = projectileColor
            isSolid = false
            affectedByGravity = false
            friction = 1.0f // لا يتباطأ بالاحتكاك
            isProjectile = true
            ownerId = shooter.id
            projectileDamage = damage
            lifeTime = 1.6f // يختفي تلقائياً بعد 1.6 ثانية
            facingRight = shooter.facingRight
            scriptType = ScriptType.NONE
        }

        addEntity(projectile)
        spawnParticles(startX + projW / 2f, startY + projH / 2f, projectileColor, 6)
        return projectile
    }

    fun spawnParticles(x: Float, y: Float, color: Int, count: Int = 14) {
        for (i in 0 until count) {
            val angle = Random.nextFloat() * (2f * PI.toFloat())
            val speed = 60f + Random.nextFloat() * 220f
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 60f,
                    radius = 4f + Random.nextFloat() * 6f,
                    color = color,
                    life = 0.35f + Random.nextFloat() * 0.45f
                )
            )
        }
    }
}

/**
 * كاميرا ثنائية الأبعاد مع ملاحقة ناعمة ومؤثر اهتزاز الشاشة (Screen Shake)
 */
class Camera2D {
    var position = Vector2(0f, 0f)
    var zoom: Float = 1.0f
    var targetEntity: GameEntity? = null
    var smoothSpeed: Float = 6.0f
    private var shakeIntensity: Float = 0f
    private var shakeTime: Float = 0f

    fun triggerShake(intensity: Float = 12f, duration: Float = 0.25f) {
        shakeIntensity = intensity
        shakeTime = duration
    }

    fun update(viewportWidth: Float, viewportHeight: Float, dt: Float) {
        targetEntity?.let { target ->
            val targetX = target.position.x + (target.width / 2f) - (viewportWidth / (2f * zoom))
            val targetY = target.position.y + (target.height / 2f) - (viewportHeight / (2f * zoom))
            position.x += (targetX - position.x) * min(1f, smoothSpeed * dt)
            position.y += (targetY - position.y) * min(1f, smoothSpeed * dt)
        }

        if (shakeTime > 0f) {
            shakeTime -= dt
            position.x += (Random.nextFloat() - 0.5f) * 2f * shakeIntensity
            position.y += (Random.nextFloat() - 0.5f) * 2f * shakeIntensity
        }
    }
}

/**
 * مدير مدخلات اللمس المتعدد وأزرار التحكم الافتراضية على الشاشة
 */
class InputManager {
    private val pressedButtons = ConcurrentHashMap<String, Boolean>()
    private val justPressedButtons = ConcurrentHashMap<String, Boolean>()
    val joystickDir = Vector2(0f, 0f)
    var touchPosition = Vector2(0f, 0f)
    var isTouchDown: Boolean = false

    fun setButtonPressed(buttonKey: String, isDown: Boolean) {
        val key = buttonKey.trim().lowercase()
        val wasDown = pressedButtons[key] == true
        pressedButtons[key] = isDown
        if (isDown && !wasDown) {
            justPressedButtons[key] = true
        }
    }

    fun isPressed(buttonKey: String): Boolean {
        val key = buttonKey.trim().lowercase()
        return pressedButtons[key] == true
    }

    fun consumeJustPressed(buttonKey: String): Boolean {
        val key = buttonKey.trim().lowercase()
        return justPressedButtons.remove(key) == true
    }

    fun clear() {
        pressedButtons.clear()
        justPressedButtons.clear()
        joystickDir.set(0f, 0f)
        isTouchDown = false
    }
}

/**
 * مدير الصوتيات المطور: يولّد مؤثرات صوتية حقيقية (WAV PCM) مدمجة تلقائياً،
 * ويدعم استيراد وتشغيل ملفات الصوت من الهاتف!
 */
class EngineAudioManager(private val context: Context) {
    private val soundPool: SoundPool
    private val soundMap = ConcurrentHashMap<String, Int>()

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(10)
            .setAudioAttributes(attrs)
            .build()

        initBuiltInSynthesizedSounds()
    }

    private fun initBuiltInSynthesizedSounds() {
        try {
            registerSynthTone("قفزة_البطل", 320.0, 680.0, 0.16)
            registerSynthTone("jump_sound.ogg", 320.0, 680.0, 0.16)
            registerSynthTone("إطلاق", 880.0, 280.0, 0.13)
            registerSynthTone("ليزر", 980.0, 310.0, 0.14)
            registerSynthTone("التقاط_الحجر", 520.0, 1040.0, 0.22)
            registerSynthTone("رنين_العملة", 660.0, 1320.0, 0.18)
            registerSynthTone("فتح_الباب", 220.0, 880.0, 0.35)
            registerSynthTone("unlock_gate.ogg", 220.0, 880.0, 0.35)
            registerSynthTone("نغمة_فوز", 440.0, 1180.0, 0.40)
            registerSynthTone("ضربة", 260.0, 90.0, 0.15)
            registerSynthTone("انفجار", 180.0, 55.0, 0.32)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerSynthTone(name: String, startFreq: Double, endFreq: Double, durationSec: Double) {
        val cacheFile = File(context.cacheDir, "synth_${name.hashCode()}.wav")
        if (!cacheFile.exists()) {
            writeSynthesizedWav(cacheFile, startFreq, endFreq, durationSec)
        }
        val soundId = soundPool.load(cacheFile.absolutePath, 1)
        soundMap[name.trim().lowercase()] = soundId
    }

    private fun writeSynthesizedWav(file: File, startFreq: Double, endFreq: Double, durationSec: Double) {
        val sampleRate = 22050
        val numSamples = (sampleRate * durationSec).toInt()
        val pcmData = ByteArray(numSamples * 2)
        var phase = 0.0

        for (i in 0 until numSamples) {
            val progress = i.toDouble() / numSamples.toDouble()
            val freq = startFreq + (endFreq - startFreq) * progress
            val envelope = (1.0 - progress).pow(0.7)
            phase += 2.0 * PI * freq / sampleRate
            val rawWave = 0.65 * sin(phase) + 0.35 * (if (sin(phase) >= 0.0) 0.8 else -0.8)
            val sample = (rawWave * envelope * 24000.0).toInt().coerceIn(-32767, 32767).toShort()
            pcmData[i * 2] = (sample.toInt() and 0xFF).toByte()
            pcmData[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }

        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()

        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }

    fun loadExternalSound(name: String, filePath: String) {
        try {
            val f = File(filePath)
            if (f.exists()) {
                val id = soundPool.load(f.absolutePath, 1)
                soundMap[name.trim().lowercase()] = id
                soundMap[name.substringBeforeLast(".").trim().lowercase()] = id
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playSound(soundName: String) {
        val key = soundName.trim().lowercase()
        val id = soundMap[key] ?: soundMap["قفزة_البطل"]
        if (id != null) {
            soundPool.play(id, 0.9f, 0.9f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}

/**
 * محرك فيزياء متكامل يدعم الجاذبية، الاحتكاك، المقذوفات، والارتداد، ومنع تكرار التصادم
 */
class PhysicsEngine {
    fun update(scene: GameScene, dt: Float, onCollision: (GameEntity, GameEntity) -> Unit) {
        val clampedDt = min(dt, 0.033f)
        val notifiedPairs = HashSet<String>()

        for (entity in scene.entities) {
            if (!entity.isActive) continue

            // تحديث مؤقتات التهدئة والعمر الزمني للمقذوفات
            if (entity.shootCooldown > 0f) {
                entity.shootCooldown = max(0f, entity.shootCooldown - clampedDt)
            }
            if (entity.hitCooldown > 0f) {
                entity.hitCooldown = max(0f, entity.hitCooldown - clampedDt)
            }
            if (entity.lifeTime > 0f) {
                entity.lifeTime -= clampedDt
                if (entity.lifeTime <= 0f) {
                    entity.isActive = false
                    scene.removeEntity(entity)
                    continue
                }
            }

            if (entity.velocity.x > 8f) entity.facingRight = true
            else if (entity.velocity.x < -8f) entity.facingRight = false

            if (abs(entity.velocity.x) > 12f || abs(entity.velocity.y) > 12f) {
                entity.animTimer += clampedDt * 10f
            } else {
                entity.animTimer = 0f
            }

            if (entity.affectedByGravity && !entity.isProjectile) {
                entity.velocity.y = min(entity.velocity.y + scene.gravity * clampedDt, 1100f)
            }

            entity.position.x += entity.velocity.x * clampedDt
            checkCollisionsHorizontal(entity, scene, notifiedPairs, onCollision)

            if (!entity.isProjectile) {
                val damping = if (entity.isGrounded) entity.friction else 0.93f
                entity.velocity.x *= damping
                if (abs(entity.velocity.x) < 4f) entity.velocity.x = 0f

                if (!entity.affectedByGravity) {
                    entity.velocity.y *= 0.90f
                    if (abs(entity.velocity.y) < 4f) entity.velocity.y = 0f
                }
            } else {
                // أثر جسيمات خفيف خلف المقذوف أثناء طيرانه
                if (Random.nextFloat() < 0.45f) {
                    scene.particles.add(
                        Particle(
                            x = entity.position.x + entity.width / 2f,
                            y = entity.position.y + entity.height / 2f,
                            vx = -entity.velocity.x * 0.15f,
                            vy = (Random.nextFloat() - 0.5f) * 30f,
                            radius = 3.5f,
                            color = entity.color,
                            life = 0.16f
                        )
                    )
                }
            }

            entity.position.y += entity.velocity.y * clampedDt
            entity.isGrounded = false
            checkCollisionsVertical(entity, scene, notifiedPairs, onCollision)
        }

        val iter = scene.particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx * clampedDt
            p.y += p.vy * clampedDt
            p.vy += scene.gravity * 0.35f * clampedDt
            p.life -= clampedDt
            if (p.life <= 0f) {
                scene.particles.remove(p)
            }
        }
    }

    private fun handleProjectileCollision(proj: GameEntity, target: GameEntity, scene: GameScene): Boolean {
        if (!proj.isProjectile) return false
        if (target.id == proj.ownerId || target.isProjectile || target.tag == "عنصر") return true
        if (target.tag == "جدار" || target.tag == "بوابة" || target.isSolid) {
            scene.spawnParticles(proj.position.x + proj.width / 2f, proj.position.y + proj.height / 2f, proj.color, 10)
            proj.isActive = false
            scene.removeEntity(proj)
        }
        return false
    }

    private fun notifyOnce(
        a: GameEntity,
        b: GameEntity,
        scene: GameScene,
        notifiedPairs: HashSet<String>,
        onCollision: (GameEntity, GameEntity) -> Unit
    ) {
        if (a.isProjectile && a.ownerId == b.id) return
        if (b.isProjectile && b.ownerId == a.id) return

        val pairKey = if (a.id < b.id) "${a.id}_${b.id}" else "${b.id}_${a.id}"
        if (notifiedPairs.add(pairKey)) {
            onCollision(a, b)
            if (a.isProjectile && a.isActive) handleProjectileCollision(a, b, scene)
            if (b.isProjectile && b.isActive) handleProjectileCollision(b, a, scene)
        }
    }

    private fun checkCollisionsHorizontal(
        entity: GameEntity,
        scene: GameScene,
        notifiedPairs: HashSet<String>,
        onCollision: (GameEntity, GameEntity) -> Unit
    ) {
        val box = entity.getBoundingBox()
        for (other in scene.entities) {
            if (other == entity || !other.isActive) continue
            val otherBox = other.getBoundingBox()

            if (RectF.intersects(box, otherBox)) {
                notifyOnce(entity, other, scene, notifiedPairs, onCollision)
                if (!entity.isProjectile && !other.isProjectile && entity.isSolid && other.isSolid) {
                    if (entity.velocity.x > 0f) {
                        entity.position.x = otherBox.left - entity.width * entity.scaleX
                        entity.patrolDirection = -1
                    } else if (entity.velocity.x < 0f) {
                        entity.position.x = otherBox.right
                        entity.patrolDirection = 1
                    }
                    entity.velocity.x = -entity.velocity.x * entity.bounce
                }
            }
        }
    }

    private fun checkCollisionsVertical(
        entity: GameEntity,
        scene: GameScene,
        notifiedPairs: HashSet<String>,
        onCollision: (GameEntity, GameEntity) -> Unit
    ) {
        val box = entity.getBoundingBox()
        for (other in scene.entities) {
            if (other == entity || !other.isActive) continue
            val otherBox = other.getBoundingBox()

            if (RectF.intersects(box, otherBox)) {
                notifyOnce(entity, other, scene, notifiedPairs, onCollision)
                if (!entity.isProjectile && !other.isProjectile && entity.isSolid && other.isSolid) {
                    if (entity.velocity.y > 0f) {
                        entity.position.y = otherBox.top - entity.height * entity.scaleY
                        if (entity.bounce > 0.05f && abs(entity.velocity.y) > 80f) {
                            entity.velocity.y = -entity.velocity.y * entity.bounce
                        } else {
                            entity.velocity.y = 0f
                            entity.isGrounded = true
                        }
                    } else if (entity.velocity.y < 0f) {
                        entity.position.y = otherBox.bottom
                        entity.velocity.y = 0f
                    }
                }
            }
        }
    }
}

/**
 * واجهة العرض السريعة (SurfaceView) وتشغيل حلقة اللعبة بمعدل 60 إطار في الثانية
 * مع دعم السحب والإفلات المباشر للكائنات على الشاشة!
 */
class MangoGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    var currentScene: GameScene = GameScene()
    val inputManager = InputManager()
    val physicsEngine = PhysicsEngine()
    val audioManager = EngineAudioManager(context)

    var onFrameUpdate: ((Float) -> Unit)? = null
    var onCollisionEvent: ((GameEntity, GameEntity) -> Unit)? = null
    var onEntitySelectedByTouch: ((GameEntity) -> Unit)? = null

    var fps: Int = 0
    var isRunning: Boolean = false
    var isPaused: Boolean = false
    var showDebugOverlay: Boolean = true
    var enableTouchDragInEditor: Boolean = true

    private var draggedEntity: GameEntity? = null
    private var dragOffsetX: Float = 0f
    private var dragOffsetY: Float = 0f

    private var gameThread: Thread? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#FF7043")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(28, 255, 255, 255)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cam = currentScene.camera
        val worldX = (event.x / cam.zoom) + cam.position.x
        val worldY = (event.y / cam.zoom) + cam.position.y
        inputManager.touchPosition.set(worldX, worldY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inputManager.isTouchDown = true
                if (enableTouchDragInEditor) {
                    val hit = currentScene.entities.reversed().firstOrNull {
                        it.isActive && !it.isProjectile && it.getBoundingBox().contains(worldX, worldY)
                    }
                    if (hit != null) {
                        draggedEntity = hit
                        dragOffsetX = worldX - hit.position.x
                        dragOffsetY = worldY - hit.position.y
                        onEntitySelectedByTouch?.invoke(hit)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                draggedEntity?.let { ent ->
                    val rawX = worldX - dragOffsetX
                    val rawY = worldY - dragOffsetY
                    if (isPaused) {
                        ent.position.x = (rawX / 10f).roundToInt() * 10f
                        ent.position.y = (rawY / 10f).roundToInt() * 10f
                        ent.velocity.set(0f, 0f)
                    } else {
                        ent.position.set(rawX, rawY)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                inputManager.isTouchDown = false
                draggedEntity = null
            }
        }
        return super.onTouchEvent(event)
    }

    fun start() {
        if (!isRunning) {
            isRunning = true
            gameThread = Thread(this, "MangoGameEngineLoop").apply { start() }
        }
    }

    fun stop() {
        isRunning = false
        try {
            gameThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        gameThread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stop()
    }

    override fun run() {
        var lastTime = System.nanoTime()
        var frameCount = 0
        var fpsTimer = System.currentTimeMillis()

        while (isRunning) {
            val now = System.nanoTime()
            val dt = (now - lastTime) / 1_000_000_000f
            lastTime = now

            if (!isPaused) {
                onFrameUpdate?.invoke(dt)
                physicsEngine.update(currentScene, dt) { a, b ->
                    onCollisionEvent?.invoke(a, b)
                }
                currentScene.camera.update(width.toFloat(), height.toFloat(), dt)
            }

            render()

            frameCount++
            if (System.currentTimeMillis() - fpsTimer >= 1000) {
                fps = frameCount
                frameCount = 0
                fpsTimer = System.currentTimeMillis()
            }

            try {
                Thread.sleep(14)
            } catch (e: Exception) {}
        }
    }

    private fun render() {
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(currentScene.backgroundColor)

            val cam = currentScene.camera
            canvas.save()
            canvas.scale(cam.zoom, cam.zoom)
            canvas.translate(-cam.position.x, -cam.position.y)

            drawWorldGrid(canvas, cam)

            for (entity in currentScene.entities) {
                if (!entity.isActive || !entity.isVisible) continue

                if (entity.spriteBitmap == null && entity.spriteName.isNotEmpty()) {
                    entity.spriteBitmap = ProjectManager.getLoadedBitmap(context, entity.spriteName)
                }

                val w = entity.width * entity.scaleX
                val h = entity.height * entity.scaleY
                val bobOffset = if (entity.isGrounded && abs(entity.velocity.x) > 15f) {
                    sin(entity.animTimer * 1.5f) * 3.5f
                } else 0f

                canvas.save()
                canvas.translate(entity.position.x, entity.position.y + bobOffset)
                canvas.rotate(entity.rotation, w / 2f, h / 2f)

                val bmp = entity.spriteBitmap
                if (bmp != null) {
                    canvas.save()
                    if (!entity.facingRight) {
                        canvas.scale(-1f, 1f, w / 2f, h / 2f)
                    }
                    val destRect = RectF(0f, 0f, w, h)
                    canvas.drawBitmap(bmp, null, destRect, paint)
                    canvas.restore()
                } else {
                    drawStylizedEntity(canvas, entity, w, h)
                }

                // رسم شريط صحة فوق الأعداء أو الكائنات المتضررة
                if (!entity.isProjectile && (entity.tag == "عدو" || entity.health < entity.maxHealth) && entity.maxHealth > 0f) {
                    paint.color = Color.parseColor("#37474F")
                    canvas.drawRoundRect(0f, -18f, w, -8f, 4f, 4f, paint)
                    paint.color = if (entity.tag == "عدو") Color.parseColor("#FF5252") else Color.parseColor("#00E676")
                    val ratio = (entity.health / entity.maxHealth).coerceIn(0f, 1f)
                    canvas.drawRoundRect(0f, -18f, w * ratio, -8f, 4f, 4f, paint)
                }

                if (showDebugOverlay && entity.isSolid && !entity.isProjectile) {
                    canvas.drawRect(0f, 0f, w, h, boxPaint)
                }

                if (showDebugOverlay && !entity.isProjectile) {
                    paint.color = Color.WHITE
                    paint.textSize = 20f
                    canvas.drawText(entity.name, 4f, -22f, paint)
                }

                canvas.restore()
            }

            for (p in currentScene.particles) {
                paint.color = p.color
                paint.alpha = ((p.life / p.maxLife) * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(p.x, p.y, p.radius, paint)
            }
            paint.alpha = 255

            canvas.restore()

            drawHudOverlay(canvas)

        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawWorldGrid(canvas: Canvas, cam: Camera2D) {
        val step = 80f
        val startX = floor(cam.position.x / step) * step
        val startY = floor(cam.position.y / step) * step
        val endX = cam.position.x + (width / cam.zoom) + step
        val endY = cam.position.y + (height / cam.zoom) + step

        var x = startX
        while (x <= endX) {
            canvas.drawLine(x, startY, x, endY, gridPaint)
            x += step
        }
        var y = startY
        while (y <= endY) {
            canvas.drawLine(startX, y, endX, y, gridPaint)
            y += step
        }
    }

    private fun drawStylizedEntity(canvas: Canvas, entity: GameEntity, w: Float, h: Float) {
        paint.color = entity.color
        when {
            entity.isProjectile || entity.tag == "مقذوف" -> {
                // رسم كرة طاقة متوهجة للمقذوف
                paint.alpha = 95
                canvas.drawRoundRect(-4f, -4f, w + 4f, h + 4f, h, h, paint)
                paint.alpha = 255
                canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)
                paint.color = Color.WHITE
                canvas.drawRoundRect(w * 0.2f, h * 0.25f, w * 0.8f, h * 0.75f, h / 3f, h / 3f, paint)
            }
            entity.tag == "عنصر" || entity.name.contains("حجر") || entity.name.contains("عملة") || entity.name.contains("بلورة") -> {
                val pulse = 1f + 0.08f * sin(System.currentTimeMillis() / 160.0).toFloat()
                paint.alpha = 70
                canvas.drawCircle(w / 2f, h / 2f, (w * 0.65f) * pulse, paint)
                paint.alpha = 255
                canvas.drawRoundRect(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.9f, w * 0.35f, h * 0.35f, paint)
            }
            entity.tag == "بوابة" || entity.name.contains("باب") -> {
                canvas.drawRoundRect(0f, 0f, w, h, 18f, 18f, paint)
                paint.color = Color.argb(90, 0, 0, 0)
                canvas.drawRoundRect(8f, 10f, w - 8f, h - 4f, 12f, 12f, paint)
            }
            entity.tag == "بطل" || entity.tag == "لاعب" || entity.tag == "عدو" || entity.name.contains("اللاعب") || entity.name.contains("حارس") || entity.name.contains("وحش") -> {
                canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, paint)
                val eyeOffsetX = if (entity.facingRight) w * 0.62f else w * 0.22f
                paint.color = if (entity.tag == "عدو") Color.parseColor("#FFEB3B") else Color.WHITE
                canvas.drawCircle(eyeOffsetX, h * 0.28f, w * 0.13f, paint)
                paint.color = Color.BLACK
                val pupilShift = if (entity.facingRight) 2.5f else -2.5f
                canvas.drawCircle(eyeOffsetX + pupilShift, h * 0.28f, w * 0.06f, paint)
            }
            else -> {
                canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, paint)
            }
        }
    }

    private fun drawHudOverlay(canvas: Canvas) {
        val hero = currentScene.camera.targetEntity ?: currentScene.findEntityByName("اللاعب")
        paint.color = Color.argb(165, 18, 19, 26)
        canvas.drawRoundRect(20f, 20f, 520f, if (hero != null) 165f else 115f, 18f, 18f, paint)

        canvas.drawText("Mango 2D • FPS: $fps • الكائنات: ${currentScene.entities.size}", 38f, 56f, debugPaint)

        hero?.let { p ->
            val score = (p.attributes["النقاط"] as? Number)?.toInt()
                ?: currentScene.globalVariables["النقاط"]?.toInt() ?: 0
            hudPaint.color = Color.parseColor("#FFD54F")
            canvas.drawText("❤ الصحة: ${p.health.toInt()}   ⭐ النقاط: $score", 38f, 98f, hudPaint)

            val invText = if (p.inventory.isEmpty()) "فارغ" else p.inventory.joinToString(" ، ")
            hudPaint.color = Color.parseColor("#80D8FF")
            hudPaint.textSize = 23f
            canvas.drawText("🎒 المخزون: $invText", 38f, 138f, hudPaint)
            hudPaint.textSize = 28f
        }

        if (isPaused) {
            paint.color = Color.argb(190, 255, 167, 38)
            canvas.drawRoundRect(width - 380f, 20f, width - 20f, 78f, 14f, 14f, paint)
            hudPaint.color = Color.BLACK
            hudPaint.textSize = 24f
            canvas.drawText("✋ وضع التحرير: اسحب أي كائن", width - 360f, 56f, hudPaint)
            hudPaint.textSize = 28f
        }
    }
}
