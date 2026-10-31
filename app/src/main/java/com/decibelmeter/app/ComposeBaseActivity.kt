package com.decibelmeter.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * Compose 页面的基类：沿用 [LocaleHelper] 的语言切换（与 [BaseActivity] 同一套行为），
 * 并把「语言」这一项固定占掉 Menu.FIRST，子类的条目从 Menu.FIRST + 1 起排。
 *
 * 单独开一个基类而不是让 [BaseActivity] 改成继承 AndroidX：分贝仪那几页仍然只用系统控件，
 * 把它们连带迁到 AndroidX 的 ComponentActivity 没有收益，反而要重测所有已实测过的行为。
 */
abstract class ComposeBaseActivity : ComponentActivity() {

    /**
     * 最近一次创建的 options menu。
     *
     * 公开给子类是因为 Compose 页面里没有 `invalidateOptionsMenu()` 那种「菜单自己会重问一遍
     * onCreateOptions」的可靠行为（系统 ActionBar 的菜单是命令式的），改完一个开关要立刻把
     * 勾对上，只能握着这个引用自己改。
     */
    protected var optionsMenu: Menu? = null
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ToolboxTheme {
                Content()
            }
        }
    }

    /** 子类的界面本体，已经在 [ToolboxTheme] 的 MaterialTheme 里。 */
    @Composable
    protected abstract fun Content()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, Menu.FIRST, 0, R.string.lang_title)
        onCreateComposeOptions(menu)
        optionsMenu = menu
        return true
    }

    /** 子类往 options menu 里加自己的条目。 */
    protected open fun onCreateComposeOptions(menu: Menu) {}

    override fun onDestroy() {
        optionsMenu = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == Menu.FIRST) {
            showLanguageDialog()
            return true
        }
        return onMenuItemSelected(item.itemId)
    }

    /** @return true 表示这个条目已由子类处理 */
    protected open fun onMenuItemSelected(id: Int): Boolean = false

    private fun showLanguageDialog() {
        val names = arrayOf(
                getString(R.string.lang_auto),
                getString(R.string.lang_zh),
                getString(R.string.lang_en))
        val cur = LocaleHelper.current(this)
        val checked = if (cur == "zh") 1 else if (cur == "en") 2 else 0
        AlertDialog.Builder(this)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(names, checked) { dialog, which ->
                    LocaleHelper.save(this, if (which == 1) "zh" else if (which == 2) "en" else "auto")
                    dialog.dismiss()
                    recreate()
                }
                .show()
    }
}
