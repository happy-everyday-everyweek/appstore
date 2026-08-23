package me.spica27.spicamusic.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 商店主页 ViewModel：底栏页面状态 */
class StoreViewModel : ViewModel() {
    private val _currentPage = MutableStateFlow(StorePage.Discover)
    val currentPage: StateFlow<StorePage> = _currentPage.asStateFlow()

    fun navigateTo(page: StorePage) {
        _currentPage.value = page
    }
}
