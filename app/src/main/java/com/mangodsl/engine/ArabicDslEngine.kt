package com.mangodsl.engine

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * مفسر لغة البرمجة العربية المطور لمحرك ألعاب Mango DSL
 * يدعم شجرة أوامر كاملة (AST)، العمليات الحسابية، الشروط مع (وإلا)،
 * نظام إطلاق المقذوفات (أطلق_مقذوف)، ودوريات الحراسة الذكية (تحرك_في_دورية بين ... و ...).
 */
class ArabicDslEngine(
    private val scene: GameScene,
    private val input: InputManager,
    private val audio: EngineAudioManager,
    private val logCallback: ((String) -> Unit)? = null
) {

    sealed class Statement {
        data class MoveDirection(val direction: String, val speedExpr: String) : Statement()
        data class MoveTowards(val targetName: String, val speedExpr: String) : Statement()
        data class PatrolBetween(val minXExpr: String, val maxXExpr: String, val speedExpr: String) : Statement()
        data class ShootProjectile(val speedExpr: String, val damageExpr: String) : Statement()
        data class Jump(val powerExpr: String) : Statement()
        data class IfKeyPressed(val key: String, val thenBranch: List<Statement>, val elseBranch: List<Statement> = emptyList()) : Statement()
        data class IfHasItem(val itemName: String, val thenBranch: List<Statement>, val elseBranch: List<Statement> = emptyList()) : Statement()
        data class IfCondition(val leftExpr: String, val op: String, val rightExpr: String, val thenBranch: List<Statement>, val elseBranch: List<Statement> = emptyList()) : Statement()
        data class RepeatLoop(val countExpr: String, val body: List<Statement>) : Statement()
        data class AddItem(val itemName: String) : Statement()
        data class RemoveItem(val itemName: String) : Statement()
        data class OpenEntity(val targetName: String) : Statement()
        data class CloseEntity(val targetName: String) : Statement()
        data class DestroyEntity(val targetName: String) : Statement()
        data class ChangeSprite(val spriteOrColor: String) : Statement()
        data class ChangePosition(val xExpr: String, val yExpr: String) : Statement()
        data class ChangeSize(val wExpr: String, val hExpr: String) : Statement()
        data class PlaySound(val soundName: String) : Statement()
        data class ShowMessage(val message: String) : Statement()
        data class SetVariable(val varName: String, val expr: String) : Statement()
        data class ShakeCamera(val intensityExpr: String) : Statement()
        object DisableCollision : Statement()
    }

    data class CompiledScript(
        val onStart: MutableList<Statement> = mutableListOf(),
        val onUpdate: MutableList<Statement> = mutableListOf(),
        val onCollision: MutableList<Pair<String, List<Statement>>> = mutableListOf()
    )

    private val scriptCache = mutableMapOf<String, CompiledScript>()

    fun compile(code: String): CompiledScript {
        val compiled = CompiledScript()
        val lines = code.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (line.startsWith("عند_البدء")) {
                val block = extractBlock(lines, i)
                compiled.onStart.addAll(parseStatements(block.first))
                i = block.second
            } else if (line.startsWith("في_كل_إطار")) {
                val block = extractBlock(lines, i)
                compiled.onUpdate.addAll(parseStatements(block.first))
                i = block.second
            } else if (line.startsWith("عند_التصادم مع") || line.startsWith("عند_التصادم")) {
                val target = line.removePrefix("عند_التصادم مع").removePrefix("عند_التصادم").replace("{", "").trim()
                val block = extractBlock(lines, i)
                compiled.onCollision.add(Pair(target, parseStatements(block.first)))
                i = block.second
            } else {
                val stmts = parseSingleLine(line)
                if (stmts != null) compiled.onUpdate.add(stmts)
                i++
            }
        }
        return compiled
    }

    private fun extractBlock(lines: List<String>, startIndex: Int): Pair<List<String>, Int> {
        val block = mutableListOf<String>()
        var depth = 0
        var i = startIndex
        var foundOpen = false

        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("{")) {
                depth += line.count { it == '{' }
                foundOpen = true
            }

            if (i > startIndex) {
                if (line.contains("}")) {
                    depth -= line.count { it == '}' }
                    val cleanLine = line.replace("}", "").trim()
                    if (cleanLine.isNotEmpty() && depth >= 0 && !cleanLine.startsWith("وإلا")) {
                        block.add(cleanLine)
                    }
                } else {
                    block.add(line)
                }
            }

            if (foundOpen && depth <= 0) {
                if (i > startIndex && line.contains("وإلا")) {
                    return Pair(block, i)
                }
                break
            }
            i++
        }
        return Pair(block, i + 1)
    }

    private fun extractOptionalElse(lines: List<String>, nextIdx: Int): Pair<List<Statement>, Int> {
        if (nextIdx < lines.size && (lines[nextIdx].contains("وإلا") || lines[nextIdx].contains("واإلا") || lines[nextIdx].contains("غير_ذلك"))) {
            val elseBlock = extractBlock(lines, nextIdx)
            return Pair(parseStatements(elseBlock.first), elseBlock.second)
        }
        return Pair(emptyList(), nextIdx)
    }

    private fun parseStatements(lines: List<String>): List<Statement> {
        val result = mutableListOf<Statement>()
        var idx = 0

        while (idx < lines.size) {
            val line = lines[idx].trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                idx++
                continue
            }

            if (line.startsWith("إذا ضغط اللاعب") || line.startsWith("اذا ضغط اللاعب") || line.startsWith("اذا ضغط الاعب") || line.startsWith("إذا استمر الضغط على")) {
                val key = extractQuotesOrWord(
                    line.removePrefix("إذا ضغط اللاعب")
                        .removePrefix("اذا ضغط اللاعب")
                        .removePrefix("اذا ضغط الاعب")
                        .removePrefix("إذا استمر الضغط على")
                )
                val block = extractBlock(lines, idx)
                val elsePair = extractOptionalElse(lines, block.second)
                result.add(Statement.IfKeyPressed(key, parseStatements(block.first), elsePair.first))
                idx = elsePair.second
                continue
            }

            if (line.startsWith("إذا توفر عنصر") || line.startsWith("اذا توفر عنصر")) {
                val after = line.removePrefix("إذا توفر عنصر").removePrefix("اذا توفر عنصر")
                val itemName = extractQuotesOrWord(after.substringBefore("في المخزون"))
                val block = extractBlock(lines, idx)
                val elsePair = extractOptionalElse(lines, block.second)
                result.add(Statement.IfHasItem(itemName, parseStatements(block.first), elsePair.first))
                idx = elsePair.second
                continue
            }

            if (line.startsWith("كرر ") || line.startsWith("تكرار ")) {
                val countExpr = line.removePrefix("كرر").removePrefix("تكرار").substringBefore("مرات").replace("{", "").trim()
                val block = extractBlock(lines, idx)
                result.add(Statement.RepeatLoop(countExpr.ifEmpty { "1" }, parseStatements(block.first)))
                idx = block.second
                continue
            }

            if (line.startsWith("إذا ") || line.startsWith("اذا ")) {
                val condHeader = line.removePrefix("إذا ").removePrefix("اذا ").substringBefore("{").trim()
                val parsedCond = parseComparisonHeader(condHeader)
                if (parsedCond != null) {
                    val block = extractBlock(lines, idx)
                    val elsePair = extractOptionalElse(lines, block.second)
                    result.add(
                        Statement.IfCondition(
                            parsedCond.first,
                            parsedCond.second,
                            parsedCond.third,
                            parseStatements(block.first),
                            elsePair.first
                        )
                    )
                    idx = elsePair.second
                    continue
                }
            }

            val stmt = parseSingleLine(line)
            if (stmt != null) {
                result.add(stmt)
            }
            idx++
        }
        return result
    }

    private fun parseComparisonHeader(cond: String): Triple<String, String, String>? {
        val operators = listOf(">=", "<=", "!=", "==", ">", "<", "أكبر_أو_يساوي", "أصغر_أو_يساوي", "لا_يساوي", "يساوي", "أكبر_من", "أصغر_من")
        for (op in operators) {
            if (cond.contains(op)) {
                val left = cond.substringBefore(op).trim()
                val right = cond.substringAfter(op).trim()
                val normalizedOp = when (op) {
                    "أكبر_أو_يساوي" -> ">="
                    "أصغر_أو_يساوي" -> "<="
                    "لا_يساوي" -> "!="
                    "يساوي" -> "=="
                    "أكبر_من" -> ">"
                    "أصغر_من" -> "<"
                    else -> op
                }
                return Triple(left, normalizedOp, right)
            }
        }
        return null
    }

    private fun parseSingleLine(line: String): Statement? {
        val clean = line.replace(";", "").trim()

        // تحرك_في_دورية بين 420 و 610 بسرعة 115
        if (clean.startsWith("تحرك_في_دورية") || clean.startsWith("تحرك في دورية") || clean.startsWith("دورية")) {
            val after = clean.removePrefix("تحرك_في_دورية").removePrefix("تحرك في دورية").removePrefix("دورية").removePrefix("بين").trim()
            val minX = after.substringBefore("و").trim()
            val rest = after.substringAfter("و", "600").trim()
            val maxX = rest.substringBefore("بسرعة").trim()
            val speed = rest.substringAfter("بسرعة", "110").trim()
            return Statement.PatrolBetween(minX, maxX, speed)
        }

        // أطلق_مقذوف بسرعة 560 بقوة 35
        if (clean.startsWith("أطلق_مقذوف") || clean.startsWith("اطلق_مقذوف") || clean.startsWith("أطلق مقذوف") || clean.startsWith("اطلق مقذوف")) {
            val after = clean.removePrefix("أطلق_مقذوف").removePrefix("اطلق_مقذوف").removePrefix("أطلق مقذوف").removePrefix("اطلق مقذوف").trim()
            val speed = if (after.contains("بسرعة")) after.substringAfter("بسرعة").substringBefore("بقوة").trim() else after.ifEmpty { "560" }
            val dmg = if (after.contains("بقوة")) after.substringAfter("بقوة").trim() else "35"
            return Statement.ShootProjectile(speed.ifEmpty { "560" }, dmg.ifEmpty { "35" })
        }

        if (clean.startsWith("تحرك في اتجاه") || clean.startsWith("تحرك في اتجاة")) {
            val parts = clean.removePrefix("تحرك في اتجاه").removePrefix("تحرك في اتجاة").trim()
            val dir = parts.substringBefore("بسرعة").trim()
            val speed = parts.substringAfter("بسرعة", "180").trim()
            return Statement.MoveDirection(dir, speed)
        }

        if (clean.startsWith("تحرك نحو كائن") || clean.startsWith("تحرك نحو")) {
            val parts = clean.removePrefix("تحرك نحو كائن").removePrefix("تحرك نحو").trim()
            val target = parts.substringBefore("بسرعة").trim()
            val speed = parts.substringAfter("بسرعة", "100").trim()
            return Statement.MoveTowards(target, speed)
        }

        if (clean.startsWith("اقفز بقوة") || clean.startsWith("اقفز")) {
            val power = clean.removePrefix("اقفز بقوة").removePrefix("اقفز").trim()
            return Statement.Jump(power.ifEmpty { "380" })
        }

        if (clean.startsWith("أضف عنصر") || clean.startsWith("اضف عنصر")) {
            val item = extractQuotesOrWord(clean.removePrefix("أضف عنصر").removePrefix("اضف عنصر").substringBefore("إلى المخزون"))
            return Statement.AddItem(item)
        }

        if (clean.startsWith("احذف عنصر")) {
            val item = extractQuotesOrWord(clean.removePrefix("احذف عنصر").substringBefore("من المخزون"))
            return Statement.RemoveItem(item)
        }

        if (clean.startsWith("افتح")) {
            val target = clean.removePrefix("افتح").trim()
            return Statement.OpenEntity(target)
        }

        if (clean.startsWith("أغلق") || clean.startsWith("اغلق")) {
            val target = clean.removePrefix("أغلق").removePrefix("اغلق").trim()
            return Statement.CloseEntity(target)
        }

        if (clean.startsWith("دمر_الكائن") || clean.startsWith("دمر")) {
            val target = clean.removePrefix("دمر_الكائن").removePrefix("دمر").trim()
            return Statement.DestroyEntity(target)
        }

        if (clean.startsWith("غير المظهر إلى") || clean.startsWith("غير_المظهر إلى")) {
            val res = extractQuotesOrWord(clean.removePrefix("غير المظهر إلى").removePrefix("غير_المظهر إلى"))
            return Statement.ChangeSprite(res)
        }

        if (clean.startsWith("ضع_في_الموضع") || clean.startsWith("انتقل_إلى")) {
            val coords = clean.removePrefix("ضع_في_الموضع").removePrefix("انتقل_إلى").trim()
            val xExpr = coords.substringBefore(",").substringBefore("،").trim()
            val yExpr = coords.substringAfter(",", coords.substringAfter("،", "0")).trim()
            return Statement.ChangePosition(xExpr, yExpr)
        }

        if (clean.startsWith("غير_الحجم إلى") || clean.startsWith("غير الحجم إلى")) {
            val dims = clean.removePrefix("غير_الحجم إلى").removePrefix("غير الحجم إلى").trim()
            val wExpr = dims.substringBefore(",").substringBefore("،").trim()
            val hExpr = dims.substringAfter(",", dims.substringAfter("،", "64")).trim()
            return Statement.ChangeSize(wExpr, hExpr)
        }

        if (clean.startsWith("شغل صوت") || clean.startsWith("شغل_صوت")) {
            val sound = extractQuotesOrWord(clean.removePrefix("شغل صوت").removePrefix("شغل_صوت"))
            return Statement.PlaySound(sound)
        }

        if (clean.startsWith("أظهر رسالة") || clean.startsWith("اظهر رسالة")) {
            val msg = extractQuotesOrWord(clean.removePrefix("أظهر رسالة").removePrefix("اظهر رسالة"))
            return Statement.ShowMessage(msg)
        }

        if (clean.startsWith("اهتزاز_الكاميرا") || clean.startsWith("هز_الكاميرا")) {
            val intensity = clean.removePrefix("اهتزاز_الكاميرا").removePrefix("هز_الكاميرا").trim().ifEmpty { "14" }
            return Statement.ShakeCamera(intensity)
        }

        if (clean.contains("عطّل التصادم") || clean.contains("عطل التصادم")) {
            return Statement.DisableCollision
        }

        if (clean.startsWith("زد ") || clean.startsWith("أضف_إلى ")) {
            val body = clean.removePrefix("زد ").removePrefix("أضف_إلى ").trim()
            val varName = body.substringBefore("بمقدار").trim()
            val delta = body.substringAfter("بمقدار", "1").trim()
            return Statement.SetVariable(varName, "$varName + $delta")
        }

        if (clean.startsWith("أنقص ") || clean.startsWith("انقص ")) {
            val body = clean.removePrefix("أنقص ").removePrefix("انقص ").trim()
            val varName = body.substringBefore("بمقدار").trim()
            val delta = body.substringAfter("بمقدار", "1").trim()
            return Statement.SetVariable(varName, "$varName - $delta")
        }

        if (clean.startsWith("اجعل")) {
            val assignment = clean.removePrefix("اجعل").trim()
            val varName = assignment.substringBefore("=").trim()
            val expr = assignment.substringAfter("=").trim()
            return Statement.SetVariable(varName, expr)
        } else if (clean.contains("=")) {
            val varName = clean.substringBefore("=").trim()
            val expr = clean.substringAfter("=").trim()
            return Statement.SetVariable(varName, expr)
        }

        return null
    }

    private fun extractQuotesOrWord(text: String): String {
        val str = text.trim()
        if (str.contains("\"")) {
            val start = str.indexOf("\"")
            val end = str.indexOf("\"", start + 1)
            if (start != -1 && end != -1) {
                return str.substring(start + 1, end).trim()
            }
        }
        return str.replace("{", "").replace("}", "").trim()
    }

    fun executeStart(entity: GameEntity) {
        val script = getOrCompile(entity)
        executeStatements(entity, script.onStart, null)
    }

    fun executeUpdate(entity: GameEntity, dt: Float) {
        val script = getOrCompile(entity)
        executeStatements(entity, script.onUpdate, null)
    }

    fun executeCollision(entity: GameEntity, other: GameEntity) {
        val script = getOrCompile(entity)
        for ((target, stmts) in script.onCollision) {
            if (target.isEmpty() || target == "الكل" || target == other.name || target == other.tag) {
                executeStatements(entity, stmts, other)
            }
        }
    }

    private fun getOrCompile(entity: GameEntity): CompiledScript {
        return scriptCache.getOrPut(entity.id + "_" + entity.scriptCode.hashCode()) {
            compile(entity.scriptCode)
        }
    }

    private fun executeStatements(entity: GameEntity, statements: List<Statement>, other: GameEntity?) {
        for (stmt in statements) {
            when (stmt) {
                is Statement.MoveDirection -> {
                    val speed = evalExpression(stmt.speedExpr, entity, 180f)
                    when (stmt.direction.trim()) {
                        "يمين", "شرق", "right" -> entity.velocity.x = speed
                        "يسار", "غرب", "left" -> entity.velocity.x = -speed
                        "أعلى", "اعلى", "شمال", "up" -> entity.velocity.y = -speed
                        "أسفل", "اسفل", "جنوب", "down" -> entity.velocity.y = speed
                        "توقف", "ثابت", "stop" -> {
                            entity.velocity.x = 0f
                            if (!entity.affectedByGravity) entity.velocity.y = 0f
                        }
                    }
                }
                is Statement.MoveTowards -> {
                    val speed = evalExpression(stmt.speedExpr, entity, 100f)
                    val target = scene.findEntityByName(stmt.targetName)
                    if (target != null) {
                        val dx = target.position.x - entity.position.x
                        val dy = target.position.y - entity.position.y
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist > 2f) {
                            entity.velocity.x = (dx / dist) * speed
                            if (!entity.affectedByGravity) {
                                entity.velocity.y = (dy / dist) * speed
                            }
                        }
                    }
                }
                is Statement.PatrolBetween -> {
                    val x1 = evalExpression(stmt.minXExpr, entity, 300f)
                    val x2 = evalExpression(stmt.maxXExpr, entity, 600f)
                    val low = min(x1, x2)
                    val high = max(x1, x2)
                    val speed = evalExpression(stmt.speedExpr, entity, 110f)

                    if (entity.position.x <= low) {
                        entity.patrolDirection = 1
                        entity.facingRight = true
                    } else if (entity.position.x >= high) {
                        entity.patrolDirection = -1
                        entity.facingRight = false
                    }
                    entity.velocity.x = entity.patrolDirection * speed
                }
                is Statement.ShootProjectile -> {
                    val speed = evalExpression(stmt.speedExpr, entity, 560f)
                    val dmg = evalExpression(stmt.damageExpr, entity, 35f)
                    val created = scene.spawnProjectile(entity, speed, dmg)
                    if (created != null) {
                        audio.playSound("إطلاق")
                    }
                }
                is Statement.Jump -> {
                    val power = evalExpression(stmt.powerExpr, entity, 380f)
                    if (entity.isGrounded || !entity.affectedByGravity) {
                        entity.velocity.y = -power
                        entity.isGrounded = false
                        scene.spawnParticles(
                            entity.position.x + entity.width / 2f,
                            entity.position.y + entity.height,
                            Color.parseColor("#B0BEC5"),
                            8
                        )
                    }
                }
                is Statement.IfKeyPressed -> {
                    if (input.isPressed(stmt.key)) {
                        executeStatements(entity, stmt.thenBranch, other)
                    } else if (stmt.elseBranch.isNotEmpty()) {
                        executeStatements(entity, stmt.elseBranch, other)
                    }
                }
                is Statement.IfHasItem -> {
                    val holder = if (entity.hasItem(stmt.itemName)) entity
                    else if (other != null && other.hasItem(stmt.itemName)) other
                    else scene.findEntityByName("اللاعب")?.takeIf { it.hasItem(stmt.itemName) }

                    if (holder != null) {
                        executeStatements(entity, stmt.thenBranch, other)
                    } else {
                        executeStatements(entity, stmt.elseBranch, other)
                    }
                }
                is Statement.IfCondition -> {
                    val left = evalExpression(stmt.leftExpr, entity, 0f)
                    val right = evalExpression(stmt.rightExpr, entity, 0f)
                    val conditionMet = when (stmt.op) {
                        "==" -> kotlin.math.abs(left - right) < 0.001f
                        "!=" -> kotlin.math.abs(left - right) >= 0.001f
                        ">" -> left > right
                        "<" -> left < right
                        ">=" -> left >= right
                        "<=" -> left <= right
                        else -> false
                    }
                    if (conditionMet) {
                        executeStatements(entity, stmt.thenBranch, other)
                    } else {
                        executeStatements(entity, stmt.elseBranch, other)
                    }
                }
                is Statement.RepeatLoop -> {
                    val count = evalExpression(stmt.countExpr, entity, 1f).toInt().coerceIn(0, 50)
                    for (r in 0 until count) {
                        executeStatements(entity, stmt.body, other)
                    }
                }
                is Statement.AddItem -> {
                    val receiver = if (entity.tag == "عنصر" && other != null) other else entity
                    receiver.addItem(stmt.itemName)
                    scene.spawnParticles(entity.position.x + entity.width / 2f, entity.position.y + entity.height / 2f, entity.color, 14)
                    logCallback?.invoke("🎒 أضيف للمخزون: ${stmt.itemName}")
                }
                is Statement.RemoveItem -> {
                    if (!entity.removeItem(stmt.itemName)) {
                        other?.removeItem(stmt.itemName)
                        scene.findEntityByName("اللاعب")?.removeItem(stmt.itemName)
                    }
                    logCallback?.invoke("🎒 استُخدم العنصر: ${stmt.itemName}")
                }
                is Statement.OpenEntity -> {
                    val target = if (stmt.targetName == "هذا_الكائن" || stmt.targetName.isEmpty()) entity else scene.findEntityByName(stmt.targetName)
                    target?.let {
                        it.attributes["حالة"] = "مفتوح"
                        it.isSolid = false
                        it.color = Color.parseColor("#81C784")
                        scene.spawnParticles(it.position.x + it.width / 2f, it.position.y + it.height / 2f, Color.parseColor("#00E676"), 18)
                        logCallback?.invoke("🔓 تم فتح: ${it.name}")
                    }
                }
                is Statement.CloseEntity -> {
                    val target = if (stmt.targetName == "هذا_الكائن" || stmt.targetName.isEmpty()) entity else scene.findEntityByName(stmt.targetName)
                    target?.let {
                        it.attributes["حالة"] = "مغلق"
                        it.isSolid = true
                        it.color = Color.parseColor("#8D6E63")
                    }
                }
                is Statement.DestroyEntity -> {
                    val target = when (stmt.targetName) {
                        "هذا_الكائن", "" -> entity
                        "الآخر", "الكائن_الآخر" -> other
                        else -> scene.findEntityByName(stmt.targetName)
                    }
                    target?.let {
                        scene.spawnParticles(it.position.x + it.width / 2f, it.position.y + it.height / 2f, it.color, 18)
                        it.isActive = false
                        scene.removeEntity(it)
                    }
                }
                is Statement.ChangeSprite -> {
                    val visual = stmt.spriteOrColor.trim()
                    if (visual.startsWith("#")) {
                        try { entity.color = Color.parseColor(visual) } catch (e: Exception) {}
                    } else {
                        entity.spriteName = visual
                        entity.spriteBitmap = null
                        when {
                            visual.contains("مفتوح") -> entity.color = Color.parseColor("#81C784")
                            visual.contains("حجر") -> entity.color = Color.parseColor("#BA68C8")
                            visual.contains("ذهب") || visual.contains("عملة") -> entity.color = Color.parseColor("#FFD54F")
                            visual.contains("باب") -> entity.color = Color.parseColor("#8D6E63")
                        }
                    }
                }
                is Statement.ChangePosition -> {
                    entity.position.x = evalExpression(stmt.xExpr, entity, entity.position.x)
                    entity.position.y = evalExpression(stmt.yExpr, entity, entity.position.y)
                }
                is Statement.ChangeSize -> {
                    entity.width = evalExpression(stmt.wExpr, entity, entity.width).coerceAtLeast(12f)
                    entity.height = evalExpression(stmt.hExpr, entity, entity.height).coerceAtLeast(12f)
                }
                is Statement.PlaySound -> {
                    audio.playSound(stmt.soundName)
                }
                is Statement.ShowMessage -> {
                    logCallback?.invoke(stmt.message)
                }
                is Statement.ShakeCamera -> {
                    val intensity = evalExpression(stmt.intensityExpr, entity, 14f)
                    scene.camera.triggerShake(intensity, 0.3f)
                }
                is Statement.SetVariable -> {
                    val value = evalExpression(stmt.expr, entity, 0f)
                    assignVariable(entity, stmt.varName, value)
                }
                is Statement.DisableCollision -> {
                    entity.isSolid = false
                }
            }
        }
    }

    private fun assignVariable(entity: GameEntity, varName: String, value: Float) {
        when (varName.trim()) {
            "الصحة", "صحة" -> entity.health = value.coerceIn(0f, entity.maxHealth)
            "الموضع_س", "س" -> entity.position.x = value
            "الموضع_ص", "ص" -> entity.position.y = value
            "السرعة_س" -> entity.velocity.x = value
            "السرعة_ص" -> entity.velocity.y = value
            "العرض" -> entity.width = value
            "الارتفاع" -> entity.height = value
            "النقاط", "نقاط" -> {
                entity.attributes["النقاط"] = value
                scene.globalVariables["النقاط"] = value
                scene.findEntityByName("اللاعب")?.attributes?.put("النقاط", value)
            }
            else -> {
                entity.attributes[varName.trim()] = value
                scene.globalVariables[varName.trim()] = value
            }
        }
    }

    fun evalExpression(expr: String, entity: GameEntity, defaultVal: Float): Float {
        val clean = expr.trim()
        if (clean.isEmpty()) return defaultVal

        if (clean.startsWith("عشوائي(") && clean.endsWith(")")) {
            val inner = clean.removePrefix("عشوائي(").removeSuffix(")")
            val minV = evalExpression(inner.substringBefore(",").substringBefore("،"), entity, 0f)
            val maxV = evalExpression(inner.substringAfter(",", inner.substringAfter("،", "100")), entity, 100f)
            return if (maxV > minV) minV + Random.nextFloat() * (maxV - minV) else minV
        }

        val addIdx = clean.lastIndexOf('+')
        if (addIdx > 0) {
            val left = clean.substring(0, addIdx).trim()
            val right = clean.substring(addIdx + 1).trim()
            return evalExpression(left, entity, 0f) + evalExpression(right, entity, 0f)
        }

        val subIdx = clean.lastIndexOf('-')
        if (subIdx > 0) {
            val left = clean.substring(0, subIdx).trim()
            val right = clean.substring(subIdx + 1).trim()
            if (left.isNotEmpty() && !left.endsWith("*") && !left.endsWith("/")) {
                return evalExpression(left, entity, 0f) - evalExpression(right, entity, 0f)
            }
        }

        val mulIdx = clean.lastIndexOf('*')
        if (mulIdx > 0) {
            val left = clean.substring(0, mulIdx).trim()
            val right = clean.substring(mulIdx + 1).trim()
            return evalExpression(left, entity, 1f) * evalExpression(right, entity, 1f)
        }

        val divIdx = clean.lastIndexOf('/')
        if (divIdx > 0) {
            val left = clean.substring(0, divIdx).trim()
            val right = clean.substring(divIdx + 1).trim()
            val denom = evalExpression(right, entity, 1f)
            return if (denom != 0f) evalExpression(left, entity, 0f) / denom else 0f
        }

        clean.toFloatOrNull()?.let { return it }

        when (clean) {
            "السرعة", "سرعة" -> return (entity.attributes["السرعة"] as? Number)?.toFloat() ?: 200f
            "الصحة", "صحة" -> return entity.health
            "الموضع_س", "س" -> return entity.position.x
            "الموضع_ص", "ص" -> return entity.position.y
            "العرض" -> return entity.width
            "الارتفاع" -> return entity.height
            "النقاط", "نقاط" -> return scene.globalVariables["النقاط"]
                ?: (entity.attributes["النقاط"] as? Number)?.toFloat() ?: 0f
        }

        (entity.attributes[clean] as? Number)?.let { return it.toFloat() }
        scene.globalVariables[clean]?.let { return it }

        return defaultVal
    }
}
