package com.mangodsl.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * مدير المشاريع والأصول في Mango DSL
 * يتولى حفظ واسترجاع المشاهد بصيغة JSON، واستيراد الصور والأصوات الحقيقية من الهاتف،
 * وتحميل الألعاب النموذجية المدمجة مع نظام إطلاق المقذوفات والأعداء المتجولين بدوريات ذكية.
 */
object ProjectManager {

    data class ImportedAsset(
        val name: String,
        val type: String, // "image" or "audio"
        val localPath: String,
        val description: String
    )

    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    private fun getAssetsStoreDir(context: Context): File {
        val dir = File(context.filesDir, "mango_assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun importAssetFromUri(context: Context, uri: Uri, audioManager: EngineAudioManager? = null): ImportedAsset? {
        return try {
            var displayName = "asset_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIdx) ?: displayName
                }
            }
            val mime = context.contentResolver.getType(uri) ?: ""
            val isAudio = mime.startsWith("audio") ||
                displayName.endsWith(".ogg", true) ||
                displayName.endsWith(".mp3", true) ||
                displayName.endsWith(".wav", true)

            val cleanName = displayName.replace("[^a-zA-Z0-9._\\u0600-\\u06FF-]".toRegex(), "_")
            val destFile = File(getAssetsStoreDir(context), cleanName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (isAudio) {
                audioManager?.loadExternalSound(cleanName, destFile.absolutePath)
                ImportedAsset(cleanName, "audio", destFile.absolutePath, "مؤثر صوتي مستورد من الهاتف")
            } else {
                val bmp = BitmapFactory.decodeFile(destFile.absolutePath)
                if (bmp != null) {
                    bitmapCache[cleanName.lowercase()] = bmp
                    bitmapCache[cleanName.substringBeforeLast(".").lowercase()] = bmp
                }
                ImportedAsset(cleanName, "image", destFile.absolutePath, "صورة Sprite مستوردة من الهاتف")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun listAllAssets(context: Context, audioManager: EngineAudioManager? = null): List<ImportedAsset> {
        val list = mutableListOf(
            ImportedAsset("قفزة_البطل", "audio", "builtin", "مؤثر صوتي توليدي للقفز (مدمج)"),
            ImportedAsset("إطلاق", "audio", "builtin", "مؤثر إطلاق المقذوفات والليزر (مدمج)"),
            ImportedAsset("التقاط_الحجر", "audio", "builtin", "مؤثر رنين التقاط العناصر (مدمج)"),
            ImportedAsset("رنين_العملة", "audio", "builtin", "مؤثر رنين العملة الذهبية (مدمج)"),
            ImportedAsset("فتح_الباب", "audio", "builtin", "مؤثر فتح البوابة الحجرية (مدمج)"),
            ImportedAsset("ضربة", "audio", "builtin", "مؤثر إصابة العدو أو الضرر (مدمج)"),
            ImportedAsset("انفجار", "audio", "builtin", "مؤثر القضاء على العدو (مدمج)"),
            ImportedAsset("نغمة_فوز", "audio", "builtin", "مؤثر الفوز وإتمام المهمة (مدمج)")
        )

        val dir = getAssetsStoreDir(context)
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val isAudio = file.name.endsWith(".ogg", true) ||
                file.name.endsWith(".mp3", true) ||
                file.name.endsWith(".mp4", true) ||
                file.name.endsWith(".wav", true)
            if (isAudio) {
                audioManager?.loadExternalSound(file.name, file.absolutePath)
                list.add(ImportedAsset(file.name, "audio", file.absolutePath, "ملف صوتي مستورد"))
            } else {
                list.add(ImportedAsset(file.name, "image", file.absolutePath, "صورة Sprite مستوردة"))
            }
        }
        return list
    }

    fun getLoadedBitmap(context: Context, spriteName: String): Bitmap? {
        val key = spriteName.trim().lowercase()
        if (key.isEmpty()) return null
        bitmapCache[key]?.let { return it }

        val dir = getAssetsStoreDir(context)
        val candidate = File(dir, spriteName)
        val fileToLoad = if (candidate.exists()) candidate else {
            dir.listFiles()?.firstOrNull {
                it.name.equals(spriteName, true) ||
                it.name.substringBeforeLast(".").equals(spriteName, true)
            }
        }
        if (fileToLoad != null && fileToLoad.exists()) {
            val bmp = BitmapFactory.decodeFile(fileToLoad.absolutePath)
            if (bmp != null) {
                bitmapCache[key] = bmp
                return bmp
            }
        }
        return null
    }

    /**
     * 1. لعبة "لغز الحجر السحري وحراس القلعة" بلغة الضاد (تتضمن إطلاق مقذوفات وأعداء بدوريات ذكية)
     */
    fun createDefaultMagicStoneGame(): GameScene {
        val scene = GameScene("مغامرة_الحجر_السحري")
        scene.backgroundColor = Color.parseColor("#141824")
        scene.gravity = 920f

        // 1. اللاعب (يركض بالأسهم، يقفز بزر S، ويطلق مقذوفات طاقة بزر B)
        val player = GameEntity(name = "اللاعب", tag = "بطل").apply {
            position.set(80f, 340f)
            width = 54f
            height = 68f
            color = Color.parseColor("#00E676")
            isSolid = true
            affectedByGravity = true
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """عند_البدء {
    السرعة = 235
    الصحة = 100
    النقاط = 0
}

في_كل_إطار {
    إذا ضغط اللاعب "يمين" {
        تحرك في اتجاه يمين بسرعة السرعة
    }
    إذا ضغط اللاعب "يسار" {
        تحرك في اتجاه يسار بسرعة السرعة
    }
    إذا ضغط اللاعب "s" {
        اقفز بقوة 455
        شغل صوت "قفزة_البطل"
    }
    إذا ضغط اللاعب "b" {
        أطلق_مقذوف بسرعة 580 بقوة 35
    }
}"""
        }
        scene.addEntity(player)

        // 2. أرضية المرحلة
        val ground = GameEntity(name = "أرضية_المرحلة", tag = "جدار").apply {
            position.set(0f, 520f)
            width = 1600f
            height = 90f
            color = Color.parseColor("#37474F")
            isSolid = true
            affectedByGravity = false
            scriptType = ScriptType.NONE
        }
        scene.addEntity(ground)

        // 3. منصة حجرية مرتفعة
        val platform1 = GameEntity(name = "منصة_حجرية", tag = "جدار").apply {
            position.set(240f, 380f)
            width = 200f
            height = 34f
            color = Color.parseColor("#546E7A")
            isSolid = true
            affectedByGravity = false
            scriptType = ScriptType.NONE
        }
        scene.addEntity(platform1)

        // 4. عملة ذهبية فوق المنصة
        val coin = GameEntity(name = "عملة_ذهبية", tag = "عنصر").apply {
            position.set(280f, 320f)
            width = 36f
            height = 36f
            color = Color.parseColor("#FFD54F")
            isSolid = false
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """عند_التصادم مع اللاعب {
    زد النقاط بمقدار 50
    شغل صوت "رنين_العملة"
    أظهر رسالة "حصلت على 50 نقطة ذهبية!"
    دمر_الكائن هذا_الكائن
}"""
        }
        scene.addEntity(coin)

        // 5. الحجر السحري
        val magicStone = GameEntity(name = "حجر_سحري", tag = "عنصر").apply {
            position.set(365f, 315f)
            width = 44f
            height = 44f
            color = Color.parseColor("#E040FB")
            isSolid = false
            affectedByGravity = false
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """عند_التصادم مع اللاعب {
    أضف عنصر "حجر_سحري" إلى المخزون
    زد النقاط بمقدار 100
    شغل صوت "التقاط_الحجر"
    أظهر رسالة "حصلت على الحجر السحري العظيم (+100 نقطة)!"
    دمر_الكائن هذا_الكائن
}"""
        }
        scene.addEntity(magicStone)

        // 6. العدو الأول: حارس البوابة الذكي (يتجول في دورية بين 440 و 610 ويتأثر بالمقذوفات)
        val guardEnemy = GameEntity(name = "حارس_البوابة", tag = "عدو").apply {
            position.set(500f, 458f)
            width = 56f
            height = 62f
            color = Color.parseColor("#EF5350")
            isSolid = true
            affectedByGravity = true
            health = 100f
            maxHealth = 100f
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """في_كل_إطار {
    تحرك_في_دورية بين 430 و 600 بسرعة 115
}

عند_التصادم مع مقذوف {
    أنقص الصحة بمقدار 35
    شغل صوت "ضربة"
    اهتزاز_الكاميرا 8
    دمر_الكائن الآخر
    إذا الصحة <= 0 {
        زد النقاط بمقدار 150
        شغل صوت "انفجار"
        اهتزاز_الكاميرا 16
        أظهر رسالة "🎯 قضيت على حارس البوابة (+150 نقطة)!"
        دمر_الكائن هذا_الكائن
    }
}

عند_التصادم مع اللاعب {
    شغل صوت "ضربة"
    اهتزاز_الكاميرا 10
    أظهر رسالة "⚠️ احذر من حارس البوابة! اضغط زر B لإطلاق المقذوفات عليه!"
}"""
        }
        scene.addEntity(guardEnemy)

        // 7. الباب الحجري المغلق
        val stoneDoor = GameEntity(name = "الباب_الحجري", tag = "بوابة").apply {
            position.set(680f, 390f)
            width = 64f
            height = 130f
            color = Color.parseColor("#8D6E63")
            isSolid = true
            affectedByGravity = false
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """عند_التصادم مع اللاعب {
    إذا توفر عنصر "حجر_سحري" في المخزون {
        افتح الباب_الحجري
        احذف عنصر "حجر_سحري" من المخزون
        زد النقاط بمقدار 250
        شغل صوت "فتح_الباب"
        اهتزاز_الكاميرا 14
        غير المظهر إلى "#81C784"
        عطّل التصادم مع هذا_الكائن
        أظهر رسالة "فُتحت البوابة ببركة الحجر السحري! (+250 نقطة)"
    } وإلا {
        أظهر رسالة "تحتاج إلى الحجر السحري البنفسجي لفتح البوابة!"
    }
}"""
        }
        scene.addEntity(stoneDoor)

        // 8. العدو الثاني: حارس الكنز الذكي خلف البوابة
        val treasureGuard = GameEntity(name = "حارس_الكنز", tag = "عدو").apply {
            position.set(800f, 458f)
            width = 58f
            height = 62f
            color = Color.parseColor("#FF7043")
            isSolid = true
            affectedByGravity = true
            health = 100f
            maxHealth = 100f
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """في_كل_إطار {
    تحرك_في_دورية بين 760 و 920 بسرعة 135
}

عند_التصادم مع مقذوف {
    أنقص الصحة بمقدار 35
    شغل صوت "ضربة"
    دمر_الكائن الآخر
    إذا الصحة <= 0 {
        زد النقاط بمقدار 200
        شغل صوت "انفجار"
        اهتزاز_الكاميرا 16
        أظهر رسالة "🔥 هزمت حارس الكنز (+200 نقطة)!"
        دمر_الكائن هذا_الكائن
    }
}"""
        }
        scene.addEntity(treasureGuard)

        // 9. كنز النصر النهائي
        val chest = GameEntity(name = "كنز_النصر", tag = "عنصر").apply {
            position.set(980f, 455f)
            width = 60f
            height = 65f
            color = Color.parseColor("#FFA726")
            isSolid = false
            scriptType = ScriptType.ARABIC_DSL
            scriptCode = """عند_التصادم مع اللاعب {
    زد النقاط بمقدار 500
    شغل صوت "نغمة_فوز"
    اهتزاز_الكاميرا 18
    أظهر رسالة "🏆 مبروك! وصلت إلى كنز النصر وأنهيت المرحلة!"
    دمر_الكائن هذا_الكائن
}"""
        }
        scene.addEntity(chest)

        scene.camera.targetEntity = player
        return scene
    }

    /**
     * 2. لعبة "معركة الفضاء" بلغة بايثون (تتضمن إطلاق ليزر بزر B ودوريات مركبات معادية)
     */
    fun createPythonDemoGame(): GameScene {
        val scene = GameScene("سباق_الفضاء_بايثون")
        scene.backgroundColor = Color.parseColor("#0B0C10")
        scene.gravity = 0f

        val ship = GameEntity(name = "مركبة_الفضاء", tag = "لاعب").apply {
            position.set(160f, 280f)
            width = 62f
            height = 58f
            color = Color.parseColor("#29B6F6")
            isSolid = true
            affectedByGravity = false
            scriptType = ScriptType.PYTHON
            scriptCode = """def on_start(entity):
    print("Ship online! Press B to fire lasers!")
    entity.health = 100
    entity.score = 0

def on_update(entity, dt):
    if input.is_pressed("يمين"):
        entity.vx = 260
    if input.is_pressed("يسار"):
        entity.vx = -260
    if input.is_pressed("أعلى"):
        entity.vy = -260
    if input.is_pressed("أسفل"):
        entity.vy = 260
    if input.is_pressed("b"):
        entity.shoot(620)
    if input.is_pressed("s"):
        audio.play("قفزة_البطل")
        entity.vx = 440
"""
        }
        scene.addEntity(ship)

        val drone = GameEntity(name = "مقاتلة_معادية", tag = "عدو").apply {
            position.set(520f, 280f)
            width = 58f
            height = 58f
            color = Color.parseColor("#EF5350")
            isSolid = true
            affectedByGravity = false
            scriptType = ScriptType.PYTHON
            scriptCode = """def on_update(entity, dt):
    entity.patrol(360, 720, 140)

def on_collision(entity, other):
    if other.tag == "مقذوف":
        entity.health -= 35
        audio.play("ضربة")
        other.destroy()
        if entity.health <= 0:
            entity.score += 200
            audio.play("انفجار")
            print("Enemy Drone Destroyed! +200")
            self.destroy()
"""
        }
        scene.addEntity(drone)

        val positions = listOf(420f to 160f, 620f to 390f, 840f to 240f)
        positions.forEachIndexed { idx, (cx, cy) ->
            val crystal = GameEntity(name = "بلورة_${idx + 1}", tag = "عنصر").apply {
                position.set(cx, cy)
                width = 44f
                height = 44f
                color = Color.parseColor("#FFD54F")
                isSolid = false
                scriptType = ScriptType.PYTHON
                scriptCode = """def on_collision(entity, other):
    if other.tag == "لاعب":
        other.add_item("بلورة_${idx + 1}")
        entity.score += 150
        audio.play("التقاط_الحجر")
        print("Crystal Collected! +150 Score")
        self.destroy()
"""
            }
            scene.addEntity(crystal)
        }

        scene.camera.targetEntity = ship
        return scene
    }

    /**
     * حفظ المشهد بالكامل كملف JSON في ذاكرة التطبيق الدائمة (مع تجاهل المقذوفات المؤقتة أثناء الحفظ)
     */
    fun saveSceneToJson(context: Context, scene: GameScene, fileName: String = "saved_mango_scene.json"): Boolean {
        return try {
            val root = JSONObject()
            root.put("sceneName", scene.name)
            root.put("backgroundColor", scene.backgroundColor)
            root.put("gravity", scene.gravity.toDouble())

            val entitiesArray = JSONArray()
            for (e in scene.entities) {
                if (e.isProjectile || e.tag == "مقذوف") continue
                val obj = JSONObject()
                obj.put("id", e.id)
                obj.put("name", e.name)
                obj.put("tag", e.tag)
                obj.put("x", e.position.x.toDouble())
                obj.put("y", e.position.y.toDouble())
                obj.put("width", e.width.toDouble())
                obj.put("height", e.height.toDouble())
                obj.put("color", e.color)
                obj.put("spriteName", e.spriteName)
                obj.put("isSolid", e.isSolid)
                obj.put("affectedByGravity", e.affectedByGravity)
                obj.put("bounce", e.bounce.toDouble())
                obj.put("health", e.health.toDouble())
                obj.put("scriptType", e.scriptType.name)
                obj.put("scriptCode", e.scriptCode)
                entitiesArray.put(obj)
            }
            root.put("entities", entitiesArray)

            val file = File(context.filesDir, fileName)
            file.writeText(root.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadSceneFromJson(context: Context, fileName: String = "saved_mango_scene.json"): GameScene? {
        return try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) return null

            val root = JSONObject(file.readText())
            val scene = GameScene(root.optString("sceneName", "مشروع_محفوظ"))
            scene.backgroundColor = root.optInt("backgroundColor", Color.parseColor("#141824"))
            scene.gravity = root.optDouble("gravity", 920.0).toFloat()

            val arr = root.optJSONArray("entities") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ent = GameEntity(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    name = obj.optString("name", "كائن_${i + 1}"),
                    tag = obj.optString("tag", "افتراضي")
                ).apply {
                    position.set(
                        obj.optDouble("x", 100.0).toFloat(),
                        obj.optDouble("y", 100.0).toFloat()
                    )
                    width = obj.optDouble("width", 64.0).toFloat()
                    height = obj.optDouble("height", 64.0).toFloat()
                    color = obj.optInt("color", Color.parseColor("#4CAF50"))
                    spriteName = obj.optString("spriteName", "")
                    isSolid = obj.optBoolean("isSolid", false)
                    affectedByGravity = obj.optBoolean("affectedByGravity", false)
                    bounce = obj.optDouble("bounce", 0.0).toFloat()
                    health = obj.optDouble("health", 100.0).toFloat()
                    val stName = obj.optString("scriptType", ScriptType.ARABIC_DSL.name)
                    scriptType = try { ScriptType.valueOf(stName) } catch (e: Exception) { ScriptType.ARABIC_DSL }
                    scriptCode = obj.optString("scriptCode", "")
                }
                scene.addEntity(ent)
            }

            scene.camera.targetEntity = scene.findEntityByName("اللاعب")
                ?: scene.findEntitiesByTag("بطل").firstOrNull()
                ?: scene.findEntitiesByTag("لاعب").firstOrNull()
                ?: scene.entities.firstOrNull()

            scene
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
