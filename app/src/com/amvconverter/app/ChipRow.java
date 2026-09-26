package com.amvconverter.app;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A row of selectable chips.
 *
 * <p>Replaces the spinners the first version used: with three to seven short options per setting,
 * chips show every choice at once, need one tap instead of two, and - unlike a spinner - cannot
 * wrap a label onto three lines in a narrow column.
 */
final class ChipRow {

    interface OnSelect {
        void onSelect(int index);
    }

    private ChipRow() {
    }

    static void build(LinearLayout container, Object[] items, int selected, final OnSelect callback) {
        container.removeAllViews();
        final Context ctx = container.getContext();
        final TextView[] chips = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView chip = new TextView(ctx);
            chip.setText(items[i].toString());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTextColor(ctx.getResources().getColorStateList(R.color.chip_text));
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setGravity(Gravity.CENTER);
            chip.setMinWidth(dp(ctx, 56));
            chip.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
            chip.setSelected(i == selected);
            if (i > 0) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginStart(dp(ctx, 8));
                chip.setLayoutParams(lp);
            }
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    for (int k = 0; k < chips.length; k++) {
                        chips[k].setSelected(k == index);
                    }
                    if (callback != null) callback.onSelect(index);
                }
            });
            chips[i] = chip;
            container.addView(chip);
        }
    }

    /** Marks a chip selected without firing the callback (used to restore state). */
    static void select(LinearLayout container, int index) {
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setSelected(i == index);
        }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
