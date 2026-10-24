package org.ezbook.server.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ReceiptDetails {
    fun lines(json: String): List<String> {
        val result=mutableListOf<String>()
        fun visit(value: com.google.gson.JsonElement, path: String, depth: Int) {
            if (result.size >= 30 || depth > 5 || value.isJsonNull) return
            when {
                value.isJsonObject -> value.asJsonObject.entrySet().filter { it.key != ReceiptIdentity.FIELD }.forEach { (key,item) -> visit(item, listOf(path,key).filter { it.isNotBlank() }.joinToString(" · "),depth+1) }
                value.isJsonArray -> value.asJsonArray.forEachIndexed { i,item -> visit(item,"$path ${i+1}",depth+1) }
                else -> result.add("$path：${value.asString.take(300)}")
            }
        }
        visit(read(json),"",0)
        return result
    }
    fun read(json: String): JsonObject = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { JsonObject() }
    /** Existing values win, recursively. Arrays are treated as a single fact, never concatenated. */
    fun merge(existing: String, incoming: String): String {
        val target = read(existing)
        fun fill(a: JsonObject, b: JsonObject, depth: Int) {
            if (depth > 5) return
            for ((key,value) in b.entrySet()) {
                val old = a.get(key)
                if (old == null || old.isJsonNull || old.isJsonPrimitive && old.asString.isBlank()) a.add(key,value.deepCopy())
                else if (old.isJsonObject && value.isJsonObject) fill(old.asJsonObject,value.asJsonObject,depth+1)
            }
        }
        fill(target,read(incoming),0)
        return target.toString()
    }
}
