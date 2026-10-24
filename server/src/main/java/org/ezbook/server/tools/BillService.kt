/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.ezbook.server.tools

import androidx.room.withTransaction
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ezbook.server.Server
import org.ezbook.server.ai.tools.BillTool
import org.ezbook.server.ai.tools.CategoryTool
import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.constant.DataType
import org.ezbook.server.db.AppDatabase
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.AppDataModel
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.db.model.CategoryModel
import org.ezbook.server.db.model.CurrencyModel
import org.ezbook.server.engine.JsExecutor
import org.ezbook.server.engine.RuleGenerator
import org.ezbook.server.intent.BillInfoIntent
import org.ezbook.server.models.BillResultModel
import org.ezbook.server.models.ResultModel
import org.ezbook.server.server.AnalysisParams
import org.ezbook.server.server.resolveBookByNameOrDefault
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ezbook.server.log.ServerLog

internal suspend fun resolveMappedCategory(
    bill: BillInfoModel,
    categories: List<CategoryModel>,
    processor: CategoryProcessor = CategoryProcessor()
): String? = processor.setCategoryMap(bill, categories)

/**
 * 账单核心业务服务类
 *
 * 该类封装了账单分析、处理和管理的核心业务逻辑，包括：
 * - 基于规则引擎的账单分析
 * - 基于AI的账单分析
 * - 账单分类和处理
 * - JavaScript代码执行
 *
 * 所有与数据库、AI、JS引擎交互的细节都被隐藏在这里，
 * 使得Route层能够保持轻量和简洁。
 *
 * @param db 数据库实例，用于账单数据的存储和查询
 * @param ruleGenerator 规则生成器实例，用于生成JavaScript规则代码
 * @param jsExecutor JavaScript执行器实例，用于执行规则代码
 */
