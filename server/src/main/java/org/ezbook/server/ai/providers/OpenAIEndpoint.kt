package org.ezbook.server.ai.providers

object OpenAIEndpoint {
    fun url(base: String, resource: String): String {
        val normalized=base.trim().trimEnd('/')
        require(normalized.startsWith("https://") || normalized.startsWith("http://")) { "接口地址需以 https:// 开头" }
        return normalized + (if (normalized.endsWith("/v1")) "" else "/v1") + "/" + resource
    }
}
