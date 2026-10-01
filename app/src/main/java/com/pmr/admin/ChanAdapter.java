package com.pmr.admin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* Адаптер списка абонентов V3.2.
 * Без изменений по сравнению с V3.1 — формат строки "%05d %s".
 */
public class ChanAdapter extends RecyclerView.Adapter<ChanAdapter.VH> {

    public static final String KEY_BANS_LOCAL = "bans_local";
    public static final int ADMIN_ID = 11777;

    private final Context ctx;
    private List<ChanList.Item> items = new ArrayList<>();
    private int activeClient = -1;
    private ListFile listFile;

    public ChanAdapter(Context c) {
        this.ctx = c;
    }

    public void setData(List<ChanList.Item> items, int activeClient, ListFile lf) {
        SharedPreferences sp = ctx.getSharedPreferences(
                PasswordActivity.PREFS, Context.MODE_PRIVATE);
        Set<String> bansLocal = sp.getStringSet(KEY_BANS_LOCAL,
                new HashSet<String>());

        List<ChanList.Item> sorted = new ArrayList<>();
        ChanList.Item active = null;

        for (ChanList.Item it : items) {
            if (bansLocal.contains(String.valueOf(it.Id))) {
                it.banLocal = 1;
            } else {
                it.banLocal = 0;
            }
            if (it.i == activeClient) {
                active = it;
            } else {
                sorted.add(it);
            }
        }

        Collections.sort(sorted, new Comparator<ChanList.Item>() {
            @Override
            public int compare(ChanList.Item a, ChanList.Item b) {
                boolean aAdmin = (a.Id == ADMIN_ID);
                boolean bAdmin = (b.Id == ADMIN_ID);
                if (aAdmin && !bAdmin) return 1;
                if (!aAdmin && bAdmin) return -1;
                return Integer.compare(a.Id, b.Id);
            }
        });

        if (active != null) sorted.add(0, active);

        this.items = sorted;
        this.activeClient = activeClient;
        this.listFile = lf;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chan, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChanList.Item it = items.get(position);
        String nm = (listFile != null) ? listFile.getName(it.Id) : "";
        if (nm == null) nm = "";
        nm = nm.trim();
        String line = String.format("%05d %s", it.Id, nm);

        h.text.setText(line);
        h.text.setTextSize(24f);

        if (it.i == activeClient) {
            h.text.setTextColor(ContextCompat.getColor(ctx, R.color.c_red));
            h.text.setTypeface(null, Typeface.BOLD);
        } else {
            h.text.setTextColor(ContextCompat.getColor(ctx, R.color.c_black));
            h.text.setTypeface(null, Typeface.NORMAL);
        }

        boolean banned = (it.ban == 1) || (it.banLocal == 1);

        if (it.Id == ADMIN_ID) {
            h.btnBan.setText("—");
            h.btnBan.setEnabled(false);
            h.btnBan.setBackgroundTintList(
                    ContextCompat.getColorStateList(ctx, R.color.c_gray));
            h.btnBan.setOnClickListener(null);
        } else {
            h.btnBan.setText("БАН");
            h.btnBan.setEnabled(true);
            if (banned) {
                h.btnBan.setBackgroundTintList(
                        ContextCompat.getColorStateList(ctx, R.color.c_red));
            } else {
                h.btnBan.setBackgroundTintList(
                        ContextCompat.getColorStateList(ctx, R.color.c_green));
            }

            final int cli = it.i;
            final int itId = it.Id;
            final boolean curBanned = banned;
            h.btnBan.setOnClickListener(v -> {
                if (PmrService.pmrSocket == null) {
                    Toast.makeText(ctx, R.string.toast_ban_null,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!PmrService.pmrSocket.isRunning()) {
                    Toast.makeText(ctx, R.string.toast_ban_not_running,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                PmrService.pmrSocket.sendBan(cli);

                SharedPreferences sp2 = ctx.getSharedPreferences(
                        PasswordActivity.PREFS, Context.MODE_PRIVATE);
                Set<String> set = sp2.getStringSet(KEY_BANS_LOCAL,
                        new HashSet<String>());
                Set<String> newSet = new HashSet<>(set);
                if (curBanned) {
                    newSet.remove(String.valueOf(itId));
                } else {
                    newSet.add(String.valueOf(itId));
                }
                sp2.edit().putStringSet(KEY_BANS_LOCAL, newSet).apply();

                String msg = ctx.getString(R.string.toast_ban_sent, cli)
                        + (curBanned ? " (разбан)" : " (бан)");
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView text;
        Button btnBan;
        VH(@NonNull View v) {
            super(v);
            text = v.findViewById(R.id.itemText);
            btnBan = v.findViewById(R.id.itemBtnBan);
        }
    }
}