class BillService(
    private val db: AppDatabase = Db.get(),
    private val ruleGenerator: RuleGenerator = RuleGenerator,
    private val jsExecutor: JsExecutor = JsExecutor()
) : Closeable {

    /**
     * 启动自动记账面板
     * @param billInfoModel 账单信息模型
     * @param parent 父账单信息
     */
    private suspend fun startAutoPanel(
        billInfoModel: BillInfoModel,
        parent: BillInfoModel?,
    ) {
        val dnd = SettingUtils.landscapeDnd()
        val isLandscape = isLandscapeMode()
        // 检查横屏状态并处理
        if (isLandscape && dnd) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    Server.application,
                    "账单金额：${billInfoModel.money}，横屏状态下为您自动暂存。",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ServerLog.d("Landscape DND: auto-stashed, money=${billInfoModel.money}, app=${billInfoModel.app}")
            return
        }

        // 创建并启动悬浮窗（改为入队串行处理）
        launchFloatingWindow(billInfoModel, parent)
    }

    /**
     * 检查当前设备是否处于横屏模式
     * @return Boolean 如果是横屏返回true，否则返回false
     */
    private fun isLandscapeMode(): Boolean =
        Server.application.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE


    /**
     * 启动悬浮窗口来显示账单信息
     * @param billInfoModel 要显示的账单信息模型
     * @param parent 父账单信息，可能为null，用于关联相关账单
     * @throws SecurityException 如果应用没有必要的权限
     */
    private suspend fun launchFloatingWindow(billInfoModel: BillInfoModel, parent: BillInfoModel?) {
        val intent = BillInfoIntent(billInfoModel, "JsRoute", parent)
        // 入队，由全局主线程消费者串行拉起，确保队列式显示
        floatingIntentChannel.send(intent)
    }

    /**
     * 分析账单数据的主要入口方法
     *
     * 根据参数配置，可以选择使用规则引擎或AI进行账单分析。
     * 如果规则分析失败，会自动回退到AI分析。
     * 分析完成后会进行分类处理和后续业务逻辑处理。
     *
     * @param analysisParams 分析参数，包含应用名、数据类型、是否使用AI等配置
     * @param context Android上下文，用于访问系统资源
     * @return 分析结果，包含账单信息、父级账单和是否需要用户操作的标识
     */
    suspend fun analyze(analysisParams: AnalysisParams, context: Context): ResultModel<BillResultModel> =
        analysisMutex.withLock { analyzeInternal(analysisParams, context) }

    private suspend fun analyzeInternal(
        analysisParams: AnalysisParams,
        context: Context
    ): ResultModel<BillResultModel> =
        withContext(Dispatchers.IO) {
            val dataType = runCatchingExceptCancel { DataType.valueOf(analysisParams.type) }
                .getOrElse {
                    ServerLog.w("Invalid data type: ${analysisParams.type}")
                    return@withContext ResultModel<BillResultModel>(
                        400,
                        "Type exception: ${analysisParams.type}",
                        null
                    )
                }

            // Durable source identity: successful retries return the committed result, including after restart.
            val eventId = runCatching { Gson().fromJson(analysisParams.data, JsonObject::class.java)
                ?.get("eventId")?.asString }.getOrNull().orEmpty()
            val sourceKey = if (analysisParams.fromAppData || dataType == DataType.OCR) "" else
                MD5HashTable.md5("${analysisParams.app}|${analysisParams.type}|${eventId.ifBlank { analysisParams.data }}")
            if (sourceKey.isNotEmpty()) {
                db.billInfoDao().bySource(sourceKey)?.let { existing ->
                    val parent = if (existing.groupId != -1L) db.billInfoDao().queryId(existing.groupId) else null
                    return@withContext ResultModel.ok(BillResultModel(existing, parent))
                }
            }
            // 3) 如有需要，先持久化原始数据
            val appDataModel: AppDataModel? = if (!analysisParams.fromAppData) {
                AppDataModel().apply {
                    data = analysisParams.data
                    app = analysisParams.app
                    type = dataType
                    time = System.currentTimeMillis()
                    image = analysisParams.image  // 必须在 insert 前设置，否则入库时 image 为空
                    id = Db.get().dataDao().insert(this)
                    ServerLog.d("AppData saved: id=$id, app=$app, type=$type")
                }
            } else null

            // AI rejection is different from missing configuration or a failed request.
            val start = System.currentTimeMillis()
            val (aiBill, aiError, nonTransaction) = analyzeWithAI(analysisParams.app, analysisParams.data, dataType,
                analysisParams.image, analysisParams.manual, analysisParams.pageContext)
            if (nonTransaction) return@withContext ResultModel<BillResultModel>(404, OcrPagePolicy.MESSAGE, null)
            if (aiBill == null && dataType == DataType.OCR && OcrPagePolicy.isNonTransaction(analysisParams.app, analysisParams.data))
                return@withContext ResultModel<BillResultModel>(404, OcrPagePolicy.MESSAGE, null)
            val ruleMatchResult = if (aiBill == null) analyzeWithRule(analysisParams.app, analysisParams.data, dataType)
                else RuleMatchResult(null, matchedDisabled = false)
            if (ruleMatchResult.matchedDisabled) {
                // 命中禁用规则：直接丢弃，避免触发 AI 与后续账单流程
                appDataModel?.let { model ->
                    model.match = true
                    model.rule = ruleMatchResult.ruleName
                    model.version = SettingUtils.ruleVersion()
                    Db.get().dataDao().update(model)
                    ServerLog.d("Disabled rule matched, discarded: id=${model.id}, rule=${model.rule}")
                }
                return@withContext ResultModel<BillResultModel>(
                    404,
                    "命中禁用规则，已忽略。",
                    null
                )
            }
            val localOcr = if (aiBill == null && dataType == DataType.OCR && ruleMatchResult.billInfo == null)
                OcrBillParser.parse(analysisParams.app, analysisParams.data)?.let {
                    parseBillInfo(Gson().toJson(it), analysisParams.app, dataType)?.apply {
                        ruleName = "屏幕·本地订单识别"
                    }
                } else null
            val billInfo: BillInfoModel = aiBill ?: ruleMatchResult.billInfo ?: localOcr ?: run {
                ServerLog.w("No valid bill from rule or AI")
                return@withContext ResultModel<BillResultModel>(
                    404,
                    aiError ?: "未分析到有效账单。",
                    null
                )
            }
            LedgerPolicy.minorUnits(billInfo.money)
            billInfo.sourceKey = sourceKey
            if (billInfo.generateByAi()) billInfo.reviewReason = "AI 识别结果，请核对金额和交易类型后保存"
            if (dataType == DataType.OCR) {
                billInfo.reviewReason = "已记过这笔？选择补充已有账单；新交易请核对后保存"
                if (billInfo.generateByAi()) billInfo.reviewReason = "AI 识别待核对；可补充已有账单或确认新增"
            }
            billInfo.type = BillType.valueOf(CapturePolicy.refundType(billInfo.type.name, analysisParams.data))
            if (billInfo.type == BillType.IncomeRefund && !CapturePolicy.refundCompleted(analysisParams.data, billInfo.eventStatus)) {
                return@withContext ResultModel(404, "退款尚未完成，已保留原始信息，等待到账通知", null)
            }
            // Existing rules may omit provider IDs; do not invent identity from amount/time.
            val raw = runCatching { Gson().fromJson(analysisParams.data, JsonObject::class.java) }.getOrNull()
            if (billInfo.transactionId.isBlank()) billInfo.transactionId = raw?.safeGetString("transactionId").orEmpty()
            if (billInfo.refundId.isBlank()) billInfo.refundId = raw?.safeGetString("refundId").orEmpty()
            if (billInfo.originalTransactionId.isBlank()) billInfo.originalTransactionId = raw?.safeGetString("originalTransactionId").orEmpty()
            if (billInfo.transactionNamespace.isBlank()) billInfo.transactionNamespace = raw?.safeGetString("transactionNamespace").orEmpty().ifBlank { analysisParams.app }
            ReceiptIdentity.attach(billInfo, analysisParams.data)
            ServerLog.d("Parsed bill: type=${billInfo.type}, money=${billInfo.money}, shop=${billInfo.shopName}")
            //这里也不加bookName, bookName在分类里面处理
            if (appDataModel != null) {
                appDataModel.version = SettingUtils.ruleVersion()
                Db.get().dataDao().update(appDataModel)
            }
            // 保存映射前的原始账户名，供编辑器"记住资产映射"使用
            billInfo.rawAccountNameFrom = billInfo.accountNameFrom
            billInfo.rawAccountNameTo = billInfo.accountNameTo
            // 设置资产映射
            AssetsMap().setAssetsMap(billInfo)
            // 先根据已有的信息进行分类
            categorize(billInfo, dataType)
            // Decide after rule/AI parsing but BEFORE creating any bill, notice or editor event.
            if (analysisParams.type == "OCR" && !analysisParams.fromAppData) {
                val dao = db.billInfoDao()
                val candidates = dao.ocrRecognitionCandidates(billInfo.money.roundAmount(), billInfo.bookName,
                    billInfo.app, billInfo.transactionNamespace, ReceiptIdentity.normalize(billInfo.transactionId), ReceiptIdentity.normalize(billInfo.refundId))
                    .toMutableList()
                ReceiptIdentity.keys(billInfo).values.distinct().forEach { id ->
                    candidates.addAll(dao.ocrIdentityCandidates(billInfo.bookName, id))
                }
                val matches = candidates.distinctBy { it.id }
                    .filter { OcrRepeatPolicy.samePayment(billInfo, it, analysisParams.data) }
                    .mapNotNull { if (it.groupId == -1L) it else dao.queryId(it.groupId) }
                    .distinctBy { it.id }
                if (matches.size == 1) {
                    var changed = false
                    val existing = db.withTransaction {
                        val target = requireNotNull(dao.queryId(matches.single().id))
                        changed = EnrichmentPolicy.enrichRecognized(billInfo, target)
                        changed = ReceiptIdentity.remember(target, billInfo) || changed
                        if (changed) dao.update(target)
                        target
                    }
                    if (changed) BillExportSignal.notifyCommitted()
                    appDataModel?.let {
                        it.match = true
                        it.rule = billInfo.ruleName
                        Db.get().dataDao().update(it)
                    }
                    ServerLog.d("Automatic OCR silently skipped an already recognized payment")
                    return@withContext ResultModel.ok(BillResultModel(existing, silent = !analysisParams.manual, existing = true))
                }
            }
            if (billInfo.remark.isEmpty()) {
                billInfo.remark = BillManager.getRemark(billInfo, context)
            }
            // 🔒 关键区间：账单入库+去重+分类+保存+拉起悬浮窗全流程串行执行
            // 防止并发竞态：确保账单处理的完整生命周期严格按序执行，避免悬浮窗乱序
            val autoRecord = SettingUtils.getBoolean(org.ezbook.server.constant.Setting.AUTO_RECORD_BILL,
                org.ezbook.server.constant.DefaultData.AUTO_RECORD_BILL)
            val parent = db.withTransaction {
                if (!analysisParams.fromAppData) {
                    billInfo.money = billInfo.money.roundAmount()
                    billInfo.fee = billInfo.fee.roundAmount()
                    billInfo.id = db.billInfoDao().insert(billInfo)
                }
                val parentBill = if (analysisParams.fromAppData) null else BillManager.groupBillInfo(billInfo)
                val finalBill = parentBill ?: billInfo
                // No AI or network calls in the transaction. Classification was resolved above.
                if (parentBill != null && parentBill.cateName.isBlank()) parentBill.cateName = billInfo.cateName
                RefundManager.resolve(finalBill)
                if (finalBill.reviewReason.isNotBlank()) finalBill.setFlag(BillInfoModel.FLAG_NOT_COUNT, true)
                finalBill.money = finalBill.money.roundAmount()
                finalBill.fee = finalBill.fee.roundAmount()
                val saveAutomatically = !analysisParams.fromAppData && parentBill == null &&
                    finalBill.reviewReason.isBlank() && (autoRecord || finalBill.auto) &&
                    !finalBill.hasFlag(BillInfoModel.FLAG_NOT_COUNT)
                if (saveAutomatically) {
                    finalBill.state = BillState.Edited
                    finalBill.auto = true
                }
                if (!analysisParams.fromAppData) db.billInfoDao().update(finalBill)
                if (saveAutomatically) {
                    db.billNoticeDao().insert(org.ezbook.server.db.model.BillNoticeModel(
                        billId = finalBill.id, billJson = Gson().toJson(finalBill), createdAt = System.currentTimeMillis()))
                }
                if (parentBill != null) {
                    if (parentBill.state != BillState.Wait2Edit) {
                        val refreshed = db.billNoticeDao().refreshPending(parentBill.id, Gson().toJson(parentBill))
                        val changed = billInfo.mergeSnapshot.isNotBlank() &&
                            billInfo.mergeSnapshot != Gson().toJson(parentBill.copy(mergeSnapshot = "", ownSnapshot = ""))
                        if (refreshed == 0 && (parentBill.type == BillType.Transfer || changed)) {
                            db.billNoticeDao().insert(org.ezbook.server.db.model.BillNoticeModel(
                                billId = parentBill.id, billJson = Gson().toJson(parentBill),
                                createdAt = System.currentTimeMillis(), kind = "UPDATED"))
                        }
                    }
                    billInfo.groupId = parentBill.id
                    billInfo.state = BillState.Edited
                    billInfo.mergedParentSnapshot = Gson().toJson(parentBill)
                    db.billInfoDao().update(billInfo)
                }
                parentBill
            }
            // Side effects only after a successful commit.
            if (!analysisParams.fromAppData) startAutoPanel(billInfo, parent)
            BillExportSignal.notifyCommitted()

            val cost = System.currentTimeMillis() - start
            ServerLog.d("Analysis done: ${cost}ms")

            appDataModel?.let {
                it.match = true
                it.rule = billInfo.ruleName
                it.version = ""
                Db.get().dataDao().update(it)
            }
            // 10) 返回
            ResultModel.ok(BillResultModel(billInfo, parent))
        }

    /**
     * 执行JavaScript代码
     *
     * @param code 要执行的JavaScript代码
     * @param data 传递给JavaScript的数据参数，默认为空字符串
     * @return JavaScript执行结果
     */
    suspend fun executeJs(code: String, data: String = ""): String = jsExecutor.run(code, data)

    // region --- 私有辅助方法 ---

    /**
     * 使用指定来源（系统/用户）的规则进行一次解析尝试
     * @param app 应用名称
     * @param data 原始数据
     * @param dataType 数据类型
     * @param creator 规则来源（system/user）
     * @return 解析成功返回账单，失败返回 null
     */
    private suspend fun analyzeWithCreator(
        app: String,
        data: String,
        dataType: DataType,
        creator: String,
        scope: RuleGenerator.RuleScope
    ): BillInfoModel? {
        val src = if ("system" == creator) "system" else "user"
        val js = ruleGenerator.data(app, dataType, creator, scope)
        if (js.isBlank()) {
            ServerLog.d("$src rule empty, skip")
            return null
        }
        var result = executeJs(js, data)
        var billInfo = parseBillInfo(result, app, dataType);
        if (billInfo == null && creator == "user") {
            result = executeJs(js, DataConvert.convert(data))
            billInfo = parseBillInfo(result, app, dataType);
        }

        return billInfo?.also {
            ServerLog.d("$src rule matched: type=${it.type}, money=${it.money}")
        }
    }

    /**
     * 使用规则引擎分析账单数据
     *
     * 通过规则生成器获取对应应用和数据类型的JavaScript规则代码，
     * 然后执行该代码来分析账单数据。
     *
     * @param app 应用名称
     * @param data 要分析的原始数据
     * @param dataType 数据类型（如短信、通知等）
     * @return 分析得到的账单信息，如果分析失败则返回null
     */
    private suspend fun analyzeWithRule(
        app: String,
        data: String,
        dataType: DataType
    ): RuleMatchResult {
        ServerLog.d("Rule analysis: data=${data.take(100)}...")
        //为了避免部分用户的错误规则影响自动记账整体规则的可用性，拆分成2部分处理
        // 优先使用用户规则，随后使用系统规则兜底
        for (creator in arrayOf("user", "system")) {
            analyzeWithCreator(app, data, dataType, creator, RuleGenerator.RuleScope.Enabled)
                ?.let { return RuleMatchResult(it, matchedDisabled = false) }
        }
        // 若开启“禁用规则参与匹配”，尝试禁用规则命中以规避 AI
        if (SettingUtils.ruleMatchIncludeDisabled()) {
            for (creator in arrayOf("user", "system")) {
                analyzeWithCreator(app, data, dataType, creator, RuleGenerator.RuleScope.Disabled)
                    ?.let { return RuleMatchResult(it, matchedDisabled = true) }
            }
        }
        ServerLog.d("No rule match from system or user")
        return RuleMatchResult(null, matchedDisabled = false)
    }

    /**
     * 规则匹配结果封装。
     * @param billInfo 匹配到的账单信息
     * @param matchedDisabled 是否命中禁用规则
     */
    private data class RuleMatchResult(
        val billInfo: BillInfoModel?,
        val matchedDisabled: Boolean
    ) {
        // 便捷输出规则名称，避免外层重复取空
        val ruleName: String = billInfo?.ruleName ?: ""
    }

    /**
     * 使用AI分析账单数据
     * 当 aiVisionRecognition 开启且 image 非空时，将图片直接发给大模型进行视觉识别。
     * @return 成功时返回 BillInfoModel，失败时返回 null 且 errorMsg 会反映 AI 错误信息
     */
    private suspend fun analyzeWithAI(
        app: String,
        data: String,
        dataType: DataType,
        image: String = "",
        manual: Boolean = false,
        pageContext: String = ""
    ): Triple<BillInfoModel?, String?, Boolean> {
        if (!SettingUtils.featureAiAvailable()) {
            ServerLog.d("AI disabled, skip")
            return Triple(null, null, false)
        }
        // All entry points, including automatic OCR, respect the recognition toggle.
        if (!SettingUtils.aiBillRecognition()) {
            ServerLog.d("AI bill recognition disabled, skip")
            return Triple(null, null, false)
        }
        if (SettingUtils.apiKey("").isBlank() || SettingUtils.apiUri("").isBlank() || SettingUtils.apiModel("").isBlank()) {
            return Triple(null, null, false)
        }
        val useVision = SettingUtils.aiVisionRecognition() && image.isNotBlank()
        ServerLog.d("AI analyzing, useVision=$useVision")
        val result = BillTool().execute(data, app, dataType, if (useVision) image else "", pageContext)
        return result.fold(
            onSuccess = { bill ->
                if (bill == null) {
                    ServerLog.d("AI returned no valid bill")
                    Triple(null, null, true)
                } else {
                    Triple(bill.apply {
                        ruleName = "AI 生成"
                        state = BillState.Wait2Edit
                        this.app = app
                        ServerLog.d("AI parsed: type=$type, money=$money, shop=$shopName")
                    }, null, false)
                }
            },
            onFailure = { e ->
                ServerLog.e("AI analysis failed: ${e.message}", e)
                Triple(null, e.message ?: "Unknown error", false)
            }
        )
    }

    /**
     * 解析账单信息
     * @param result 解析结果
     * @param app 应用
     * @param dataType 数据类型
     * @return 账单信息
     */
    private suspend fun parseBillInfo(
        result: String,
        app: String,
        dataType: DataType
    ): BillInfoModel? {
        ServerLog.d("Parse result: ${result.take(80)}...")
        val json =
            runCatchingExceptCancel { Gson().fromJson(result, JsonObject::class.java) }.getOrNull()
                ?: run {
                    ServerLog.d("JSON parse failed, return null")
                    return null
                }
        // 使用安全的 JSON 访问扩展函数
        return BillInfoModel().apply {
            type = runCatchingExceptCancel {
                BillType.valueOf(json.safeGetString("type", "Expend"))
            }.getOrDefault(BillType.Expend)

            this.app = app
            time = json.safeGetLong("time", 0)
            val timeText = json.safeGetString("timeText", "")
            if (time == 0L) {
                ServerLog.d("Time=0, try parse from timeText")
                if (timeText.isEmpty()) {
                    time = System.currentTimeMillis()
                } else {
                    runCatchingExceptCancel {
                        time = DateUtils.toEpochMillis(timeText)
                    }.onFailure {
                        ServerLog.e("Time parse failed, use now", it)
                        time = System.currentTimeMillis()
                    }
                }
            }
            //  DateUtils.toEpochMillis(timeText)


            money = json.safeGetDouble("money", 0.0).roundAmount()
            fee = json.safeGetDouble("fee", 0.0).roundAmount()
            shopName = json.safeGetString("shopName")
            shopItem = json.safeGetString("shopItem")
            accountNameFrom = json.safeGetString("accountNameFrom")
            accountNameTo = json.safeGetString("accountNameTo")
            channel = json.safeGetString("channel")
            detailsJson = json.get("extra")?.takeIf { it.isJsonObject }?.toString() ?: "{}"
            transactionId = json.safeGetString("transactionId")
            transactionNamespace = json.safeGetString("transactionNamespace")
            refundId = json.safeGetString("refundId")
            originalTransactionId = json.safeGetString("originalTransactionId")
            eventStatus = json.safeGetString("eventStatus", "COMPLETED")

            // 构造 CurrencyModel：获取币种代码并查询汇率
            val rawCurrency = json.safeGetString("currency").uppercase().ifEmpty { "CNY" }
            val multiCurrency = SettingUtils.featureMultiCurrency()
            val baseCurrency = SettingUtils.baseCurrency()
            currency = if (multiCurrency && rawCurrency != baseCurrency) {
                // 多币种启用且币种不同，获取汇率
                CurrencyService.buildCurrencyModel(rawCurrency, baseCurrency).toJson()
            } else {
                // 未开启多币种或同币种，直接构造默认模型
                CurrencyModel(
                    code = rawCurrency,
                    baseCurrency = baseCurrency,
                    rate = 1.0,
                    timestamp = System.currentTimeMillis()
                ).toJson()
            }

            // 格式化规则名称 - 添加数据类型前缀
            val rawRuleName = json.safeGetString("ruleName")
            ruleName = formatRuleName(rawRuleName, dataType)

            cateName = json.safeGetString("cateName")
            // 这个地方不要带上bookName，因为这里的数据来源是JS生成的，Js里面不会输出bookName和cateName，但是AI会携带cateName
            if (!this.generateByAi()) {
                val rule = Db.get().ruleDao().query(dataType.name, app, rawRuleName)
                auto = rule?.autoRecord ?: false
                ServerLog.d("Rule matched: rule=$ruleName, auto=$auto")
            }
        }
    }

    /**
     * 对账单进行自动分类处理
     *
     * 如果账单需要重新分类，则使用分类规则引擎来确定账本和分类。
     * 分类规则基于账单的类型、金额、商家名称和商品信息。
     *
     * @param bill 需要分类的账单信息
     */
    private suspend fun categorize(bill: BillInfoModel, dataType: DataType) {
        // 自定义分类规则匹配前统一清洗商户元数据：
        // 与自动记住分类时的清洗策略保持一致，避免“保存时清洗、匹配时未清洗”导致规则失效。
        val sanitizedShopName = sanitizeCategoryMetaForRule(bill.shopName)
        val sanitizedShopItem = sanitizeCategoryMetaForRule(bill.shopItem)

        val win = JsonObject().apply {
            addProperty("type", bill.type.name)
            addProperty("money", bill.money)
            addProperty("shopName", bill.shopName)
            addProperty("shopItem", bill.shopItem)
            addProperty("shopNameSanitized", sanitizedShopName)
            addProperty("shopItemSanitized", sanitizedShopItem)
            // 注入格式化后的实际账单时间（24小时制：HH:mm），供分类规则使用
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(bill.time))
            addProperty("time", timeStr)
            addProperty("ruleName", bill.ruleName)
            addProperty("channel", bill.channel)
        }

        val js = ruleGenerator.category()
        val categoryJson = runCatchingExceptCancel {
            Gson().fromJson(
                executeJs(js, win.toString()),
                JsonObject::class.java
            )
        }.getOrNull()
        ServerLog.d("Category rule evaluated")
        // 设置账本名称与分类（优先规则结果，否则默认值）
        // 将账本指针（如"默认账本"）解析为数据库中真实存在的账本名称
        val rawBookName = categoryJson.safeGetStringNonBlank("book", SettingUtils.bookName())
        val resolvedBook = resolveBookByNameOrDefault(rawBookName)
        bill.bookName = resolvedBook.name
        bill.cateName = categoryJson.safeGetStringNonBlank("category", bill.cateName.ifBlank { "其他" })
        bill.remark = categoryJson.safeGetStringNonBlank("remark", "")
        val categoryTool = CategoryTool()
        val categories = categoryTool.loadCategories(resolvedBook.remoteId, bill.type)

        // 先应用用户分类映射，再判断分类规则结果是否真实存在于当前账本。
        var resolvedCategory = resolveMappedCategory(bill, categories)
        val needsAiCategory = !bill.hasValidCategory()

        // AI分类识别需要总开关和分类开关同时开启
        if ((needsAiCategory || resolvedCategory == null) &&
            SettingUtils.featureAiAvailable() &&
            SettingUtils.aiCategoryRecognition()
        ) {
            bill.cateName = categoryTool.execute(
                win.toString(),
                bill.app,
                dataType,
                categories
            ).takeUnless { it.isNullOrEmpty() } ?: bill.cateName
            ServerLog.d("AI category evaluated")

            resolvedCategory = resolveMappedCategory(bill, categories)
        }

        if (resolvedCategory == null) {
            bill.cateName = CategoryTool.fallbackCategory(CategoryTool.categoryPaths(categories))
                ?: bill.cateName
            ServerLog.d("Category fallback evaluated")
        }
    }

    /**
     * 清洗用于“自定义分类规则匹配”的商户元数据。
     *
     * 设计目标：
     * 1) 移除金额噪声（货币符号、金额单位）以提高规则稳定性
     * 2) 保留业务数字特征（如 12306、iPhone15），避免过度清洗导致误伤
     * 3) 与客户端自动记住分类的清洗逻辑保持一致
     */
    private fun sanitizeCategoryMetaForRule(inputRaw: String): String {
        if (inputRaw.isEmpty()) return inputRaw
        return inputRaw
            // 清理金额格式：¥100.00 / $5.5
            .replace(Regex("[¥$€£￥]\\s*\\d+(?:\\.\\d+)?"), "")
            // 清理带金额单位的片段：100元 / 88.8块 / 30円
            .replace(Regex("\\d+(?:\\.\\d+)?\\s*[元円块]"), "")
            // 清理纯字母长串（订单号残余）
            .replace(Regex("[A-Za-z]{6,}"), "")
            // 清理多余空白和标点残留
            .replace(Regex("[\\s\\-_/]+"), " ")
            .trim()
    }

    /**
     * 格式化规则名称 - 添加数据类型前缀
     * @param rawRuleName 原始规则名称
     * @param dataType 数据类型
     * @return 格式化后的规则名称
     */
    private fun formatRuleName(rawRuleName: String, dataType: DataType): String {
        if (rawRuleName.isEmpty()) return rawRuleName

        val prefix = when (dataType) {
            DataType.NOTICE -> "通知"
            DataType.DATA -> "数据"
            DataType.OCR -> "OCR"
        }

        return "$prefix·$rawRuleName"
    }

    /**
     * 格式化父账单规则名称
     * @return 父账单专用的规则名称
     */
    private fun formatParentBillRuleName(): String = "由多个账单合并生成"

    /**
     * 关闭服务，释放资源
     *
     * 主要是关闭JavaScript执行器以释放相关资源
     */
    override fun close() {
        jsExecutor.close()
    }

    // endregion

    companion object {
        private val analysisMutex = Mutex()

        /**
         * 去重锁：确保账单入库和去重查询串行执行，避免并发竞态
         *
         * 并发场景下的问题：
         * - 账单A入库 → 查询重复 → 没找到
         * - 账单B入库 → 查询重复 → 找到A
         * - 结果：A和B应该去重但A先入库时还找不到B
         *
         * 解决方案：用锁保护"入库+去重查询"这个关键区间
         */


        /**
         * 悬浮窗启动全局队列：确保多次触发时严格按序执行，避免并发拉起
         */
        private val floatingIntentChannel = Channel<BillInfoIntent>(Channel.UNLIMITED)
        private val floatingIntentScope =
            CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

        private var lastStart = 0L
        init {
            // 在主线程上顺序消费队列，保证顺序与 UI 线程安全
            floatingIntentScope.launch {
                for (intent in floatingIntentChannel) {

                    runCatchingExceptCancel {
                        Server.application.startActivity(intent.toIntent())
                    }.onFailure { throwable ->
                        ServerLog.e("Floating window failed: $throwable", throwable)
                    }
                    if (System.currentTimeMillis() - lastStart <= 300) {
                        delay(300)
                    }
                    lastStart = System.currentTimeMillis()
                }
            }
        }
    }
}
