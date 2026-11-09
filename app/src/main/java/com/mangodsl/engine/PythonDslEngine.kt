package com.mangodsl.engine

import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * محرك تشغيل وتفسير نصوص بايثون للألعاب (Python Multi-Line AST Bridge)
 * يدعم الدوال (on_start, on_update, on_collision)، الكتل متعددة الأسطر بالإزاحة (Indentation)،
 * جمل (if / elif / else)، إطلاق المقذوفات (entity.shoot)، ودوريات الحراسة (entity.patrol).
 */
class PythonDslEngine(
    private val scene: GameScene,
    private val input: InputManager,
    private val audio: EngineAudioManager,
    private val logCallback: ((String) -> Unit)? = null
) {

    class PyEntity(val entity: GameEntity, private val scene: GameScene, private val audio: EngineAudioManager) {
        val name: String get() = entity.name
        val tag: String get() = entity.tag
        var x: Float
            get() = entity.position.x
            set(v) { entity.position.x = v }
        var y: Float
            get() = entity.position.y
            set(v) { entity.position.y = v }
        var vx: Float
            get() = entity.velocity.x
            set(v) { entity.velocity.x = v }
        var vy: Float
            get() = entity.velocity.y
            set(v) { entity.velocity.y = v }
        var health: Float
            get() = entity.health
            set(v) { entity.health = v.coerceIn(0f, entity.maxHealth) }
        var is_solid: Boolean
            get() = entity.isSolid
            set(v) { entity.isSolid = v }

        fun move(dx: Float, dy: Float) {
            entity.position.x += dx
            entity.position.y += dy
        }

        fun patrol(x1: Float, x2: Float, speed: Float = 120f) {
            val low = min(x1, x2)
            val high = max(x1, x2)
            if (entity.position.x <= low) {
                entity.patrolDirection = 1
                entity.facingRight = true
            } else if (entity.position.x >= high) {
                entity.patrolDirection = -1
                entity.facingRight = false
            }
            entity.velocity.x = entity.patrolDirection * speed
        }

        fun shoot(speed: Float = 580f) {
            val proj = scene.spawnProjectile(entity, speed, 35f, Color.parseColor("#00E5FF"))
            if (proj != null) {
                audio.playSound("ليزر")
            }
        }

        fun jump(power: Float = 380f) {
            if (entity.isGrounded || !entity.affectedByGravity) {
                entity.velocity.y = -power
                entity.isGrounded = false
                scene.spawnParticles(
                    entity.position.x + entity.width / 2f,
                    entity.position.y + entity.height,
                    Color.parseColor("#80D8FF"),
                    8
                )
            }
        }

        fun has_item(itemName: String): Boolean = entity.hasItem(itemName)
        fun add_item(itemName: String) {
            entity.addItem(itemName)
            scene.spawnParticles(entity.position.x + entity.width / 2f, entity.position.y + entity.height / 2f, entity.color, 12)
        }
        fun remove_item(itemName: String): Boolean = entity.removeItem(itemName)
        fun destroy() {
            scene.spawnParticles(entity.position.x + entity.width / 2f, entity.position.y + entity.height / 2f, entity.color, 16)
            entity.isActive = false
            scene.removeEntity(entity)
        }
        fun set_color(hex: String) {
            try { entity.color = Color.parseColor(hex) } catch (e: Exception) {}
        }
    }

    private val pyEntities = ConcurrentHashMap<String, PyEntity>()

    private fun getPyEntity(entity: GameEntity): PyEntity {
        return pyEntities.getOrPut(entity.id) {
            PyEntity(entity, scene, audio)
        }
    }

    fun executeStart(entity: GameEntity) {
        val pyEntity = getPyEntity(entity)
        executePythonFunction(entity.scriptCode, "on_start", pyEntity, null, 0f)
    }

    fun executeUpdate(entity: GameEntity, dt: Float) {
        val pyEntity = getPyEntity(entity)
        executePythonFunction(entity.scriptCode, "on_update", pyEntity, null, dt)
    }

    fun executeCollision(entity: GameEntity, other: GameEntity) {
        val pyEntity = getPyEntity(entity)
        val pyOther = getPyEntity(other)
        executePythonFunction(entity.scriptCode, "on_collision", pyEntity, pyOther, 0f)
    }

    private fun getIndent(line: String): Int = line.takeWhile { it == ' ' || it == '\t' }.length

    private fun executePythonFunction(code: String, targetFunc: String, self: PyEntity, other: PyEntity?, dt: Float) {
        val rawLines = code.lines()
        var i = 0
        while (i < rawLines.size) {
            val trimmed = rawLines[i].trim()
            if (trimmed.startsWith("def ")) {
                val funcName = trimmed.removePrefix("def ").substringBefore("(").trim()
                if (funcName == targetFunc) {
                    val defIndent = getIndent(rawLines[i])
                    val funcBody = mutableListOf<String>()
                    i++
                    while (i < rawLines.size) {
                        val l = rawLines[i]
                        val t = l.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && getIndent(l) <= defIndent) {
                            break
                        }
                        if (t.isNotEmpty() && !t.startsWith("#")) {
                            funcBody.add(l)
                        }
                        i++
                    }
                    executeBlock(funcBody, 0, funcBody.size, self, other, dt)
                    return
                }
            }
            i++
        }
    }

    private fun executeBlock(
        lines: List<String>,
        start: Int,
        end: Int,
        self: PyEntity,
        other: PyEntity?,
        dt: Float
    ) {
        var idx = start
        var lastIfMatched = false

        while (idx < end) {
            val raw = lines[idx]
            val trimmed = raw.trim()
            val currentIndent = getIndent(raw)

            if (trimmed.startsWith("if ") || trimmed.startsWith("elif ") || trimmed.startsWith("else:")) {
                val isIf = trimmed.startsWith("if ")
                val isElif = trimmed.startsWith("elif ")

                val inlineStmt = trimmed.substringAfter(":", "").trim()
                var subEnd = idx + 1
                while (subEnd < end && getIndent(lines[subEnd]) > currentIndent) {
                    subEnd++
                }

                val shouldRun = when {
                    isIf -> {
                        val condStr = trimmed.removePrefix("if ").substringBefore(":").trim()
                        val res = evalCondition(condStr, self, other)
                        lastIfMatched = res
                        res
                    }
                    isElif -> {
                        if (lastIfMatched) {
                            false
                        } else {
                            val condStr = trimmed.removePrefix("elif ").substringBefore(":").trim()
                            val res = evalCondition(condStr, self, other)
                            if (res) lastIfMatched = true
                            res
                        }
                    }
                    else -> !lastIfMatched
                }

                if (shouldRun) {
                    if (inlineStmt.isNotEmpty()) {
                        executeAction(inlineStmt, self, other, dt)
                    } else if (subEnd > idx + 1) {
                        executeBlock(lines, idx + 1, subEnd, self, other, dt)
                    }
                }

                idx = subEnd
                continue
            }

            executeAction(trimmed, self, other, dt)
            idx++
        }
    }

    private fun evalCondition(cond: String, self: PyEntity, other: PyEntity?): Boolean {
        if (cond.startsWith("input.is_pressed(") || cond.startsWith("input.is_key_down(")) {
            val key = extractArg(cond)
            return input.isPressed(key)
        }
        if (cond.startsWith("entity.has_item(") || cond.startsWith("self.has_item(")) {
            val item = extractArg(cond)
            return self.has_item(item)
        }
        if (cond.startsWith("other.has_item(") && other != null) {
            val item = extractArg(cond)
            return other.has_item(item)
        }

        val ops = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in ops) {
            if (cond.contains(op)) {
                val leftStr = cond.substringBefore(op).trim()
                val rightStr = cond.substringAfter(op).trim()

                if (leftStr.endsWith(".tag") || leftStr.endsWith(".name")) {
                    val leftVal = resolveStringProp(leftStr, self, other)
                    val rightVal = rightStr.replace("\"", "").replace("'", "").trim()
                    return when (op) {
                        "==" -> leftVal == rightVal
                        "!=" -> leftVal != rightVal
                        else -> false
                    }
                }

                val leftNum = evalNumeric(leftStr, self, other, 0f)
                val rightNum = evalNumeric(rightStr, self, other, 0f)
                return when (op) {
                    "==" -> abs(leftNum - rightNum) < 0.001f
                    "!=" -> abs(leftNum - rightNum) >= 0.001f
                    ">=" -> leftNum >= rightNum
                    "<=" -> leftNum <= rightNum
                    ">" -> leftNum > rightNum
                    "<" -> leftNum < rightNum
                    else -> false
                }
            }
        }
        return false
    }

    private fun resolveStringProp(expr: String, self: PyEntity, other: PyEntity?): String {
        return when (expr.trim()) {
            "entity.tag", "self.tag" -> self.tag
            "entity.name", "self.name" -> self.name
            "other.tag" -> other?.tag ?: ""
            "other.name" -> other?.name ?: ""
            else -> expr.replace("\"", "").replace("'", "").trim()
        }
    }

    private fun evalNumeric(expr: String, self: PyEntity, other: PyEntity?, dt: Float): Float {
        val clean = expr.trim()
        val addIdx = clean.lastIndexOf('+')
        if (addIdx > 0) {
            return evalNumeric(clean.substring(0, addIdx), self, other, dt) +
                   evalNumeric(clean.substring(addIdx + 1), self, other, dt)
        }
        val subIdx = clean.lastIndexOf('-')
        if (subIdx > 0) {
            val l = clean.substring(0, subIdx).trim()
            if (l.isNotEmpty() && !l.endsWith("*") && !l.endsWith("/")) {
                return evalNumeric(l, self, other, dt) - evalNumeric(clean.substring(subIdx + 1), self, other, dt)
            }
        }
        val mulIdx = clean.lastIndexOf('*')
        if (mulIdx > 0) {
            return evalNumeric(clean.substring(0, mulIdx), self, other, dt) *
                   evalNumeric(clean.substring(mulIdx + 1), self, other, dt)
        }

        clean.toFloatOrNull()?.let { return it }

        return when (clean) {
            "dt" -> dt
            "entity.x", "self.x" -> self.x
            "entity.y", "self.y" -> self.y
            "entity.vx", "self.vx" -> self.vx
            "entity.vy", "self.vy" -> self.vy
            "entity.health", "self.health" -> self.health
            "other.x" -> other?.x ?: 0f
            "other.y" -> other?.y ?: 0f
            "score", "entity.score", "self.score" -> scene.globalVariables["النقاط"]
                ?: (self.entity.attributes["النقاط"] as? Number)?.toFloat() ?: 0f
            else -> (self.entity.attributes[clean] as? Number)?.toFloat()
                ?: scene.globalVariables[clean] ?: 0f
        }
    }

    private fun executeAction(action: String, self: PyEntity, other: PyEntity?, dt: Float) {
        when {
            action.startsWith("self.shoot(") || action.startsWith("entity.shoot(") -> {
                val speed = evalNumeric(extractArg(action), self, other, dt).takeIf { it > 0f } ?: 580f
                self.shoot(speed)
            }
            action.startsWith("self.patrol(") || action.startsWith("entity.patrol(") -> {
                val inner = action.substringAfter("(").substringBeforeLast(")")
                val parts = inner.split(",")
                if (parts.size >= 2) {
                    val x1 = evalNumeric(parts[0], self, other, dt)
                    val x2 = evalNumeric(parts[1], self, other, dt)
                    val spd = if (parts.size >= 3) evalNumeric(parts[2], self, other, dt) else 120f
                    self.patrol(x1, x2, spd)
                }
            }
            action.startsWith("self.jump(") || action.startsWith("entity.jump(") -> {
                val power = evalNumeric(extractArg(action), self, other, dt).takeIf { it > 0f } ?: 380f
                self.jump(power)
            }
            action.startsWith("self.move(") || action.startsWith("entity.move(") -> {
                val inner = action.substringAfter("(").substringBeforeLast(")")
                val dx = evalNumeric(inner.substringBefore(","), self, other, dt)
                val dy = evalNumeric(inner.substringAfter(",", "0"), self, other, dt)
                self.move(dx, dy)
            }
            action.startsWith("self.add_item(") || action.startsWith("entity.add_item(") -> {
                val item = extractArg(action)
                self.add_item(item)
                logCallback?.invoke("🐍 Python [أضيف عنصر]: $item")
            }
            action.startsWith("other.add_item(") -> {
                val item = extractArg(action)
                other?.add_item(item)
                logCallback?.invoke("🐍 Python [أضيف عنصر]: $item")
            }
            action.startsWith("self.remove_item(") || action.startsWith("entity.remove_item(") -> {
                val item = extractArg(action)
                self.remove_item(item)
            }
            action.startsWith("self.destroy()") || action.startsWith("entity.destroy()") -> {
                self.destroy()
            }
            action.startsWith("other.destroy()") -> {
                other?.destroy()
            }
            action.startsWith("self.set_color(") || action.startsWith("entity.set_color(") -> {
                self.set_color(extractArg(action))
            }
            action.startsWith("print(") || action.startsWith("log(") -> {
                val msg = extractArg(action)
                logCallback?.invoke("🐍 $msg")
            }
            action.startsWith("audio.play(") || action.startsWith("sound.play(") -> {
                val sound = extractArg(action)
                audio.playSound(sound)
            }
            action.contains("+=") -> {
                val target = action.substringBefore("+=").trim()
                val delta = evalNumeric(action.substringAfter("+="), self, other, dt)
                assignPyProp(target, evalNumeric(target, self, other, dt) + delta, self, other)
            }
            action.contains("-=") -> {
                val target = action.substringBefore("-=").trim()
                val delta = evalNumeric(action.substringAfter("-="), self, other, dt)
                assignPyProp(target, evalNumeric(target, self, other, dt) - delta, self, other)
            }
            action.contains("=") && !action.contains("==") -> {
                val target = action.substringBefore("=").trim()
                val value = evalNumeric(action.substringAfter("="), self, other, dt)
                assignPyProp(target, value, self, other)
            }
        }
    }

    private fun assignPyProp(target: String, value: Float, self: PyEntity, other: PyEntity?) {
        when (target) {
            "self.x", "entity.x" -> self.x = value
            "self.y", "entity.y" -> self.y = value
            "self.vx", "entity.vx" -> self.vx = value
            "self.vy", "entity.vy" -> self.vy = value
            "self.health", "entity.health" -> self.health = value
            "other.health" -> other?.let { it.health = value }
            "score", "self.score", "entity.score" -> {
                self.entity.attributes["النقاط"] = value
                scene.globalVariables["النقاط"] = value
            }
            else -> {
                self.entity.attributes[target] = value
                scene.globalVariables[target] = value
            }
        }
    }

    private fun extractArg(str: String): String {
        val start = str.indexOf("(")
        val end = str.lastIndexOf(")")
        if (start != -1 && end > start) {
            val content = str.substring(start + 1, end).trim()
            return content.replace("\"", "").replace("'", "")
        }
        return ""
    }
}
