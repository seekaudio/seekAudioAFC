package org.appspot.apprtc.util;

import android.app.Activity;
import android.content.Context;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.appspot.apprtc.R;

import java.io.FileOutputStream;
import java.io.IOException;

public class LogviewHelper {

    private Context mContext;
    private Activity mActivity;
    private boolean mDebug;
    private TextView textView;

     public  LogviewHelper(Context context, boolean isDebug) throws Exception {
        mDebug = isDebug;
        if (context instanceof Activity) {
            mActivity = (Activity) context;
            View mDecorView = mActivity.getWindow().getDecorView();
            textView = new TextView(mActivity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 400);
            params.gravity = Gravity.BOTTOM;
            textView.setLayoutParams(params);
            ((ViewGroup) mDecorView).addView(textView);
            setupTextView(textView);
        } else {
            throw new Exception("Can't be used in Non-Activity context");
        }
    }

    public void log(String message) {
        if (!mDebug) return;
        mActivity.runOnUiThread(() -> {
            textView.append(message + "\n");
        });
    }

    private ActionMode actionMode;
    private void setupTextView(TextView textView) {
        // 启用文本选择功能
        textView.setTextIsSelectable(true);

        // 设置长按监听器
        textView.setOnLongClickListener(v -> {

            // 启动自定义 ActionMode
            if (actionMode == null) {
                actionMode = mActivity.startActionMode(actionModeCallback);
            }
            return true;
        });
    }

    // ActionMode 回调
    private final ActionMode.Callback actionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // 加载自定义菜单
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.textview_context_menu, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == R.id.menu_save) {
                // 保存到本地
                saveTextToFile(textView.getText().toString());
                mode.finish();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
        }
    };

    // 保存文本到文件
    private void saveTextToFile(String text) {
        String fileName = "saved_text" /*+ System.currentTimeMillis()*/ + ".txt";
        try (FileOutputStream fos = mActivity.openFileOutput(fileName, Context.MODE_PRIVATE)) { //在应用目录files/下
            fos.write(text.getBytes());
            Toast.makeText(mActivity, "保存成功: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(mActivity, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }
}
