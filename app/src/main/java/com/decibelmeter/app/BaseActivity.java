package com.decibelmeter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

/**
 * 带「语言」菜单的基类，所有页面共用。
 */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, Menu.FIRST, 0, R.string.lang_title);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == Menu.FIRST) {
            showLanguageDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLanguageDialog() {
        String[] names = {
                getString(R.string.lang_auto),
                getString(R.string.lang_zh),
                getString(R.string.lang_en),
        };
        String cur = LocaleHelper.current(this);
        int checked = cur.equals("zh") ? 1 : cur.equals("en") ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    LocaleHelper.save(this, which == 1 ? "zh" : which == 2 ? "en" : "auto");
                    dialog.dismiss();
                    recreate();
                })
                .show();
    }
}
