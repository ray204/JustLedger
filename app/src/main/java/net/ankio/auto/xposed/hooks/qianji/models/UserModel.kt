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

package net.ankio.auto.xposed.hooks.qianji.models

import de.robv.android.xposed.XposedHelpers
import net.ankio.auto.xposed.core.api.HookerClazz
import net.ankio.dex.model.Clazz
import net.ankio.dex.model.ClazzMethod

class UserModel {

    companion object : HookerClazz() {
        // 目标类：钱迹应用的 UserManager（使用 Manifest 规则名进行解析）
        private val userManagerClazz by lazy { clazz() }

        // 提供给 Manifest 的规则定义；使用现有规则名以保持兼容
        override var rule = Clazz(
            name = this::class.java.name,
            nameRule = "^\\w{0,2}\\..+",
            type = "class",
            methods =
                listOf(
                    ClazzMethod(
                        name = "isLogin",
                        returnType = "boolean",
                    ),
                    ClazzMethod(
                        name = "isVip",
                        returnType = "boolean",
                    ),
                    ClazzMethod(
                        name = "isSuperVIP",
                        returnType = "boolean",
                    ),
                ),
        )

        /**
         * 获取 UserManager 单例实例
         */
        private fun getInstance(): Any {
            return XposedHelpers.callStaticMethod(userManagerClazz, "getInstance")
        }

        /**
         * 是否已登录
         */
        fun isLogin(): Boolean {
            return XposedHelpers.callMethod(getInstance(), "isLogin") as Boolean
        }

        /**
         * 获取已登录用户ID
         */
        fun getLoginUserID(): String {
            return XposedHelpers.callMethod(getInstance(), "getLoginUserID") as String
        }

        /**
         * 是否为 VIP 用户
         */
        fun isVip(): Boolean {
            return XposedHelpers.callMethod(getInstance(), "isVip") as Boolean
        }
    }
}