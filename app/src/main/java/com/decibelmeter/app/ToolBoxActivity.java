package com.decibelmeter.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具箱主页：功能列表，未实现的功能置灰占位。
 */
public class ToolBoxActivity extends BaseActivity {

    private static final class Tool {
        final int nameRes;
        final Class<? extends Activity> target;

        Tool(int nameRes, Class<? extends Activity> target) {
            this.nameRes = nameRes;
            this.target = target;
        }

        boolean available() {
            return target != null;
        }
    }

    private final List<Tool> tools = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);

        tools.add(new Tool(R.string.title_decibel, MainActivity.class));
        tools.add(new Tool(R.string.title_dns, DnsActivity.class));
        tools.add(new Tool(R.string.title_isp, IspActivity.class));
        // 局域网扫描、共享文件夹、远程桌面合成一个入口：三者面向的是同一批设备，
        // 分开列会让人先想「我该点哪个」而不是直接点那台机器
        tools.add(new Tool(R.string.title_lan, LanScanActivity.class));
        // 「已下载 / 最近打开」不挂在局域网页里：它的数据跟连的是哪台机器无关，
        // 而局域网的每个入口都是「一台机器 + 一个目录」
        tools.add(new Tool(R.string.smb_downloads_title, SmbDownloadsActivity.class));
        tools.add(new Tool(R.string.tool_coming_soon, null));

        List<String> names = new ArrayList<>();
        for (Tool tool : tools) {
            names.add(getString(tool.nameRes));
        }

        ListView listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1,
                android.R.id.text1,
                names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (!tools.get(position).available()) {
                    ((TextView) v).setTextColor(Color.GRAY);
                }
                return v;
            }
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Tool tool = tools.get(position);
            if (tool.available()) {
                startActivity(new Intent(this, tool.target));
            } else {
                Toast.makeText(this, R.string.tool_soon, Toast.LENGTH_SHORT).show();
            }
        });
        setContentView(listView);
    }
}
