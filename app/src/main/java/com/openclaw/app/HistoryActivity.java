package com.openclaw.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    public static final String EXTRA_CONV_ID = "conv_id";

    private RecyclerView rvHistory;
    private View         emptyHint;
    private List<ConversationStore.ConvSummary> convList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        rvHistory = findViewById(R.id.rvHistory);
        emptyHint = findViewById(R.id.emptyHint);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        loadList();
    }

    private void loadList() {
        convList = ConversationStore.loadAll(this);
        if (convList.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            rvHistory.setVisibility(View.GONE);
        } else {
            emptyHint.setVisibility(View.GONE);
            rvHistory.setVisibility(View.VISIBLE);
            rvHistory.setAdapter(new HistoryAdapter());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ConversationStore.ConvSummary s = convList.get(pos);
            h.tvTitle.setText(s.title);
            h.tvTime.setText(s.timeLabel() + " · " + s.msgCount + " 条消息");

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(HistoryActivity.this, MainActivity.class);
                i.putExtra(EXTRA_CONV_ID, s.id);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            });

            h.btnDelete.setOnClickListener(v ->
                new AlertDialog.Builder(HistoryActivity.this)
                    .setMessage("删除这条对话？")
                    .setPositiveButton("删除", (d, w) -> {
                        ConversationStore.delete(HistoryActivity.this, s.id);
                        int idx = h.getAdapterPosition();
                        convList.remove(idx);
                        notifyItemRemoved(idx);
                        if (convList.isEmpty()) {
                            emptyHint.setVisibility(View.VISIBLE);
                            rvHistory.setVisibility(View.GONE);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show()
            );
        }

        @Override public int getItemCount() { return convList.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView    tvTitle, tvTime;
            ImageButton btnDelete;
            VH(View v) {
                super(v);
                tvTitle   = v.findViewById(R.id.tvTitle);
                tvTime    = v.findViewById(R.id.tvTime);
                btnDelete = v.findViewById(R.id.btnDelete);
            }
        }
    }
}