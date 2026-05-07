package com.ysajang.ariavoice.ui.navigation

sealed class Screen(val route: String) {
    data object Main : Screen("main")
    data object Settings : Screen("settings")
    data object History : Screen("history")
}
