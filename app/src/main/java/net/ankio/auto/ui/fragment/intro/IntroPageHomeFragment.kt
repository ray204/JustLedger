package net.ankio.auto.ui.fragment.intro

import android.os.Bundle
import android.view.View
import net.ankio.auto.databinding.FragmentIntroPageHomeBinding
import net.ankio.auto.ui.adapter.IntroPagerAdapter
import net.ankio.auto.utils.PrefManager

class IntroPageHomeFragment : BaseIntroPageFragment<FragmentIntroPageHomeBinding>() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        binding.btnStart.setOnClickListener {
            PrefManager.workMode = net.ankio.auto.constant.WorkMode.Ocr
            vm.pageRequest.value = IntroPagerAdapter.IntroPage.PERMISSION
        }


    }

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
