package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.owen.tvrecyclerview.widget.GridLayoutManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SelectDialog<T> extends BaseDialog {
    public SelectDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_select);
    }

    public SelectDialog(@NonNull @NotNull Context context, int resId) {
        super(context);
        setContentView(resId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public void setTip(String tip) {
        ((TextView) findViewById(R.id.title)).setText(tip);
    }

    public void setAdapter(SelectDialogAdapter.SelectDialogInterface<T> sourceBeanSelectDialogInterface,
                           DiffUtil.ItemCallback<T> sourceBeanItemCallback,
                           List<T> data, int select) {
        SelectDialogAdapter<T> adapter = new SelectDialogAdapter<>(sourceBeanSelectDialogInterface, sourceBeanItemCallback);
        adapter.setData(data, select);
        TvRecyclerView tvRecyclerView = findViewById(R.id.list);
        tvRecyclerView.setAdapter(adapter);
        tvRecyclerView.post(() -> {
            RecyclerView.LayoutManager layoutManager = tvRecyclerView.getLayoutManager();
            if (layoutManager instanceof GridLayoutManager) {
                GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
                int spanCount = gridLayoutManager.getNumColumns();
                int rowIndex = select / spanCount;
                int firstVisibleRow = gridLayoutManager.getFirstVisiblePosition() / spanCount;
                int lastVisibleRow = gridLayoutManager.getLastVisiblePosition() / spanCount;
                // 平滑滚动并调整位置
                if (rowIndex < firstVisibleRow || rowIndex > lastVisibleRow) {
                    int offset = tvRecyclerView.getHeight() / 3;
                    gridLayoutManager.scrollToPositionWithOffset(select, offset);
                }
//                tvRecyclerView.postDelayed(() -> tvRecyclerView.smoothScrollToPosition(select), 200);
            } else if (layoutManager instanceof LinearLayoutManager) {
                LinearLayoutManager linearLayoutManager = (LinearLayoutManager) layoutManager;
                int offset = tvRecyclerView.getHeight() / 3;
                linearLayoutManager.scrollToPositionWithOffset(select, offset);
//                tvRecyclerView.postDelayed(() -> tvRecyclerView.smoothScrollToPosition(select), 200);
            }
        });
    }

}
