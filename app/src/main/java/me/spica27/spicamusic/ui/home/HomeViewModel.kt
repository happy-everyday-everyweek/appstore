package me.spica27.spicamusic.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首页 ViewModel（Only 版）
 * 负责底栏页面状态（推荐 / 全部 / 设置）。
 * 商店数据（聚合包 / 推荐包 / 下载）见 [me.spica27.spicamusic.store.StoreRepository] 与 StoreViewModel。
 */
class HomeViewModel : ViewModel() {
    private val _currentPage = MutableStateFlow(HomePage.Discover)
    val currentPage: StateFlow<HomePage> = _currentPage.asStateFlow()

    fun navigateToPage(page: HomePage) {
        _currentPage.value = page
    }
}
