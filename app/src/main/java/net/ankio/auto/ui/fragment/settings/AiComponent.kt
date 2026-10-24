package net.ankio.auto.ui.fragment.settings

import android.view.View
import net.ankio.auto.databinding.ComponentAiBinding
import net.ankio.auto.http.api.AiAPI
import net.ankio.auto.ui.api.BaseComponent
import net.ankio.auto.utils.PrefManager

/** Three explicit inputs for an OpenAI-compatible endpoint. Credentials live only in phone settings. */
class AiComponent(binding: ComponentAiBinding) : BaseComponent<ComponentAiBinding>(binding) {
    override fun onComponentCreate() {
        super.onComponentCreate()
        binding.etAiBaseUrl.setText(PrefManager.apiUri)
        binding.etAiToken.setText(PrefManager.apiKey)
        binding.actAiModel.setText(PrefManager.apiModel)
        binding.btnTestAi.setOnClickListener { saveAndTest() }
    }

    private fun saveAndTest() = with(binding) {
        val url=etAiBaseUrl.text.toString().trim().trimEnd('/')
        val key=etAiToken.text.toString().trim()
        val model=actAiModel.text.toString().trim()
        tilAiBaseUrl.error=null; tilAiToken.error=null; tilAiModel.error=null
        if (!url.startsWith("https://") && !url.startsWith("http://")) { tilAiBaseUrl.error="填写完整接口地址"; return@with }
        if (key.isBlank()) { tilAiToken.error="填写 API Key"; return@with }
        if (model.isBlank()) { tilAiModel.error="填写模型名称"; return@with }
        PrefManager.apiProvider="ChatGPT" // Protocol adapter only; no particular vendor is hardcoded.
        PrefManager.apiUri=url; PrefManager.apiKey=key; PrefManager.apiModel=model
        PrefManager.aiVisionRecognition=false
        btnTestAi.isEnabled=false
        btnTestAi.text="正在测试…"
        cardTestResult.visibility=View.VISIBLE
        tvTestResultContent.text="已保存在本机，正在测试…"
        launch {
            try {
                val result=AiAPI.request("仅回复 OK。", "连接测试", provider="ChatGPT",apiKey=key,apiUri=url,model=model)
                if (result.isSuccess && !result.getOrNull().isNullOrBlank()) {
                    PrefManager.featureAiAvailable=true
                    PrefManager.aiBillRecognition=true
                    tvTestResultContent.text="连接成功 · 已开启 AI 优先识别"
                    btnTestAi.text="连接成功 · 再次测试"
                } else {
                    PrefManager.featureAiAvailable=false
                    val message=result.exceptionOrNull()?.message.orEmpty().replace(key,"[已隐藏]").take(160)
                    tvTestResultContent.text="连接失败，当前使用本地规则。\n$message"
                    btnTestAi.text="保存并重试"
                }
            } finally { btnTestAi.isEnabled=true }
        }
    }
}
