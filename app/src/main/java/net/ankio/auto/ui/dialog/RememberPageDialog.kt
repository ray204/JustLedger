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

package net.ankio.auto.ui.dialog

import android.content.Context
import net.ankio.auto.databinding.DialogRememberPageBinding
import net.ankio.auto.service.ocr.PageSignature
import net.ankio.auto.service.ocr.PageSignatureManager
import net.ankio.auto.ui.api.BaseSheetDialog

object RememberPageDialog {
    fun show(context: Context, packageName: String, activityName: String, structureFingerprint: String = "") {
        if (!PageSignatureManager.shouldAsk(packageName, activityName, structureFingerprint)) return
        BaseSheetDialog.create<RememberPageChoiceDialog>(context)
            .configure(PageSignature(packageName, activityName, structureFingerprint)).show()
    }
}

class RememberPageChoiceDialog internal constructor(context: Context) : BaseSheetDialog<DialogRememberPageBinding>(context) {
    fun configure(page: PageSignature) = apply {
        binding.rememberButton.setOnClickListener { PageSignatureManager.add(page.copy(never = false)); dismiss() }
        binding.neverButton.setOnClickListener { PageSignatureManager.add(page.copy(never = true)); dismiss() }
        // Later / dismissal intentionally do not persist a decision.
        binding.laterButton.setOnClickListener { dismiss() }
    }
}
