package com.mangodsl.engine

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * الواجهة الرئيسية المطورة لمحرك Mango DSL 2D على أندرويد
 * تدعم: تعدد اللمس الحقيقي، إطلاق المقذوفات بزر B، الأعداء المتجولين بدوريات ذكية،
 * حفظ واسترجاع المشاريع (JSON)، استيراد الصور والأصوات من الهاتف، والسحب والإفلات في المشهد.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_IMPORT_ASSET = 1001
    }

    private lateinit var gameView: MangoGameView
    private lateinit var arabicEngine: ArabicDslEngine
    private lateinit var pythonEngine: PythonDslEngine

    private var activeTab = 0
    private lateinit var contentContainer: FrameLayout
    private lateinit var gameRunnerLayout: FrameLayout
    private lateinit var sceneEditorLayout: ScrollView
    private lateinit var sceneEditorContent: LinearLayout
    private lateinit var codeEditorLayout: LinearLayout
    private lateinit var assetManagerLayout: ScrollView
    private lateinit var assetManagerContent: LinearLayout
    private lateinit var dictionaryLayout: LinearLayout

    private lateinit var navButtons: MutableList<TextView>
    private lateinit var statusBanner: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedEntity: GameEntity? = null
    private lateinit var codeEditText: EditText
    private lateinit var scriptTypeSpinner: Spinner
    private lateinit var codeEntityTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12131A"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        rootLayout.addView(createHeaderBar())
        rootLayout.addView(createNavigationTabs())

        statusBanner = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#1E202B"))
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
        }
        rootLayout.addView(statusBanner)

        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(contentContainer)

        gameView = MangoGameView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onEntitySelectedByTouch = { ent ->
                selectedEntity = ent
                showGameMessage("تم تحديد الكائن: ${ent.name} (يمكنك سحبه بالإصبع)")
            }
        }

        val initialScene = ProjectManager.loadSceneFromJson(this)
            ?: ProjectManager.createDefaultMagicStoneGame()
        loadSceneIntoEngine(initialScene)

        setupGameRunner()
        setupSceneEditor()
        setupCodeEditor()
        setupAssetManager()
        setupDictionary()

        switchTab(0)
        setContentView(rootLayout)
    }

    private fun loadSceneIntoEngine(scene: GameScene) {
        gameView.currentScene = scene
        selectedEntity = scene.findEntityByName("اللاعب") ?: scene.entities.firstOrNull { !it.isProjectile }

        ProjectManager.listAllAssets(this, gameView.audioManager)

        arabicEngine = ArabicDslEngine(scene, gameView.inputManager, gameView.audioManager) { msg ->
            showGameMessage(msg)
        }

        pythonEngine = PythonDslEngine(scene, gameView.inputManager, gameView.audioManager) { msg ->
            showGameMessage(msg)
        }

        gameView.onFrameUpdate = { dt ->
            for (entity in gameView.currentScene.entities) {
                if (!entity.isActive || entity.isProjectile) continue
                when (entity.scriptType) {
                    ScriptType.ARABIC_DSL -> arabicEngine.executeUpdate(entity, dt)
                    ScriptType.PYTHON -> pythonEngine.executeUpdate(entity, dt)
                    ScriptType.NONE -> {}
                }
            }
        }

        gameView.onCollisionEvent = { a, b ->
            if (a.scriptType == ScriptType.ARABIC_DSL) arabicEngine.executeCollision(a, b)
            if (b.scriptType == ScriptType.ARABIC_DSL) arabicEngine.executeCollision(b, a)
            if (a.scriptType == ScriptType.PYTHON) pythonEngine.executeCollision(a, b)
            if (b.scriptType == ScriptType.PYTHON) pythonEngine.executeCollision(b, a)
        }

        for (entity in scene.entities) {
            if (entity.scriptType == ScriptType.ARABIC_DSL) arabicEngine.executeStart(entity)
            if (entity.scriptType == ScriptType.PYTHON) pythonEngine.executeStart(entity)
        }
    }

    private fun showGameMessage(msg: String) {
        mainHandler.post {
            statusBanner.text = "📢 $msg"
            statusBanner.visibility = View.VISIBLE
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                statusBanner.visibility = View.GONE
            }, 3200)
        }
    }

    private fun createHeaderBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#181A22"))

            val titleText = TextView(context).apply {
                text = "🥭 Mango DSL Pro"
                textSize = 16.5f
                setTextColor(Color.parseColor("#FF7043"))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(titleText)

            val playBtn = createStyledButton("▶ لعب", "#00E676", "#000000") {
                gameView.isPaused = false
                showGameMessage("وضع اللعب الحي مفعّل")
            }
            val pauseBtn = createStyledButton("✋ تحرير", "#FFA726", "#000000") {
                gameView.isPaused = true
                showGameMessage("وضع التحرير: اسحب أي كائن على الشاشة لتحريكه")
            }
            val saveBtn = createStyledButton("💾 حفظ", "#29B6F6", "#000000") {
                val ok = ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
                if (ok) showGameMessage("تم حفظ المشروع بالكامل في ذاكرة الهاتف!")
            }
            val resetBtn = createStyledButton("⟲ إعادة", "#EF5350", "#FFFFFF") {
                loadSceneIntoEngine(ProjectManager.createDefaultMagicStoneGame())
                ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
                showGameMessage("تمت إعادة ضبط المرحلة الافتراضية")
            }

            addView(playBtn)
            addView(createSpacer(8))
            addView(pauseBtn)
            addView(createSpacer(8))
            addView(saveBtn)
            addView(createSpacer(8))
            addView(resetBtn)
        }
    }

    private fun createNavigationTabs(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#1E202B"))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 10, 12, 10)
        }

        val tabs = listOf(
            "🎮 المشغل الحي" to 0,
            "🎨 محرر المشهد" to 1,
            "📝 محرر الأكواد" to 2,
            "📁 مدير الأصول" to 3,
            "📖 قاموس الضاد" to 4
        )

        navButtons = mutableListOf()
        for ((title, index) in tabs) {
            val tabBtn = TextView(this).apply {
                text = title
                textSize = 13.5f
                setTextColor(if (index == 0) Color.WHITE else Color.parseColor("#94A3B8"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(26, 14, 26, 14)
                background = createRoundedDrawable(if (index == 0) "#FF7043" else "#272A38", 20f)
                setOnClickListener { switchTab(index) }
            }
            navButtons.add(tabBtn)
            row.addView(tabBtn)
            row.addView(createSpacer(10))
        }

        scroll.addView(row)
        return scroll
    }

    private fun switchTab(tabIndex: Int) {
        activeTab = tabIndex
        navButtons.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == tabIndex) Color.WHITE else Color.parseColor("#94A3B8"))
            tv.background = createRoundedDrawable(if (idx == tabIndex) "#FF7043" else "#272A38", 20f)
        }

        contentContainer.removeAllViews()
        when (tabIndex) {
            0 -> contentContainer.addView(gameRunnerLayout)
            1 -> {
                refreshSceneEditorUI()
                contentContainer.addView(sceneEditorLayout)
            }
            2 -> {
                refreshCodeEditorUI()
                contentContainer.addView(codeEditorLayout)
            }
            3 -> {
                refreshAssetManagerUI()
                contentContainer.addView(assetManagerLayout)
            }
            4 -> contentContainer.addView(dictionaryLayout)
        }
    }

    // ==========================================
    // 1. شاشة اللعب مع دعم تعدد اللمس (Multi-touch)
    // ==========================================
    private fun setupGameRunner() {
        gameRunnerLayout = FrameLayout(this)
        gameRunnerLayout.addView(gameView)

        val controlsOverlay = RelativeLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val dpadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                setMargins(28, 0, 0, 42)
            }
        }

        dpadLayout.addView(createVirtualButton("◀", "يسار"))
        dpadLayout.addView(createSpacer(12))
        dpadLayout.addView(createVirtualButton("▶", "يمين"))
        dpadLayout.addView(createSpacer(12))
        dpadLayout.addView(createVirtualButton("▲", "أعلى"))
        dpadLayout.addView(createSpacer(12))
        dpadLayout.addView(createVirtualButton("▼", "أسفل"))
        controlsOverlay.addView(dpadLayout)

        val actionButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                setMargins(0, 0, 28, 42)
            }
        }

        val jumpBtn = createVirtualButton("S\nقفز", "s", "#00E676")
        val actionABtn = createVirtualButton("A\nتفاعل", "a", "#FFA726")
        val actionBBtn = createVirtualButton("B\nإطلاق", "b", "#00E5FF")

        actionButtonsLayout.addView(actionBBtn)
        actionButtonsLayout.addView(createSpacer(12))
        actionButtonsLayout.addView(actionABtn)
        actionButtonsLayout.addView(createSpacer(12))
        actionButtonsLayout.addView(jumpBtn)

        controlsOverlay.addView(actionButtonsLayout)
        gameRunnerLayout.addView(controlsOverlay)
    }

    private fun createVirtualButton(label: String, key: String, accentColorHex: String = "#FF7043"): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = ViewGroup.LayoutParams(126, 126)
            background = createRoundedDrawable(accentColorHex, 63f, 185)

            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        gameView.inputManager.setButtonPressed(key, true)
                        alpha = 0.55f
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        gameView.inputManager.setButtonPressed(key, false)
                        alpha = 1.0f
                        true
                    }
                    else -> true
                }
            }
        }
    }

    // ==========================================
    // 2. محرر المشهد وحفظ/تبديل القوالب (Scene Editor)
    // ==========================================
    private fun setupSceneEditor() {
        sceneEditorContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        sceneEditorLayout = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161E"))
            addView(sceneEditorContent)
        }
    }

    private fun refreshSceneEditorUI() {
        sceneEditorContent.removeAllViews()

        val title = TextView(this).apply {
            text = "🎨 محرر المشهد وإدارة المشاريع"
            textSize = 18.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        sceneEditorContent.addView(title)
        sceneEditorContent.addView(createSpacer(14))

        val templatesBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val arabicDemoBtn = createStyledButton("🕌 مغامرة الحجر والحراس (عربي)", "#37474F", "#00E676") {
            loadSceneIntoEngine(ProjectManager.createDefaultMagicStoneGame())
            ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
            refreshSceneEditorUI()
            showGameMessage("تم تحميل قالب مغامرة الحجر السحري وحراس القلعة")
        }
        val pythonDemoBtn = createStyledButton("🐍 معركة الفضاء (Python)", "#37474F", "#29B6F6") {
            loadSceneIntoEngine(ProjectManager.createPythonDemoGame())
            ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
            refreshSceneEditorUI()
            showGameMessage("تم تحميل قالب معركة الفضاء (بايثون)")
        }
        templatesBar.addView(arabicDemoBtn)
        templatesBar.addView(createSpacer(10))
        templatesBar.addView(pythonDemoBtn)
        sceneEditorContent.addView(templatesBar)
        sceneEditorContent.addView(createSpacer(18))

        val editableEntities = gameView.currentScene.entities.filter { !it.isProjectile }

        val entityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val entitySpinner = Spinner(this).apply {
            val names = editableEntities.map { it.name }
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            val currentIdx = editableEntities.indexOf(selectedEntity)
            if (currentIdx >= 0) setSelection(currentIdx)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val newlySelected = editableEntities.getOrNull(pos)
                    if (newlySelected != null && newlySelected != selectedEntity) {
                        selectedEntity = newlySelected
                        refreshSceneEditorUI()
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val addBtn = createStyledButton("＋ عدو حارس", "#00E676", "#000000") {
            val newEnt = GameEntity(name = "حارس_${editableEntities.size + 1}", tag = "عدو").apply {
                position.set(480f, 458f)
                width = 56f
                height = 62f
                color = Color.parseColor("#EF5350")
                isSolid = true
                affectedByGravity = true
                scriptType = ScriptType.ARABIC_DSL
                scriptCode = """في_كل_إطار {
    تحرك_في_دورية بين 400 و 620 بسرعة 120
}

عند_التصادم مع مقذوف {
    أنقص الصحة بمقدار 35
    دمر_الكائن الآخر
    إذا الصحة <= 0 {
        زد النقاط بمقدار 150
        شغل صوت "انفجار"
        دمر_الكائن هذا_الكائن
    }
}"""
            }
            gameView.currentScene.addEntity(newEnt)
            selectedEntity = newEnt
            refreshSceneEditorUI()
            showGameMessage("تمت إضافة عدو بدورية ذكية: ${newEnt.name}")
        }

        val deleteBtn = createStyledButton("🗑 حذف", "#EF5350", "#FFFFFF") {
            selectedEntity?.let { ent ->
                if (editableEntities.size > 1) {
                    gameView.currentScene.removeEntity(ent)
                    selectedEntity = gameView.currentScene.entities.firstOrNull { !it.isProjectile }
                    refreshSceneEditorUI()
                    showGameMessage("تم حذف الكائن: ${ent.name}")
                } else {
                    showGameMessage("لا يمكن حذف الكائن الوحيد في المشهد")
                }
            }
        }

        entityRow.addView(entitySpinner)
        entityRow.addView(createSpacer(8))
        entityRow.addView(addBtn)
        entityRow.addView(createSpacer(8))
        entityRow.addView(deleteBtn)
        sceneEditorContent.addView(entityRow)
        sceneEditorContent.addView(createSpacer(18))

        selectedEntity?.let { ent ->
            val detailsCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = createRoundedDrawable("#1E202B", 16f)
            }

            val nameInput = createEditableField(detailsCard, "اسم الكائن:", ent.name)
            val tagInput = createEditableField(detailsCard, "الوسم (Tag):", ent.tag)
            val xInput = createEditableField(detailsCard, "الموضع س (X):", ent.position.x.toInt().toString())
            val yInput = createEditableField(detailsCard, "الموضع ص (Y):", ent.position.y.toInt().toString())
            val wInput = createEditableField(detailsCard, "العرض:", ent.width.toInt().toString())
            val hInput = createEditableField(detailsCard, "الارتفاع:", ent.height.toInt().toString())
            val spriteInput = createEditableField(detailsCard, "اسم الصورة (Sprite):", ent.spriteName)

            val solidCheck = CheckBox(this).apply {
                text = "جسم صلب يتصادم فيزيائياً (Solid Collider)"
                setTextColor(Color.WHITE)
                isChecked = ent.isSolid
                setOnCheckedChangeListener { _, checked -> ent.isSolid = checked }
            }
            detailsCard.addView(solidCheck)

            val gravCheck = CheckBox(this).apply {
                text = "يتأثر بالجاذبية الأرضية (Gravity)"
                setTextColor(Color.WHITE)
                isChecked = ent.affectedByGravity
                setOnCheckedChangeListener { _, checked -> ent.affectedByGravity = checked }
            }
            detailsCard.addView(gravCheck)

            detailsCard.addView(createSpacer(12))
            val applyPropsBtn = createStyledButton("✅ تطبيق التعديلات وحفظ المشهد", "#FF7043", "#FFFFFF") {
                ent.name = nameInput.text.toString().trim().ifEmpty { ent.name }
                ent.tag = tagInput.text.toString().trim().ifEmpty { ent.tag }
                xInput.text.toString().toFloatOrNull()?.let { ent.position.x = it }
                yInput.text.toString().toFloatOrNull()?.let { ent.position.y = it }
                wInput.text.toString().toFloatOrNull()?.let { ent.width = it.coerceAtLeast(10f) }
                hInput.text.toString().toFloatOrNull()?.let { ent.height = it.coerceAtLeast(10f) }
                ent.spriteName = spriteInput.text.toString().trim()
                ent.spriteBitmap = null
                ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
                showGameMessage("تم تحديث خصائص (${ent.name}) وحفظ المشهد!")
            }
            detailsCard.addView(applyPropsBtn)

            sceneEditorContent.addView(detailsCard)
        }
    }

    private fun createEditableField(parent: LinearLayout, label: String, initVal: String): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 6)
        }
        val lbl = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(250, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val input = EditText(this).apply {
            setText(initVal)
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#272A38"))
            setPadding(16, 10, 16, 10)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(lbl)
        row.addView(input)
        parent.addView(row)
        return input
    }

    // ==========================================
    // 3. استوديو الأكواد (Code Studio)
    // ==========================================
    private fun setupCodeEditor() {
        codeEditorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#14161E"))
        }

        codeEntityTitle = TextView(this).apply {
            text = "📝 كود الكائن المحدد"
            textSize = 15.5f
            setTextColor(Color.parseColor("#FFD54F"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        codeEditorLayout.addView(codeEntityTitle)

        val snippetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, 14)
        }
        val snippetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val snippets = listOf(
            "إطلاق مقذوف" to "إذا ضغط اللاعب \"b\" {\n    أطلق_مقذوف بسرعة 580 بقوة 35\n}",
            "دورية حراسة" to "تحرك_في_دورية بين 430 و 600 بسرعة 115",
            "إذا ضغط \"s\"" to "إذا ضغط اللاعب \"s\" {\n    اقفز بقوة 455\n}",
            "شرط النقاط >=" to "إذا النقاط >= 100 {\n    افتح الباب_الحجري\n} وإلا {\n    أظهر رسالة \"اجمع 100 نقطة أولاً!\"\n}",
            "زد النقاط" to "زد النقاط بمقدار 50",
            "أنقص الصحة" to "أنقص الصحة بمقدار 35",
            "اهتزاز الكاميرا" to "اهتزاز_الكاميرا 15",
            "دمر الكائن" to "دمر_الكائن هذا_الكائن"
        )

        for ((label, code) in snippets) {
            val btn = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTextColor(Color.parseColor("#00E676"))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(20, 12, 20, 12)
                background = createRoundedDrawable("#1E202B", 12f)
                setOnClickListener { insertCodeSnippet(code) }
            }
            snippetRow.addView(btn)
            snippetRow.addView(createSpacer(10))
        }
        snippetScroll.addView(snippetRow)
        codeEditorLayout.addView(snippetScroll)

        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 14)
        }

        val langLabel = TextView(this).apply {
            text = "اللغة:"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 14, 0)
        }
        langRow.addView(langLabel)

        scriptTypeSpinner = Spinner(this).apply {
            val options = listOf("لغة الضاد (Arabic DSL)", "بايثون (Python)")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    selectedEntity?.scriptType = if (pos == 0) ScriptType.ARABIC_DSL else ScriptType.PYTHON
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        langRow.addView(scriptTypeSpinner)

        val saveCodeBtn = createStyledButton("💾 ترجمة وحفظ الكود", "#FF7043", "#FFFFFF") {
            selectedEntity?.let { ent ->
                ent.scriptCode = codeEditText.text.toString()
                if (ent.scriptType == ScriptType.ARABIC_DSL) arabicEngine.executeStart(ent)
                if (ent.scriptType == ScriptType.PYTHON) pythonEngine.executeStart(ent)
                ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
                showGameMessage("تم تطبيق الكود وحفظ المشروع للكائن: ${ent.name}")
            }
        }
        langRow.addView(createSpacer(14))
        langRow.addView(saveCodeBtn)
        codeEditorLayout.addView(langRow)

        codeEditText = EditText(this).apply {
            setTextColor(Color.parseColor("#E0E6ED"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.parseColor("#181A22"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        codeEditorLayout.addView(codeEditText)
    }

    private fun refreshCodeEditorUI() {
        selectedEntity?.let { ent ->
            codeEntityTitle.text = "📝 تحرير سكريبت الكائن: ${ent.name} (${ent.tag})"
            codeEditText.setText(ent.scriptCode)
            scriptTypeSpinner.setSelection(if (ent.scriptType == ScriptType.ARABIC_DSL) 0 else 1)
        }
    }

    private fun insertCodeSnippet(code: String) {
        val start = maxOf(codeEditText.selectionStart, 0)
        val end = maxOf(codeEditText.selectionEnd, 0)
        codeEditText.text.replace(minOf(start, end), maxOf(start, end), code, 0, code.length)
    }

    // ==========================================
    // 4. مدير الأصول الحقيقي (Real Asset Importer)
    // ==========================================
    private fun setupAssetManager() {
        assetManagerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        assetManagerLayout = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#14161E"))
            addView(assetManagerContent)
        }
    }

    private fun refreshAssetManagerUI() {
        assetManagerContent.removeAllViews()

        val title = TextView(this).apply {
            text = "📁 مدير الأصول الحقيقي (استيراد صور وأصوات)"
            textSize = 18.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        assetManagerContent.addView(title)
        assetManagerContent.addView(createSpacer(10))

        val desc = TextView(this).apply {
            text = "استورد صور الشخصيات (PNG/JPG) أو ملفات الصوت (WAV/OGG/MP3) من هاتفك، أو اضغط على أي صورة لتعيينها للكائن المحدد (${selectedEntity?.name ?: "اللاعب"}):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13.5f
        }
        assetManagerContent.addView(desc)
        assetManagerContent.addView(createSpacer(16))

        val importRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val importImgBtn = createStyledButton("🖼 استيراد صورة Sprite من الهاتف", "#00E676", "#000000") {
            openSystemFilePicker("image/*")
        }
        val importAudioBtn = createStyledButton("🔊 استيراد ملف صوتي", "#FFA726", "#000000") {
            openSystemFilePicker("audio/*")
        }
        importRow.addView(importImgBtn)
        importRow.addView(createSpacer(12))
        importRow.addView(importAudioBtn)
        assetManagerContent.addView(importRow)
        assetManagerContent.addView(createSpacer(20))

        val assets = ProjectManager.listAllAssets(this, gameView.audioManager)
        for (asset in assets) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(22, 16, 22, 16)
                background = createRoundedDrawable("#1E202B", 14f)

                val icon = if (asset.type == "audio") "🔊" else "🖼"
                val nameTv = TextView(context).apply {
                    text = "$icon ${asset.name}\n${asset.description}"
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 13.5f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(nameTv)

                if (asset.type == "audio") {
                    val playSoundBtn = createStyledButton("▶ تجربة الصوت", "#29B6F6", "#000000") {
                        gameView.audioManager.playSound(asset.name)
                        showGameMessage("تشغيل المؤثر الصوتي: ${asset.name}")
                    }
                    addView(playSoundBtn)
                } else {
                    val assignBtn = createStyledButton("تعيين للكائن المحدد", "#00E676", "#000000") {
                        selectedEntity?.let { ent ->
                            ent.spriteName = asset.name
                            ent.spriteBitmap = ProjectManager.getLoadedBitmap(this@MainActivity, asset.name)
                            ProjectManager.saveSceneToJson(this@MainActivity, gameView.currentScene)
                            showGameMessage("تم تعيين الصورة ${asset.name} للكائن ${ent.name}")
                        }
                    }
                    addView(assignBtn)
                }
            }
            assetManagerContent.addView(itemCard)
            assetManagerContent.addView(createSpacer(12))
        }
    }

    private fun openSystemFilePicker(mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
            }
            startActivityForResult(intent, REQ_IMPORT_ASSET)
        } catch (e: Exception) {
            showGameMessage("تعذر فتح مستعرض الملفات: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_ASSET && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val imported = ProjectManager.importAssetFromUri(this, uri, gameView.audioManager)
            if (imported != null) {
                refreshAssetManagerUI()
                showGameMessage("تم استيراد الأصل بنجاح: ${imported.name}")
            } else {
                showGameMessage("تعذر قراءة الملف المختار")
            }
        }
    }

    // ==========================================
    // 5. قاموس لغة الضاد (Dictionary)
    // ==========================================
    private fun setupDictionary() {
        dictionaryLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#14161E"))
        }

        val title = TextView(this).apply {
            text = "📖 قاموس ومرجع لغة البرمجة العربية المطور"
            textSize = 18.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        dictionaryLayout.addView(title)
        dictionaryLayout.addView(createSpacer(14))

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (entry in ArabicDictionary.entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = createRoundedDrawable("#1E202B", 16f)

                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val keywordTv = TextView(context).apply {
                        text = entry.keyword
                        textSize = 15.5f
                        setTextColor(Color.parseColor("#00E676"))
                        typeface = Typeface.DEFAULT_BOLD
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val categoryBadge = TextView(context).apply {
                        text = entry.category
                        textSize = 11.5f
                        setTextColor(Color.parseColor("#FFA726"))
                        setPadding(16, 6, 16, 6)
                        background = createRoundedDrawable("#2E2B25", 10f)
                    }
                    addView(keywordTv)
                    addView(categoryBadge)
                }
                addView(header)
                addView(createSpacer(10))

                val descTv = TextView(context).apply {
                    text = entry.description
                    textSize = 13.5f
                    setTextColor(Color.parseColor("#DDE5F0"))
                }
                addView(descTv)
                addView(createSpacer(10))

                val syntaxTv = TextView(context).apply {
                    text = "الصيغة: " + entry.syntax
                    textSize = 13f
                    setTextColor(Color.parseColor("#80D8FF"))
                    typeface = Typeface.MONOSPACE
                    setPadding(16, 10, 16, 10)
                    background = createRoundedDrawable("#181A22", 10f)
                }
                addView(syntaxTv)
                addView(createSpacer(10))

                val copyBtn = createStyledButton("📋 إدراج هذا المثال في محرر الكود", "#37474F", "#FFFFFF") {
                    switchTab(2)
                    insertCodeSnippet("\n" + entry.example)
                    showGameMessage("تم إدراج الكود في المحرر!")
                }
                addView(copyBtn)
            }

            itemsContainer.addView(card)
            itemsContainer.addView(createSpacer(14))
        }

        scrollView.addView(itemsContainer)
        dictionaryLayout.addView(scrollView)
    }

    private fun createStyledButton(label: String, bgHex: String, textHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(textHex))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(20, 12, 20, 12)
            background = createRoundedDrawable(bgHex, 14f)
            setOnClickListener { onClick() }
        }
    }

    private fun createRoundedDrawable(colorHex: String, radius: Float, alphaVal: Int = 255): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.parseColor(colorHex))
            alpha = alphaVal
        }
    }

    private fun createSpacer(sizePx: Int): View {
        return View(this).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
        }
    }

    override fun onPause() {
        super.onPause()
        ProjectManager.saveSceneToJson(this, gameView.currentScene)
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView.stop()
        gameView.audioManager.release()
    }
}